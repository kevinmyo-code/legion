"""`/api/books/library*`: the household's book library (map alfred-builds, ticket 06, engine half).

    GET    /api/books/library                 a member: the household's books, newest first
    POST   /api/books/library                 a member: multipart `file` (EPUB only), `title`,
                                              `author`, `licence_note` (required), `source_url`
    DELETE /api/books/library/<id>            a member: take a book out (`undo.py` for a ship)
    GET    /api/books/library/<id>/download   a member: a signed link to the file, 15 minutes
    GET    /api/books/library/<id>/file?t=    the file; the signature is the whole authority

**Legal copies only.** `licence_note` is required here and by a check constraint; the Claude
worker's book-finder skill adds books only from legal free sources (map alfred-builds, "built in")
and ships them through `tools/claude_session/ship.py --kind content` after Kevin's yes.

**EPUB only, checked by bytes**: a ZIP whose `mimetype` entry is `application/epub+zip`. A PDF
goes through `POST /api/books/convert` first. Capped at the converter's 30 MiB (Cloud Run refuses
a request body over 32 MiB).

**Scoped by household** at `household.tenancy.scoped`, the one choke point (ADR 0045); shared
inside it (ADR 0052's default). A recruiter's visit is refused every route here
(`household.recruiter_routes.RECRUITER_DENIED`). Uploading the same file twice is answered 200
with the row already there, nothing new.

**The download link** follows `claude_session.PageView`: `GET .../download` (a member) mints a
`TimestampSigner` token naming one household and one book; `GET .../file?t=` needs no header, so
the phone's downloader and a browser can fetch it, and the link dies after 15 minutes.

Nothing here logs a title, a filename or any of the book's text.
"""

from __future__ import annotations

import hashlib
import io
import zipfile

from django.core import signing
from django.db import IntegrityError, transaction
from django.http import HttpResponse
from django.urls import reverse
from django.utils.dateparse import parse_datetime
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from books.models import LibraryBook
from books.views import MAX_BYTES, MAX_MB
from household.tenancy import household_of, scoped

TAGS = ["books"]
EPUB_TYPE = "application/epub+zip"
LINK_SALT = "books.library.file"
LINK_SECONDS = 15 * 60
_GONE = "That link has expired or is not valid. Ask for a new download link."


class LibraryBookSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    title = serializers.CharField()
    author = serializers.CharField()
    format = serializers.CharField()
    sha256 = serializers.CharField()
    size = serializers.IntegerField(help_text="Bytes.")
    source_url = serializers.CharField(help_text="Where the copy came from.")
    licence_note = serializers.CharField(
        help_text='Why the household may hold it: "public domain (Gutenberg)", "free from the author".'
    )
    added_by = serializers.CharField(allow_null=True, help_text="The member who added it.")
    added_at = serializers.DateTimeField()


class LibraryListSerializer(serializers.Serializer):
    results = LibraryBookSerializer(many=True)


class LibraryUploadSerializer(serializers.Serializer):
    file = serializers.FileField(help_text=f"The EPUB, at most {MAX_MB} MB.")
    title = serializers.CharField(max_length=500)
    author = serializers.CharField(max_length=500, required=False, allow_blank=True, default="")
    licence_note = serializers.CharField(
        max_length=500,
        help_text='Required. Why this copy is legal: "public domain (Project Gutenberg)", '
        '"free from the author".',
    )
    source_url = serializers.URLField(max_length=2000, help_text="The page the copy came from.")


class DownloadLinkSerializer(serializers.Serializer):
    url = serializers.CharField(help_text="Fetch with GET, no header. Valid for `expires_in` seconds.")
    expires_in = serializers.IntegerField()


def _row(book: LibraryBook) -> dict:
    user = book.added_by
    return {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "format": book.format,
        "sha256": book.sha256,
        "size": book.size,
        "source_url": book.source_url,
        "licence_note": book.licence_note,
        "added_by": getattr(user, "email", None) if user else None,
        "added_at": book.added_at,
    }


def _detail(text: str, code: int) -> Response:
    return Response({"detail": text}, status=code)


def is_epub(blob: bytes) -> bool:
    if not blob.startswith(b"PK\x03\x04"):
        return False
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            return z.read("mimetype").strip() == EPUB_TYPE.encode()
    except (zipfile.BadZipFile, KeyError, OSError, ValueError):
        return False


class LibraryListView(APIView):
    parser_classes = [MultiPartParser, FormParser]

    @extend_schema(
        operation_id="api_books_library_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter("since", str, description="ISO 8601; only books added after it."),
        ],
        responses={200: LibraryListSerializer, 400: DetailSerializer},
    )
    def get(self, request):
        rows = scoped(LibraryBook, request).defer("content").select_related("added_by")
        since = request.query_params.get("since")
        if since:
            moment = parse_datetime(since)
            if moment is None:
                return _detail("Nothing was read. since must be an ISO 8601 time.", 400)
            rows = rows.filter(added_at__gt=moment)
        return Response({"results": [_row(b) for b in rows.order_by("-added_at", "-id")]})

    @extend_schema(
        operation_id="api_books_library_create",
        tags=TAGS,
        request={"multipart/form-data": LibraryUploadSerializer},
        responses={
            201: OpenApiResponse(response=LibraryBookSerializer, description="Added."),
            200: OpenApiResponse(
                response=LibraryBookSerializer, description="This file was already in the library."
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Refused, nothing added."),
            413: OpenApiResponse(response=DetailSerializer, description="Over the size cap."),
        },
    )
    def post(self, request):
        household = household_of(request)
        serializer = LibraryUploadSerializer(data=request.data)
        if not serializer.is_valid():
            errors = serializer.errors
            if "licence_note" in errors:
                return _detail(
                    "Nothing was added. A book needs a licence note saying why this copy is legal, "
                    'for example "public domain (Project Gutenberg)".',
                    400,
                )
            return _detail(f"Nothing was added. {errors}", 400)
        data = serializer.validated_data
        upload = data["file"]
        if upload.size > MAX_BYTES:
            return _detail(f"Nothing was added. A book is capped at {MAX_MB} MB.", 413)
        blob = upload.read()
        if not is_epub(blob):
            return _detail(
                "Nothing was added. Only EPUB files go in the library; convert a PDF with "
                "/api/books/convert first.",
                400,
            )
        licence = data["licence_note"].strip()
        if not licence:
            return _detail("Nothing was added. The licence note is empty.", 400)
        sha = hashlib.sha256(blob).hexdigest()
        existing = scoped(LibraryBook, request).defer("content").filter(sha256=sha).first()
        if existing is not None:
            return Response(_row(existing))
        try:
            with transaction.atomic():
                book = LibraryBook.objects.create(
                    household=household,
                    added_by=request.user,
                    title=data["title"].strip(),
                    author=data["author"].strip(),
                    sha256=sha,
                    size=len(blob),
                    source_url=data["source_url"],
                    licence_note=licence,
                    content=blob,
                )
        except IntegrityError:
            book = scoped(LibraryBook, request).defer("content").get(sha256=sha)
            return Response(_row(book))
        return Response(_row(book), status=status.HTTP_201_CREATED)


class LibraryDetailView(APIView):
    @extend_schema(
        operation_id="api_books_library_destroy",
        tags=TAGS,
        responses={204: None, 404: DetailSerializer},
    )
    def delete(self, request, book_id: int):
        deleted, _ = scoped(LibraryBook, request).filter(pk=book_id).delete()
        if not deleted:
            return _detail("No book with that id in this household's library.", 404)
        return Response(status=status.HTTP_204_NO_CONTENT)


class LibraryDownloadView(APIView):
    @extend_schema(
        operation_id="api_books_library_download",
        tags=TAGS,
        responses={200: DownloadLinkSerializer, 404: DetailSerializer},
    )
    def get(self, request, book_id: int):
        book = scoped(LibraryBook, request).defer("content").filter(pk=book_id).first()
        if book is None:
            return _detail("No book with that id in this household's library.", 404)
        token = signing.TimestampSigner(salt=LINK_SALT).sign(f"{book.household_id}:{book.id}")
        path = reverse("books-library-file", kwargs={"book_id": book.id})
        return Response(
            {"url": request.build_absolute_uri(f"{path}?t={token}"), "expires_in": LINK_SECONDS}
        )


class LibraryFileView(APIView):
    """No token header: the signature in `t` is the whole authority. It names one household and
    one book and lives LINK_SECONDS; it is minted only by `LibraryDownloadView`, for a member."""

    authentication_classes: list = []
    permission_classes = [AllowAny]

    @extend_schema(
        operation_id="api_books_library_file",
        tags=TAGS,
        auth=[],
        parameters=[OpenApiParameter("t", str, required=True, description="The signed link token.")],
        responses={
            (200, EPUB_TYPE): OpenApiResponse(response=OpenApiTypes.BINARY, description="The EPUB."),
            404: DetailSerializer,
        },
    )
    def get(self, request, book_id: int):
        try:
            value = signing.TimestampSigner(salt=LINK_SALT).unsign(
                request.query_params.get("t", ""), max_age=LINK_SECONDS
            )
            household_id, signed_id = value.split(":", 1)
            signed_id = int(signed_id)
        except (signing.BadSignature, ValueError):
            return _detail(_GONE, 404)
        if signed_id != book_id:
            return _detail(_GONE, 404)
        # Scoped by the household the signature names; there is no request user.
        book = LibraryBook.objects.filter(household_id=household_id, pk=book_id).first()
        if book is None:
            return _detail(_GONE, 404)
        response = HttpResponse(bytes(book.content), content_type=EPUB_TYPE)
        response["Content-Disposition"] = f'attachment; filename="book-{book.id}.epub"'
        response["X-Content-Type-Options"] = "nosniff"
        response["Cache-Control"] = "private, no-store"
        return response
