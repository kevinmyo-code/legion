"""`POST /api/books/convert`: a PDF in, its text as an EPUB out (books ticket 04,
rulings in ticket 03).

**Pass-through, and nothing is kept.** Ticket 03 ruling 1 amends ADR 0061's
"everything stays on the phone" for exactly this: the PDF lives in a
`tempfile.TemporaryDirectory()` for the length of the request and is deleted
with it on every path, exceptions included. No model, no migration, no
tenancy table, no file under MEDIA_ROOT. The response says `no-store`.

**Logged: metadata only.** Bytes in, pages, characters detected, seconds and
the outcome code, on one line. Never the PDF's title, a filename (the request
carries none and none is read) or any of its text. `logger.warning` rather
than `info` for the reason `brief/news.py`'s outcome line gives: the project
configures no LOGGING, so Python's last-resort handler is what reaches Cloud
Run's log, and it prints WARNING and above.

**The body is raw `application/pdf`, read from the stream, never from
`request.body`.** Django refuses `request.body` over
`DATA_UPLOAD_MAX_MEMORY_SIZE` (2.5 MB) - `devicelogs/views.py` dodged that with
multipart, and this endpoint's contract is a raw body. DRF's `request.stream`
is the `HttpRequest` itself while nothing has read it yet, and
`HttpRequest.read()` has no size check, so the body is copied to disk in 1 MiB
chunks. The cap is checked from `Content-Length` before a byte is read, and
the WSGI stream is bounded by that same `Content-Length`, so a body cannot be
longer than the length that passed the check.

**The converter is a child process** (`books/convert.py`), killed at 50 s. The
service's request timeout is 60 s and gunicorn's `--timeout 60` kills a worker
that runs over with no response at all, so the 50 s bound is what turns an
overlong conversion into a 502 in words instead of a dropped connection.
That holds only while the upload itself is quick: gunicorn's 60 s counts the
whole request, upload and spool included, so a slow 30 MB upload leaves the
converter less than 50 s. Raising Cloud Run's timeout without raising
gunicorn's `--timeout` in `entrypoint.sh` with it changes nothing.

**Who may call it**: the project defaults, like every device endpoint - a
device token or a browser session, household membership required. A
`read`-scoped token is refused by `IsHouseholdMember` as on any POST, even
though nothing is written: it is the same door, and widening it is a ruling.

**`Accept` is ignored.** The phone may ask for `application/epub+zip` only;
DRF's content negotiation would answer that with 406 before the view runs,
because no renderer produces EPUB. Every response here is a plain Django
response whose type the contract fixes.
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from django.http import HttpResponse, JsonResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.negotiation import BaseContentNegotiation
from rest_framework.views import APIView

logger = logging.getLogger(__name__)

# 30 MiB, binary megabytes, and the phone (ticket 05) uses the same number and
# unit. Cloud Run's front end refuses an HTTP/1 request body over 32 MiB, so
# this leaves 2 MiB of headroom and the 413 below is always the one a phone
# sees, in words, rather than Google's own error page.
MAX_BYTES = 30 * 1024 * 1024
MAX_MB = MAX_BYTES // (1024 * 1024)
CONVERT_TIMEOUT_SECONDS = 50
CONVERTER = Path(__file__).with_name("convert.py")
_CHUNK = 1024 * 1024

EPUB_TYPE = "application/epub+zip"
PAGES_HEADER = "X-Legion-Pages"

SCANNED = "This PDF is scanned pages with no text, so its text cannot be refit."
EMPTY = "The request carried no PDF: its body was empty, so nothing was converted."
CUT_SHORT = "The PDF upload ended before all of it arrived, so nothing was converted."
TIMED_OUT = (
    f"Converting this PDF took longer than {CONVERT_TIMEOUT_SECONDS} seconds and was "
    "stopped. Nothing was kept."
)
FAILED = "The converter failed on this PDF, so no EPUB was made. Nothing was kept."
NO_BODY_TEXT = (
    "Every line of text in this PDF was a page header, footer or page number, so there "
    "was nothing to refit. Nothing was kept."
)


def too_big_message(size_bytes: int) -> str:
    """Ticket 04's sentence. The phone writes the same one without uploading,
    so the two must not drift: same constant, same unit (MiB, one decimal)."""
    size_mb = size_bytes / (1024 * 1024)
    return f"This PDF is {size_mb:.1f} MB; the most the server takes is {MAX_MB} MB."


CONVERT_CODES = ("scanned", "unreadable", "too_big", "failed")


class ConvertErrorSerializer(serializers.Serializer):
    code = serializers.ChoiceField(
        choices=list(CONVERT_CODES),
        help_text="`scanned`, `unreadable` and `too_big` are final: sending the same PDF again "
        "gets the same answer. `failed` may succeed on a retry.",
    )
    message = serializers.CharField(
        help_text="A sentence for a person, saying what happened and that nothing was kept. "
        "Show it as is."
    )


class IgnoreAccept(BaseContentNegotiation):
    """Picks the first parser and renderer whatever the request asks for. See the
    module doc: without this, `Accept: application/epub+zip` is a 406."""

    def select_parser(self, request, parsers):
        return parsers[0] if parsers else None

    def select_renderer(self, request, renderers, format_suffix=None):
        return renderers[0], renderers[0].media_type


def _error(code: str, message: str, http_status: int) -> JsonResponse:
    response = JsonResponse({"code": code, "message": message}, status=http_status)
    response["Cache-Control"] = "no-store"
    return response


def _content_length(request) -> int:
    try:
        return max(int(request.META.get("CONTENT_LENGTH") or 0), 0)
    except (TypeError, ValueError):
        return 0


def _child_env() -> dict[str, str]:
    """The converter gets no secrets: only what a Python process needs to start
    (SYSTEMROOT on Windows, where tests run) and UTF-8 stdout."""
    keep = ("PATH", "SYSTEMROOT", "TEMP", "TMP", "HOME", "LANG")
    env = {key: os.environ[key] for key in keep if key in os.environ}
    env["PYTHONIOENCODING"] = "utf-8"
    return env


def _spool(request, path: Path, expected: int) -> bool:
    """Copy the body to `path`. False when fewer bytes arrived than promised."""
    written = 0
    with path.open("wb") as handle:
        stream = request.stream
        while stream is not None and written < expected:
            chunk = stream.read(min(_CHUNK, expected - written))
            if not chunk:
                break
            handle.write(chunk)
            written += len(chunk)
    return written == expected


def run_converter(in_path: Path, out_path: Path, workdir: Path) -> subprocess.CompletedProcess:
    """The one place the child process starts. Tests replace this."""
    return subprocess.run(
        [sys.executable, str(CONVERTER), str(in_path), str(out_path)],
        cwd=workdir,
        env=_child_env(),
        capture_output=True,
        timeout=CONVERT_TIMEOUT_SECONDS,
        check=False,
    )


def _outcome(completed: subprocess.CompletedProcess) -> dict:
    """The child's one JSON line, or a `failed` outcome when there is none."""
    stdout = completed.stdout or b""
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    lines = [line for line in stdout.splitlines() if line.strip()]
    if not lines:
        return {"outcome": "failed", "error": f"exit {completed.returncode}, no outcome"}
    try:
        outcome = json.loads(lines[-1])
    except ValueError:
        return {"outcome": "failed", "error": f"exit {completed.returncode}, unparsable outcome"}
    if not isinstance(outcome, dict) or "outcome" not in outcome:
        return {"outcome": "failed", "error": f"exit {completed.returncode}, unparsable outcome"}
    return outcome


class BookConvertView(APIView):
    content_negotiation_class = IgnoreAccept

    @extend_schema(
        operation_id="api_books_convert_create",
        tags=["books"],
        summary="Convert a PDF to a reflowable EPUB. Nothing is kept on the server.",
        description=(
            "The body is the PDF itself (`Content-Type: application/pdf`), not multipart, and "
            f"must declare its `Content-Length`; over {MAX_MB} MiB is refused before it is read. "
            "The PDF is converted in a temporary directory deleted before the response is sent. "
            "Text only: figures and tables are not carried over. A PDF whose median page has "
            "fewer than 200 non-whitespace characters is a scan and is refused as `scanned`."
        ),
        request={"application/pdf": OpenApiTypes.BINARY},
        parameters=[
            OpenApiParameter(
                name=PAGES_HEADER,
                type=OpenApiTypes.INT,
                location=OpenApiParameter.HEADER,
                response=[200],
                description="The PDF's page count.",
            )
        ],
        responses={
            (200, EPUB_TYPE): OpenApiResponse(
                response=OpenApiTypes.BINARY, description="The EPUB, in the response body."
            ),
            (413, "application/json"): OpenApiResponse(
                response=ConvertErrorSerializer,
                description=f"`too_big`: over {MAX_MB} MiB. Nothing was read.",
            ),
            (422, "application/json"): OpenApiResponse(
                response=ConvertErrorSerializer,
                description="`scanned`: no text layer. `unreadable`: not a PDF, damaged, "
                "password-protected, empty, all margins, or the upload ended early; the message "
                "says which.",
            ),
            (502, "application/json"): OpenApiResponse(
                response=ConvertErrorSerializer,
                description=f"`failed`: the converter failed or ran past "
                f"{CONVERT_TIMEOUT_SECONDS} s.",
            ),
        },
    )
    def post(self, request):
        started = time.monotonic()
        size = _content_length(request)
        meta = {"pages": None, "chars": None}
        code = "failed"
        try:
            if size > MAX_BYTES:
                code = "too_big"
                return _error(code, too_big_message(size), 413)
            if size == 0:
                code = "unreadable"
                return _error(code, EMPTY, 422)
            response, code = self._convert(request, size, meta)
            return response
        finally:
            logger.warning(
                "Book conversion outcome=%s bytes_in=%d pages=%s chars=%s seconds=%.2f",
                code,
                size,
                meta["pages"],
                meta["chars"],
                time.monotonic() - started,
            )

    def _convert(self, request, size: int, meta: dict) -> tuple[HttpResponse, str]:
        with tempfile.TemporaryDirectory(prefix="legion-book-") as tmp:
            workdir = Path(tmp)
            in_path = workdir / "in.pdf"
            out_path = workdir / "out.epub"
            if not _spool(request, in_path, size):
                return _error("unreadable", CUT_SHORT, 422), "unreadable"
            try:
                completed = run_converter(in_path, out_path, workdir)
            except subprocess.TimeoutExpired:
                return _error("failed", TIMED_OUT, 502), "failed"
            outcome = _outcome(completed)
            meta["pages"] = outcome.get("pages")
            meta["chars"] = outcome.get("chars")
            kind = outcome.get("outcome")
            if kind == "ok" and out_path.is_file():
                response = HttpResponse(out_path.read_bytes(), content_type=EPUB_TYPE)
                response[PAGES_HEADER] = str(outcome.get("pages", ""))
                response["Cache-Control"] = "no-store"
                return response, "ok"
            if kind == "scanned":
                return _error("scanned", SCANNED, 422), "scanned"
            if kind == "unreadable":
                message = str(outcome.get("message") or "This PDF could not be read.")
                return _error("unreadable", message, 422), "unreadable"
            if outcome.get("error") == "NoBodyText":
                # Deterministic, so `unreadable` (final) and not `failed`, which
                # the phone retries: the same PDF would only fail the same way.
                return _error("unreadable", NO_BODY_TEXT, 422), "unreadable"
            return _error("failed", FAILED, 502), "failed"
