"""`/api/vault/items`: the ID vault over REST (ADR 0076, memory-and-vault ticket 05).

| Route | Who | Answers with |
|---|---|---|
| `GET /api/vault/items` | the member, phone or browser | labels and kinds, never a value |
| `POST /api/vault/items` | the member, phone or browser | the label, never the value |
| `PATCH /api/vault/items/<id>` | the member, phone or browser | the label, never the value |
| `DELETE /api/vault/items/<id>` | the member's phone | 204, a hard delete |
| `POST /api/vault/items/<id>/reveal` | the member's phone, after the fingerprint | the value |

**Write-only everywhere but the reveal.** Create and update never answer with the value, so a
browser may enter one (Kevin, 2026-11-05: *"can i enter it on the pc man, but later"*,
memory-and-vault ticket 15, KIV) and never read one back. Reveal and delete need a device
token: a browser session is refused (never on the web).

**Refused on every route:** any token the engine ties to a Claude worker
(`claude_session.tokens.is_claude_token`: never readable by Claude), a recruiter (ADR 0068, and
`household.recruiter_routes` denies the routes too), and every other member: an item is visible
only to the member who keeps it (`visible()`, `owner_user` never null), so another member's item
is a 404.

**The fingerprint is the phone's word, not the server's proof.** Reveal requires the header
`X-Legion-Unlocked: biometric`, which the phone sets only after `BiometricPrompt` succeeds. The
server cannot verify a fingerprint; a member's device token that sends the header gets the value.
The guarantee rests on the phone (ADR 0076 says so in words). What the server adds: a rate limit
per member (`vault_reveal`, 10 an hour by default), an audit row per reveal (label, time, device
name, never the value), and `Cache-Control: no-store`.

**Nothing here logs a value**, and no exception message carries one: a value is read from the
request once, sealed, and dropped. `tests/test_id_vault.py` captures every log record at DEBUG
through create, update and reveal and fails if the value appears.
"""

from __future__ import annotations

from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from claude_session.tokens import is_claude_token
from household.models import DeviceToken, is_recruiter
from household.tenancy import household_of, visible
from id_vault.models import KINDS, LABEL_MAX_CHARS, VALUE_MAX_CHARS, VaultItem, VaultReveal
from ingest import vault as sealer

TAGS = ["vault"]
UNLOCK_HEADER = "X-Legion-Unlocked"
UNLOCK_VALUE = "biometric"

CLAUDE_REFUSAL = (
    "Nothing was read or written. The ID vault is never readable by Claude: this token belongs "
    "to a Claude worker (ADR 0076)."
)
RECRUITER_REFUSAL = (
    "Nothing was read or written. This is a recruiter's demo visit, and the ID vault is not part "
    "of the demo."
)
PHONE_ONLY_REFUSAL = (
    "Nothing was {verb}. The ID vault opens and deletes only on the member's own phone, behind "
    "the fingerprint; it never opens on the web (ADR 0076)."
)
UNLOCK_REFUSAL = (
    "Nothing was revealed. A vault item opens only after the phone's fingerprint prompt "
    f"succeeds, and this request did not say it had ({UNLOCK_HEADER}: {UNLOCK_VALUE})."
)
NOT_FOUND = "Nothing was {verb}. There is no vault item with that id that you keep."
DUPLICATE_LABEL = "Nothing was saved. You already keep a vault item labelled {label!r}."


class VaultRevealThrottle(SimpleRateThrottle):
    """Reveals per member, across all their devices."""

    scope = "vault_reveal"

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": user.pk}


class VaultItemSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    label = serializers.CharField()
    kind = serializers.ChoiceField(choices=KINDS)
    created_at = serializers.DateTimeField()
    updated_at = serializers.DateTimeField()
    last_revealed_at = serializers.DateTimeField(allow_null=True)


class VaultListSerializer(serializers.Serializer):
    results = VaultItemSerializer(many=True)


class VaultCreateSerializer(serializers.Serializer):
    label = serializers.CharField(max_length=LABEL_MAX_CHARS)
    kind = serializers.ChoiceField(choices=KINDS)
    value = serializers.CharField(
        max_length=VALUE_MAX_CHARS,
        write_only=True,
        trim_whitespace=True,
        help_text="Sealed before it is stored. Never returned by this route.",
    )


class VaultUpdateSerializer(serializers.Serializer):
    label = serializers.CharField(max_length=LABEL_MAX_CHARS, required=False)
    kind = serializers.ChoiceField(choices=KINDS, required=False)
    value = serializers.CharField(
        max_length=VALUE_MAX_CHARS,
        write_only=True,
        required=False,
        trim_whitespace=True,
        help_text="Sealed before it is stored. Never returned by this route.",
    )


class VaultRevealSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    label = serializers.CharField()
    kind = serializers.ChoiceField(choices=KINDS)
    value = serializers.CharField(help_text="Show it on screen only. Never log, speak or send it.")


def _out(item: VaultItem) -> dict:
    from rest_framework.fields import DateTimeField

    stamp = DateTimeField().to_representation
    return {
        "id": str(item.id),
        "label": item.label,
        "kind": item.kind,
        "created_at": stamp(item.created_at),
        "updated_at": stamp(item.updated_at),
        "last_revealed_at": stamp(item.last_revealed_at) if item.last_revealed_at else None,
    }


def _no(detail: str, code=status.HTTP_403_FORBIDDEN) -> Response:
    return Response({"detail": detail}, status=code)


def _door(request, *, phone_only_verb: str | None = None) -> Response | None:
    """The fences every route runs first, in words. `phone_only_verb` set means the route also
    needs a device token (reveal, delete)."""
    if is_recruiter(getattr(request, "user", None)):
        return _no(RECRUITER_REFUSAL)
    if is_claude_token(request):
        return _no(CLAUDE_REFUSAL)
    if phone_only_verb and not isinstance(getattr(request, "auth", None), DeviceToken):
        return _no(PHONE_ONLY_REFUSAL.format(verb=phone_only_verb))
    return None


def _bad(serializer) -> Response:
    # Field names and DRF's messages only. DRF's messages for these fields never quote the input.
    fields = ", ".join(sorted(serializer.errors))
    return _no(
        f"Nothing was saved. These fields were missing or not acceptable: {fields}. A label is "
        f"at most {LABEL_MAX_CHARS} characters, a value at most {VALUE_MAX_CHARS}, and kind is one "
        f"of {', '.join(KINDS)}.",
        status.HTTP_400_BAD_REQUEST,
    )


def _seal(value: str) -> bytes | Response:
    try:
        return sealer.seal({"value": value})
    except sealer.VaultUnavailable:
        return _no(
            "Nothing was saved. The engine's vault key (LEGION_VAULT_KEY) is not set or not "
            "usable, so nothing can be sealed. The operator sets it in the engine's environment.",
            status.HTTP_503_SERVICE_UNAVAILABLE,
        )


def _mine(request):
    return visible(VaultItem, request)


class VaultListCreateView(APIView):
    @extend_schema(
        operation_id="api_vault_items_list",
        tags=TAGS,
        responses={200: VaultListSerializer, 403: DetailSerializer},
        description="The caller's own vault items: labels and kinds. Never a value.",
    )
    def get(self, request):
        refused = _door(request)
        if refused:
            return refused
        rows = _mine(request).order_by("label")
        return Response({"results": [_out(row) for row in rows]})

    @extend_schema(
        operation_id="api_vault_items_create",
        tags=TAGS,
        request=VaultCreateSerializer,
        responses={201: VaultItemSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description="Seals and stores a value. Answers with the label only, never the value.",
    )
    def post(self, request):
        refused = _door(request)
        if refused:
            return refused
        serializer = VaultCreateSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        data = serializer.validated_data
        sealed = _seal(data["value"])
        if isinstance(sealed, Response):
            return sealed
        label = " ".join(data["label"].split())
        try:
            with transaction.atomic():
                item = VaultItem.objects.create(
                    household=household_of(request),
                    owner_user=request.user,
                    label=label,
                    kind=data["kind"],
                    ciphertext=sealed,
                )
        except IntegrityError:
            return _no(DUPLICATE_LABEL.format(label=label), status.HTTP_409_CONFLICT)
        item.refresh_from_db()
        return Response(_out(item), status=status.HTTP_201_CREATED)


class VaultDetailView(APIView):
    @extend_schema(
        operation_id="api_vault_items_partial_update",
        tags=TAGS,
        request=VaultUpdateSerializer,
        responses={
            200: VaultItemSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description="Changes the label, kind or value. Answers with the label only.",
    )
    def patch(self, request, item_id):
        refused = _door(request)
        if refused:
            return refused
        item = _mine(request).filter(pk=item_id).first()
        if item is None:
            return _no(NOT_FOUND.format(verb="changed"), status.HTTP_404_NOT_FOUND)
        serializer = VaultUpdateSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        data = serializer.validated_data
        fields = []
        if "value" in data:
            sealed = _seal(data["value"])
            if isinstance(sealed, Response):
                return sealed
            item.ciphertext = sealed
            fields.append("ciphertext")
        if "label" in data:
            item.label = " ".join(data["label"].split())
            fields.append("label")
        if "kind" in data:
            item.kind = data["kind"]
            fields.append("kind")
        if fields:
            item.updated_at = timezone.now()
            try:
                with transaction.atomic():
                    item.save(update_fields=[*fields, "updated_at"])
            except IntegrityError:
                return _no(DUPLICATE_LABEL.format(label=item.label), status.HTTP_409_CONFLICT)
        return Response(_out(item))

    @extend_schema(
        operation_id="api_vault_items_destroy",
        tags=TAGS,
        responses={
            204: OpenApiResponse(description="Deleted for good."),
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description="A hard delete, from the member's own phone only.",
    )
    def delete(self, request, item_id):
        refused = _door(request, phone_only_verb="deleted")
        if refused:
            return refused
        deleted, _ = _mine(request).filter(pk=item_id).delete()
        if not deleted:
            return _no(NOT_FOUND.format(verb="deleted"), status.HTTP_404_NOT_FOUND)
        return Response(status=status.HTTP_204_NO_CONTENT)


class VaultRevealView(APIView):
    throttle_classes = [VaultRevealThrottle]

    @extend_schema(
        operation_id="api_vault_items_reveal",
        tags=TAGS,
        request=None,
        parameters=[
            OpenApiParameter(
                UNLOCK_HEADER,
                str,
                location=OpenApiParameter.HEADER,
                required=True,
                enum=[UNLOCK_VALUE],
                description="Set by the phone only after BiometricPrompt succeeds.",
            )
        ],
        responses={
            200: VaultRevealSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
            429: DetailSerializer,
        },
        description=(
            "The value, for the member's own phone, after the fingerprint. Audited (label and "
            "time, never the value), rate limited, never cached. The server cannot verify the "
            "fingerprint: the header is the phone's word."
        ),
    )
    def post(self, request, item_id):
        refused = _door(request, phone_only_verb="revealed")
        if refused:
            return refused
        if request.headers.get(UNLOCK_HEADER, "").strip().lower() != UNLOCK_VALUE:
            return _no(UNLOCK_REFUSAL)
        item = _mine(request).filter(pk=item_id).first()
        if item is None:
            return _no(NOT_FOUND.format(verb="revealed"), status.HTTP_404_NOT_FOUND)
        try:
            secret = sealer.unseal(item.ciphertext)
        except sealer.VaultUnavailable:
            return _no(
                "Nothing was revealed. The engine's vault key (LEGION_VAULT_KEY) is missing or has "
                "changed since this item was saved, so it cannot be opened.",
                status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        now = timezone.now()
        token = request.auth
        with transaction.atomic():
            VaultReveal.objects.create(
                household=item.household,
                owner_user=request.user,
                item=item,
                label=item.label,
                device_name=getattr(token, "name", ""),
            )
            VaultItem.objects.filter(pk=item.pk).update(last_revealed_at=now)
        response = Response(
            {
                "id": str(item.id),
                "label": item.label,
                "kind": item.kind,
                "value": secret.get("value", "") if isinstance(secret, dict) else "",
            }
        )
        response["Cache-Control"] = "no-store"
        return response
