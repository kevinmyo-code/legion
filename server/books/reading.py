"""Reading position and bookmarks, synced between the phone and the laptop
(books ticket 08, rulings in ticket 07).

| Method | Path | Does |
|---|---|---|
| GET | `/api/books/positions?since=<iso>` | The caller's positions changed at or after `since`. |
| PUT | `/api/books/positions/<sha256>/<variant>` | Upsert; newest `recorded_at` wins. |
| GET | `/api/books/bookmarks?since=<iso>` | The same for bookmarks, tombstones included. |
| PUT | `/api/books/bookmarks/<uuid>` | Create or update. |
| DELETE | `/api/books/bookmarks/<uuid>` | Sets `deleted_at`; idempotent. |

**Always the caller's own rows.** Every query is `household.tenancy.scoped`,
then `user=request.user`: tenancy by household at the ADR 0045 choke point,
then the member, because Mia's place in a book is hers (ticket 07). Another
member's bookmark id is a miss like an id that never existed, and creating
one under that id is refused rather than written over.

**The lists are bare arrays, unpaged.** Ticket 08 has each row carry the
server's `updated_at` so a client takes the largest one it has seen as its
next `since`; there is no `next` cursor and no `{"results": ...}` envelope.
One member holds one position per book copy and a handful of bookmarks per
book, so a page cap would only add a way to stop early. `since` is inclusive
(`>=`), the same posture as every other `?since=` route here
(`api/sync.parse_since`): the row AT the cursor comes back again, and a
client merging by key or by id takes it twice harmlessly, where an exclusive
bound could skip a row written in the same instant. A missing or unreadable
`since` means everything.

**Newest wins by the reader's clock** (ticket 07 ruling 4). A position whose
`recorded_at` is older than the stored one is not written: the answer is 200
with the stored row and `"kept": "newer"`, and the stored row's `updated_at`
does not move, because nothing changed. A write with the SAME `recorded_at`
is applied, so a retried write whose acknowledgement was lost lands rather
than being reported as kept out.

**No book text.** A Readium Locator may carry a `text` member (the words
before, at and after the spot). It is dropped before anything is stored,
because ticket 07 rules the engine holds a hash and a place, never the book.
The rest of the locator is stored as sent.
"""

from __future__ import annotations

import json
import math
import uuid

from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from api.sync import parse_since
from books.models import VARIANTS, BookBookmark, BookPosition
from household.tenancy import household_of, scoped

TAGS = ["books"]

# A locator without its `text` member is a few hundred bytes. The cap is for
# a client bug, not for a real reader.
MAX_LOCATOR_BYTES = 16 * 1024
MAX_CHAPTER_LABEL = 1000
MAX_DEVICE = 64
MAX_LABEL = 500
MAX_NOTE = 20000

KEPT_NEWER = "newer"
KEPT_CHOICES = (KEPT_NEWER,)

NOTHING_SAVED = "Nothing was saved."
HASH_RULE = (
    "{value!r} is not a book hash: it must be the SHA-256 of the book file, 64 hexadecimal "
    "characters."
)
VARIANT_RULE = "The variant must be `original` or `resizable`, not {value!r}."
BAD_HASH = f"{NOTHING_SAVED} {HASH_RULE}"
BAD_VARIANT = f"{NOTHING_SAVED} {VARIANT_RULE}"
BAD_BOOKMARK_ID = (
    "Nothing was saved. {value!r} is not a bookmark id: it must be a UUID the reader chose."
)
BAD_BOOKMARK_ID_DELETE = "Nothing was deleted. {value!r} is not a bookmark id: it must be a UUID."
BAD_PROGRESSION = (
    "must be a number from 0 to 1 saying how far through the book this is (0.43 is 43 percent)."
)
ID_TAKEN = (
    "Nothing was saved. That bookmark id is already in use by another bookmark that is not "
    "yours. Make the bookmark again with a new id."
)
BOOKMARK_MOVED = (
    "Nothing was saved. A bookmark stays in the book and copy it was made in; this one was "
    "made in a different one. Make a new bookmark instead."
)
NO_SUCH_BOOKMARK = "Nothing was deleted. No bookmark of yours has that id."
LOCATOR_TOO_BIG = (
    f"Nothing was saved. The locator is over {MAX_LOCATOR_BYTES // 1024} KiB, which no reader's "
    "position needs."
)

_HEX = set("0123456789abcdef")


def _hash_or_none(raw: str) -> str | None:
    value = (raw or "").strip().lower()
    if len(value) == 64 and set(value) <= _HEX:
        return value
    return None


def _refused(sentence: str, http_status: int = status.HTTP_400_BAD_REQUEST) -> Response:
    return Response({"detail": sentence}, status=http_status)


def _refused_fields(errors) -> Response:
    """A serializer's field errors as one sentence a person can read."""
    parts = []
    for field, messages in errors.items():
        if isinstance(messages, dict):
            messages = [str(message) for message in messages.values()]
        text = " ".join(str(message) for message in messages)
        parts.append(text if field == "non_field_errors" else f"`{field}`: {text}")
    return _refused(f"{NOTHING_SAVED} {' '.join(parts)}")


def _strip_text(locator):
    if isinstance(locator, dict) and "text" in locator:
        return {key: value for key, value in locator.items() if key != "text"}
    return locator


class _Progression(serializers.FloatField):
    default_error_messages = {
        "invalid": BAD_PROGRESSION,
        "max_value": BAD_PROGRESSION,
        "min_value": BAD_PROGRESSION,
        "max_string_length": BAD_PROGRESSION,
        "required": BAD_PROGRESSION,
        "null": BAD_PROGRESSION,
    }

    def __init__(self, **kwargs):
        super().__init__(min_value=0.0, max_value=1.0, **kwargs)

    def to_internal_value(self, data):
        if isinstance(data, bool):
            self.fail("invalid")
        value = super().to_internal_value(data)
        if not math.isfinite(value):
            self.fail("invalid")
        return value


class _Locator(serializers.JSONField):
    def to_internal_value(self, data):
        value = _strip_text(super().to_internal_value(data))
        if len(json.dumps(value, separators=(",", ":"))) > MAX_LOCATOR_BYTES:
            raise serializers.ValidationError(LOCATOR_TOO_BIG)
        return value


# =============================================================================
# Positions
# =============================================================================


class BookPositionSerializer(serializers.Serializer):
    book_sha256 = serializers.CharField(
        help_text="SHA-256 of the book file's bytes, 64 lower-case hex characters."
    )
    variant = serializers.ChoiceField(
        choices=list(VARIANTS),
        help_text="`original`: the file as it is. `resizable`: the EPUB made from a PDF.",
    )
    locator = serializers.JSONField(
        allow_null=True,
        help_text="Readium Locator JSON for an EPUB, a page index for PDF/CBZ, as the reader "
        "sent it minus any `text` member. Null when only `total_progression` is known.",
    )
    total_progression = serializers.FloatField(help_text="0 to 1, how far through the book.")
    chapter_label = serializers.CharField(allow_null=True)
    recorded_at = serializers.DateTimeField(
        help_text="When the reader recorded this position, by the reader's clock. Newest wins."
    )
    device = serializers.CharField(help_text='Which reader wrote it, e.g. "A25" or "web".')
    updated_at = serializers.DateTimeField(
        help_text="When the server last stored this row. Use the largest seen as the next `since`."
    )


class BookPositionPutResponseSerializer(BookPositionSerializer):
    kept = serializers.ChoiceField(
        choices=list(KEPT_CHOICES),
        required=False,
        help_text="Present, as `newer`, only when the write was NOT stored because the stored "
        "position has a newer `recorded_at`. The body is then the stored row, unchanged. Absent "
        "when the write was stored.",
    )


class BookPositionWriteSerializer(serializers.Serializer):
    locator = _Locator(
        required=False,
        allow_null=True,
        default=None,
        help_text="Readium Locator JSON for an EPUB, a page index for PDF/CBZ. A `text` member "
        "is dropped and never stored.",
    )
    total_progression = _Progression()
    chapter_label = serializers.CharField(
        required=False,
        allow_null=True,
        allow_blank=True,
        default=None,
        max_length=MAX_CHAPTER_LABEL,
    )
    recorded_at = serializers.DateTimeField(
        help_text="When the reader recorded this position, by its own clock."
    )
    device = serializers.CharField(
        required=False, allow_blank=True, default="", max_length=MAX_DEVICE
    )


def _position_body(row: BookPosition) -> dict:
    return BookPositionSerializer(row).data


_SHA256_PARAM = OpenApiParameter(
    name="sha256",
    type=OpenApiTypes.STR,
    location=OpenApiParameter.PATH,
    description="SHA-256 of the book file, 64 hex characters (either case; stored lower).",
)
_VARIANT_PARAM = OpenApiParameter(
    name="variant",
    type=OpenApiTypes.STR,
    location=OpenApiParameter.PATH,
    enum=list(VARIANTS),
    description="`original` or `resizable`.",
)
_SINCE_PARAM = OpenApiParameter(
    name="since",
    type=OpenApiTypes.DATETIME,
    location=OpenApiParameter.QUERY,
    required=False,
    description="Rows the server stored at or after this instant (inclusive). Absent or "
    "unreadable means every row.",
)


class BookPositionListView(APIView):
    @extend_schema(
        operation_id="api_books_positions_list",
        tags=TAGS,
        parameters=[_SINCE_PARAM],
        responses={
            200: OpenApiResponse(
                response=BookPositionSerializer(many=True),
                description="The signed-in member's positions, oldest `updated_at` first. A "
                "bare array, never paged.",
            )
        },
    )
    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        rows = (
            scoped(BookPosition, request)
            .filter(user=request.user, updated_at__gte=since)
            .order_by("updated_at", "pk")
        )
        return Response(BookPositionSerializer(rows, many=True).data)


class BookPositionDetailView(APIView):
    @extend_schema(
        operation_id="api_books_positions_update",
        tags=TAGS,
        parameters=[_SHA256_PARAM, _VARIANT_PARAM],
        request=BookPositionWriteSerializer,
        responses={
            200: OpenApiResponse(
                response=BookPositionPutResponseSerializer,
                description="The position as stored. Created or replaced when this "
                "`recorded_at` is the same as or newer than the stored one. Otherwise nothing "
                "was written, the body is the stored row and `kept` is `newer`.",
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="A bad hash, variant, progression or body. Nothing was saved.",
            ),
        },
    )
    def put(self, request, sha256: str, variant: str):
        book = _hash_or_none(sha256)
        if book is None:
            return _refused(BAD_HASH.format(value=sha256))
        if variant not in VARIANTS:
            return _refused(BAD_VARIANT.format(value=variant))
        incoming = BookPositionWriteSerializer(data=request.data)
        if not incoming.is_valid():
            return _refused_fields(incoming.errors)
        fields = incoming.validated_data
        fields["chapter_label"] = fields.get("chapter_label") or None

        def mine():
            return (
                scoped(BookPosition, request)
                .select_for_update()
                .filter(user=request.user, book_sha256=book, variant=variant)
                .first()
            )

        with transaction.atomic():
            stored = mine()
            if stored is None:
                try:
                    with transaction.atomic():
                        created = BookPosition.objects.create(
                            household=household_of(request),
                            user=request.user,
                            book_sha256=book,
                            variant=variant,
                            updated_at=timezone.now(),
                            **fields,
                        )
                    return Response(_position_body(created))
                except IntegrityError as exc:
                    # Another write for this book made the row first. Read it
                    # and compare like any other update.
                    stored = mine()
                    if stored is None:
                        return _refused(f"{NOTHING_SAVED} The database refused it: {exc}")
            if fields["recorded_at"] < stored.recorded_at:
                return Response({**_position_body(stored), "kept": KEPT_NEWER})
            for name, value in fields.items():
                setattr(stored, name, value)
            stored.updated_at = timezone.now()
            stored.save()
            return Response(_position_body(stored))


# =============================================================================
# Bookmarks
# =============================================================================


class BookBookmarkSerializer(serializers.Serializer):
    id = serializers.UUIDField(help_text="Chosen by the reader that made the bookmark.")
    book_sha256 = serializers.CharField(
        help_text="SHA-256 of the book file's bytes, 64 lower-case hex characters."
    )
    variant = serializers.ChoiceField(choices=list(VARIANTS))
    locator = serializers.JSONField(help_text="As sent, minus any `text` member.")
    total_progression = serializers.FloatField(help_text="0 to 1, how far through the book.")
    label = serializers.CharField(allow_null=True)
    note = serializers.CharField(allow_null=True, help_text="What the member typed, if anything.")
    created_at = serializers.DateTimeField(
        help_text="When the bookmark was made: the reader's `created_at` when it sent one, "
        "otherwise when the server first stored it. Never changed by an edit."
    )
    deleted_at = serializers.DateTimeField(
        allow_null=True, help_text="Set when the bookmark was deleted. Drop it locally."
    )
    updated_at = serializers.DateTimeField(
        help_text="When the server last stored this row. Use the largest seen as the next `since`."
    )


class BookBookmarkWriteSerializer(serializers.Serializer):
    book_sha256 = serializers.CharField()
    variant = serializers.CharField()
    locator = _Locator(help_text="Required. A `text` member is dropped and never stored.")
    total_progression = _Progression()
    label = serializers.CharField(
        required=False, allow_null=True, allow_blank=True, default=None, max_length=MAX_LABEL
    )
    note = serializers.CharField(
        required=False, allow_null=True, allow_blank=True, default=None, max_length=MAX_NOTE
    )
    created_at = serializers.DateTimeField(
        required=False,
        allow_null=True,
        default=None,
        help_text="When the member made it, by the reader's clock. Used on create only.",
    )

    def validate_book_sha256(self, value):
        book = _hash_or_none(value)
        if book is None:
            raise serializers.ValidationError(HASH_RULE.format(value=value))
        return book

    def validate_variant(self, value):
        if value not in VARIANTS:
            raise serializers.ValidationError(VARIANT_RULE.format(value=value))
        return value


def _bookmark_body(row: BookBookmark) -> dict:
    return BookBookmarkSerializer(row).data


def _bookmark_id(raw: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(str(raw))
    except (ValueError, AttributeError, TypeError):
        return None


_BOOKMARK_ID_PARAM = OpenApiParameter(
    name="id",
    type=OpenApiTypes.UUID,
    location=OpenApiParameter.PATH,
    description="The bookmark's id, a UUID chosen by the reader that made it.",
)


class BookBookmarkListView(APIView):
    @extend_schema(
        operation_id="api_books_bookmarks_list",
        tags=TAGS,
        parameters=[_SINCE_PARAM],
        responses={
            200: OpenApiResponse(
                response=BookBookmarkSerializer(many=True),
                description="The signed-in member's bookmarks, oldest `updated_at` first, "
                "deleted ones included with `deleted_at` set. A bare array, never paged.",
            )
        },
    )
    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        rows = (
            scoped(BookBookmark, request)
            .filter(user=request.user, updated_at__gte=since)
            .order_by("updated_at", "pk")
        )
        return Response(BookBookmarkSerializer(rows, many=True).data)


class BookBookmarkDetailView(APIView):
    def _mine(self, request, bookmark_id):
        return (
            scoped(BookBookmark, request)
            .select_for_update()
            .filter(user=request.user, pk=bookmark_id)
            .first()
        )

    @extend_schema(
        operation_id="api_books_bookmarks_update",
        tags=TAGS,
        parameters=[_BOOKMARK_ID_PARAM],
        request=BookBookmarkWriteSerializer,
        responses={
            200: OpenApiResponse(
                response=BookBookmarkSerializer,
                description="The bookmark as stored, created or updated. Updating a deleted "
                "bookmark brings it back (`deleted_at` cleared). `created_at` never changes "
                "after the first write.",
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="A bad id, hash, variant, progression or body, or an update that "
                "would move the bookmark to another book or copy. Nothing was saved.",
            ),
            409: OpenApiResponse(
                response=DetailSerializer,
                description="The id belongs to a bookmark that is not the caller's. Nothing was "
                "saved; make the bookmark again with a new id.",
            ),
        },
    )
    def put(self, request, id: str):
        bid = _bookmark_id(id)
        if bid is None:
            return _refused(BAD_BOOKMARK_ID.format(value=id))
        incoming = BookBookmarkWriteSerializer(data=request.data)
        if not incoming.is_valid():
            return _refused_fields(incoming.errors)
        fields = dict(incoming.validated_data)
        created_at = fields.pop("created_at", None)
        fields["label"] = fields.get("label") or None
        fields["note"] = fields.get("note") or None

        with transaction.atomic():
            stored = self._mine(request, bid)
            if stored is None:
                now = timezone.now()
                try:
                    with transaction.atomic():
                        created = BookBookmark.objects.create(
                            id=bid,
                            household=household_of(request),
                            user=request.user,
                            created_at=created_at or now,
                            updated_at=now,
                            **fields,
                        )
                    return Response(_bookmark_body(created))
                except IntegrityError:
                    # Either this member's own retry made it a moment ago, or
                    # the id is someone else's. Only the first may be updated.
                    stored = self._mine(request, bid)
                    if stored is None:
                        return _refused(ID_TAKEN, status.HTTP_409_CONFLICT)
            if (stored.book_sha256, stored.variant) != (fields["book_sha256"], fields["variant"]):
                return _refused(BOOKMARK_MOVED)
            for name in ("locator", "total_progression", "label", "note"):
                setattr(stored, name, fields[name])
            stored.deleted_at = None
            stored.updated_at = timezone.now()
            stored.save()
            return Response(_bookmark_body(stored))

    @extend_schema(
        operation_id="api_books_bookmarks_destroy",
        tags=TAGS,
        parameters=[_BOOKMARK_ID_PARAM],
        request=None,
        responses={
            200: OpenApiResponse(
                response=BookBookmarkSerializer,
                description="The bookmark as stored, with `deleted_at` set. Deleting it again "
                "changes nothing and answers the same.",
            ),
            400: OpenApiResponse(
                response=DetailSerializer, description="The id is not a UUID. Nothing changed."
            ),
            404: OpenApiResponse(
                response=DetailSerializer,
                description="No bookmark of the caller's has that id: it was never synced, or "
                "it is not theirs. Nothing changed. A reader that made and deleted a bookmark "
                "while offline may treat this as done.",
            ),
        },
    )
    def delete(self, request, id: str):
        bid = _bookmark_id(id)
        if bid is None:
            return _refused(BAD_BOOKMARK_ID_DELETE.format(value=id))
        with transaction.atomic():
            stored = self._mine(request, bid)
            if stored is None:
                return _refused(NO_SUCH_BOOKMARK, status.HTTP_404_NOT_FOUND)
            if stored.deleted_at is None:
                now = timezone.now()
                stored.deleted_at = now
                stored.updated_at = now
                stored.save(update_fields=["deleted_at", "updated_at"])
            return Response(_bookmark_body(stored))
