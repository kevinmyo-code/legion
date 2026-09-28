"""`drive_statements`: raw bank statements from a Drive folder, through the gate
(backend-etl ticket 06, map ruling 3, CLAUDE.md section 4 rule 1 as amended
2026-09-27).

Kevin drops a bank's own PDF or CSV into one Drive folder. Every six hours this
lists the files directly in that folder, and for each one it has not seen:

1. **Route by type.** A PDF or a CSV is read; a Google-native doc, any other
   type, and anything over `MAX_STATEMENT_BYTES` is skipped with a note.
2. **Deterministic first.** Every registered parser (`PARSERS`, see
   `register_parser`) that takes the file's kind is offered the bytes, before
   anything else. The first that recognises the layout returns a `Parsed`. The
   BofA checking and card PDF parsers (`ingest/parsers/bofa.py`, ticket 10)
   register from `IngestConfig.ready`; the BofA activity CSV reader is ticket
   09 and plugs in the same way.
3. **A CSV is NEVER sent to Gemini** (Kevin, 2026-09-28). BofA's mid-month
   activity CSVs print no anchor, so they belong to section 4 rule 7's
   provisional path, whose first condition is deterministic extraction. A CSV no
   parser recognises is quarantined with `CSV_NOT_RECOGNISED`. A parser may
   return a PROVISIONAL result; it goes to the registered provisional writer
   (`register_provisional_writer`, ticket 09), never to the gate.
4. **Otherwise, a PDF goes to Gemini on the household's own key**
   (`LEGION_GEMINI_KEY`). The model fills LEGION's statement format
   (`docs/ledger-csv-import-format.md`: account last four and nickname,
   currency, the three printed anchors, and every line), as JSON under a
   response schema whose fields are that format's own. **All three anchors are
   required**: a statement read without a printed total, an opening balance or
   a closing balance is quarantined with the missing ones named. Rows are
   `LLM_RECONCILED`.
5. **The gate is `ingest.views.commit_statement`**, the same function
   `POST /api/ingest/statement` calls, in-process. Quarantine on any mismatch,
   nothing partial written, anchors persisted on `public.statements`.

**Idempotency is the content hash**, `ingested_files (household, content_sha256)`.
A file already committed or quarantined is never sent to Gemini again. The
Drive `modifiedTime` watermark only narrows what is listed; a file seen twice
is still a no-op because of the hash, not because of the watermark.

**Refusals, in words.** A quarantined file is recorded on `ingested_files` with
its reason, and `/api/freshness` says so for `drive_statements`. No key records
`skipped` with a sentence. A refused Drive login records `needs_login`.

**Never logged:** the document's content and the key. The key goes in a header,
not the URL, so no exception message or proxy log can carry it, and every
message a run records is `scrub()`bed by `run_job`.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import json
import os
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

from django.db import DatabaseError, DataError, IntegrityError, transaction

from ingest import gate, vault
from ingest.drive import (
    _OPENER,
    DriveClient,
    DriveError,
    DriveRefused,
    Response,
    Transport,
    _quote,
    urllib_transport,
)
from ingest.models import IngestRun, SessionSource, Source

GEMINI_KEY_ENV = "LEGION_GEMINI_KEY"
FOLDER_CONFIG_KEY = "statements_folder_id"

# The cheap one-shot model the phone's sub-agents use (`ai/SubAgent.kt`
# DEFAULT_MODEL, also `GeminiKeyValidator`), on the same v1beta endpoint.
GEMINI_MODEL = "gemini-3.5-flash-lite"
GEMINI_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
)
# A multi-page PDF transcription is slower than the phone's 30s text calls.
GEMINI_TIMEOUT_SECONDS = 180

# Ticket 06: anything larger is refused before it is downloaded. The Cloud Run
# job has 512Mi; a bank statement is usually well under 1 MB.
MAX_STATEMENT_BYTES = 20 * 1024 * 1024
# Gemini refuses an inline request over 20 MB, and base64 grows the bytes by a
# third, so a PDF over this cannot be sent inline at all.
GEMINI_INLINE_MAX_BYTES = 14 * 1024 * 1024

PDF_MIME = "application/pdf"
CSV_MIMES = frozenset({"text/csv", "text/comma-separated-values", "application/csv"})
# Mime types Drive gives a `.csv` depending on the uploader's machine.
CSV_BY_NAME_MIMES = frozenset(
    {"text/plain", "application/vnd.ms-excel", "application/octet-stream"}
)
GOOGLE_NATIVE_PREFIX = "application/vnd.google-apps."

PDF = "pdf"
CSV = "csv"

# `ingested_files.source_file_id` for a file this job read: says where it came
# from, and is how freshness finds this job's quarantines.
SOURCE_FILE_PREFIX = "drive:"

NO_KEY_SENTENCE = (
    f"{GEMINI_KEY_ENV} is not set on the server, so a statement no parser recognises "
    f"cannot be read. Nothing was sent to Gemini and nothing was written."
)
CSV_NOT_RECOGNISED = (
    "CSV not recognised: no deterministic reader for this layout, and a CSV is never "
    "sent to Gemini"
)


# =============================================================================
# The parser seam (tickets 09 and 10 plug in here)
# =============================================================================


@dataclass(frozen=True)
class Parsed:
    """What a deterministic parser read from one file.

    `payload` is everything `commit_statement` reads except `content_sha256`
    and the file facts (the pipeline adds those): the lines, the anchors the
    document prints, the account. `provenance` defaults to `DETERMINISTIC`.

    `provisional=True` says the document prints NO anchor (a BofA mid-month
    activity CSV): the payload then goes to the registered provisional writer,
    section 4 rule 7, and never to the gate.
    """

    payload: dict[str, Any]
    provisional: bool = False


class StatementParser(Protocol):
    """A deterministic reader of one bank layout.

    `kinds` names the file kinds it takes (`{"pdf"}`, `{"csv"}`). `parse`
    returns a `Parsed` when it recognises the layout and None when it does not.
    It must never guess: a line it does not recognise inside a section it does
    is a quarantine, not a skip (section 4 rule 6), so it raises
    `ParserRefused` with the reason."""

    name: str
    kinds: frozenset[str]

    def parse(self, content: bytes, *, file_name: str) -> Parsed | None: ...


class ParserRefused(Exception):
    """A parser recognised the layout and refused the document. The message is
    the quarantine reason."""


# (payload with file facts, household) -> the writer's CommitResult. Ticket 09.
ProvisionalWriter = Callable[[dict, object], Any]

PARSERS: list[StatementParser] = []
_PROVISIONAL_WRITER: list[ProvisionalWriter] = []


def register_parser(parser: StatementParser) -> StatementParser:
    """Add a deterministic parser. Tried in registration order, before Gemini."""
    PARSERS.append(parser)
    return parser


def register_provisional_writer(writer: ProvisionalWriter) -> ProvisionalWriter:
    """Install the section 4 rule 7 writer (ticket 09). One only."""
    _PROVISIONAL_WRITER[:] = [writer]
    return writer


def provisional_writer() -> ProvisionalWriter | None:
    return _PROVISIONAL_WRITER[0] if _PROVISIONAL_WRITER else None


# =============================================================================
# Gemini
# =============================================================================


class GeminiUnavailable(Exception):
    """Gemini could not answer right now (timeout, 429, 5xx). The file is left
    for the next run; nothing is recorded against it."""


class GeminiKeyRefused(Exception):
    """Gemini refused the key (401/403). Every file would fail the same way, so
    the run fails and says so."""


class GeminiRefusedDocument(Exception):
    """Gemini answered about THIS document with something that is not a
    statement (a 400, a blocked answer, JSON that does not parse). The message
    is the quarantine reason, and never contains the document."""


def gemini_transport(
    method: str, url: str, headers: dict[str, str], body: bytes | None
) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=GEMINI_TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


_CENTS = {"type": "integer"}
_NULLABLE_CENTS = {"type": "integer", "nullable": True}
_NULLABLE_DATE = {"type": "string", "nullable": True}

# LEGION's statement format (`docs/ledger-csv-import-format.md`), field for
# field, as the JSON `POST /api/ingest/statement` takes. The anchors are
# nullable ONLY so the model can say "not printed" instead of inventing one;
# a null anchor is a quarantine here, never a zero. `period_start`/`period_end`
# are the endpoint's own optional fields.
RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "account_last4": {"type": "string"},
        "account_nickname": {"type": "string"},
        "currency": {"type": "string", "enum": ["USD", "SGD"]},
        "stated_total_cents": _NULLABLE_CENTS,
        "opening_balance_cents": _NULLABLE_CENTS,
        "closing_balance_cents": _NULLABLE_CENTS,
        "period_start": _NULLABLE_DATE,
        "period_end": _NULLABLE_DATE,
        "lines": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "txn_date": {"type": "string"},
                    "description": {"type": "string"},
                    "amount_cents": _CENTS,
                },
                "required": ["txn_date", "description", "amount_cents"],
            },
        },
    },
    "required": [
        "account_last4",
        "account_nickname",
        "currency",
        "stated_total_cents",
        "opening_balance_cents",
        "closing_balance_cents",
        "lines",
    ],
}

PROMPT = """Read the attached bank or card statement and fill LEGION's statement format \
as JSON. Transcribe; never estimate, infer, or fill a gap. Your answer is checked against \
the statement's own printed figures, and a statement that does not tie out is refused.

Every *_cents field is a plain signed integer number of CENTS: $1,234.56 is 123456. \
Never a decimal, never a string.

Signs are from the ACCOUNT HOLDER's side:
- amount_cents is negative for money leaving the account (a withdrawal, a purchase, a \
fee, interest charged) and positive for money entering it (a deposit, a refund, a \
payment made TO a card).
- A bank account's balance is what the holder has. A card's balance is what the holder \
OWES, so it is NEGATIVE: a card statement's new balance of $500.00 owed is -50000.
- So closing_balance_cents - opening_balance_cents equals the sum of every amount_cents.

Fields:
- account_last4: the last four digits of the account or card number, exactly four digits.
- account_nickname: the bank's name and the account's type, e.g. "BofA checking". No commas.
- currency: USD or SGD.
- stated_total_cents: the statement's own PRINTED total or net movement for the period, \
signed as above. null if the statement prints no such single figure. Never add the lines \
up yourself.
- opening_balance_cents: the balance PRINTED at the start of the period (previous balance, \
beginning balance). null if not printed.
- closing_balance_cents: the balance PRINTED at the end of the period (new balance, ending \
balance). null if not printed.
- period_start, period_end: the statement period as YYYY-MM-DD, or null if not printed.
- lines: EVERY transaction in the period, in the order printed: txn_date as YYYY-MM-DD, the \
description as printed with any comma removed, and the signed amount_cents. Include fees \
and interest charged as lines. Leave out nothing and add nothing.

Use only numbers actually printed on the statement. Do not compute or guess a total, an \
opening balance or a closing balance that is not printed: say null."""


def gemini_request_body(content: bytes) -> bytes:
    """The generateContent body for one PDF, sent as inline data."""
    encoded = base64.b64encode(content).decode("ascii")
    return json.dumps(
        {
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {"text": PROMPT},
                        {"inlineData": {"mimeType": PDF_MIME, "data": encoded}},
                    ],
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": RESPONSE_SCHEMA,
                "temperature": 0,
            },
        }
    ).encode()


def gemini_extract(content: bytes, *, key: str, transport: Transport | None = None) -> dict:
    """The model's reading of one PDF statement, as a dict in LEGION's format.
    Raises `GeminiUnavailable`, `GeminiKeyRefused` or `GeminiRefusedDocument`;
    no message carries the document or the key."""
    send = transport or gemini_transport
    try:
        response = send(
            "POST",
            GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            gemini_request_body(content),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError) as exc:
        raise GeminiUnavailable(f"Gemini did not answer ({type(exc).__name__}).") from None
    status = response.status
    if status in (401, 403):
        raise GeminiKeyRefused(
            f"Gemini refused {GEMINI_KEY_ENV} (HTTP {status}). Check the key in the "
            f"server's secrets. Nothing was written."
        )
    if status == 429 or status >= 500:
        raise GeminiUnavailable(f"Gemini is unavailable right now (HTTP {status}).")
    if status != 200:
        raise GeminiRefusedDocument(
            f"Gemini could not read this document (HTTP {status}{_error_status(response)}). "
            f"Nothing was written."
        )
    try:
        candidate = (response.json().get("candidates") or [None])[0]
        if not candidate:
            raise ValueError("no candidates")
        text = "".join(
            part.get("text", "") for part in candidate.get("content", {}).get("parts", [])
        )
        result = json.loads(text)
    except (ValueError, TypeError, AttributeError, IndexError):
        finish = ""
        try:
            finish = response.json()["candidates"][0].get("finishReason") or ""
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            pass
        suffix = f" (finish reason {finish})" if finish else ""
        raise GeminiRefusedDocument(
            f"Gemini's answer for this document could not be read as a statement{suffix}. "
            f"Nothing was written."
        ) from None
    if not isinstance(result, dict):
        raise GeminiRefusedDocument(
            "Gemini's answer for this document was not a statement object. Nothing was written."
        )
    return result


def _error_status(response: Response) -> str:
    try:
        error = response.json().get("error") or {}
        return f" {error.get('status')}" if isinstance(error, dict) and error.get("status") else ""
    except (ValueError, AttributeError):
        return ""


# =============================================================================
# From the model's reading to the commit payload
# =============================================================================

_ANCHORS = (
    ("stated_total_cents", "printed total"),
    ("opening_balance_cents", "opening balance"),
    ("closing_balance_cents", "closing balance"),
)
_FORMAT_FIELDS = (
    "account_last4",
    "account_nickname",
    "currency",
    "stated_total_cents",
    "opening_balance_cents",
    "closing_balance_cents",
    "period_start",
    "period_end",
)


def missing_anchors(extraction: dict) -> list[str]:
    return [label for key, label in _ANCHORS if extraction.get(key) is None]


def three_anchor_reason(missing: list[str]) -> str:
    return (
        f"The statement as read by Gemini has no {', no '.join(missing)}. A statement read "
        f"by a model must state all three anchors (printed total, opening balance, closing "
        f"balance) to be checked, so nothing was written."
    )


def payload_from_extraction(extraction: dict) -> tuple[dict | None, str | None]:
    """(payload, None) to commit, or (None, quarantine reason). Copies the
    format's fields and nothing else; the gate does the arithmetic."""
    missing = missing_anchors(extraction)
    if missing:
        return None, three_anchor_reason(missing)
    lines = extraction.get("lines")
    if not isinstance(lines, list) or not all(isinstance(line, dict) for line in lines):
        # Section 4 rule 6: a line that is not a line is a hard failure.
        return None, (
            "Gemini returned transactions that were not a list of lines. Nothing was written."
        )
    payload = {key: extraction.get(key) for key in _FORMAT_FIELDS}
    payload["provenance"] = gate.LLM_RECONCILED
    payload["lines"] = [
        {
            "txn_date": line.get("txn_date"),
            "description": line.get("description"),
            "amount_cents": line.get("amount_cents"),
        }
        for line in lines
    ]
    return payload, None


# =============================================================================
# One run
# =============================================================================


@dataclass
class FileResult:
    file_id: str
    name: str
    # committed / already_committed / quarantined / provisional / known /
    # skipped / deferred
    action: str
    detail: str = ""
    inserted: int = 0


@dataclass
class RunReport:
    results: list[FileResult] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class _Commit:
    """Route a file to the gate (or, provisional, to the rule 7 writer)."""

    payload: dict
    provisional: bool = False


@dataclass(frozen=True)
class _Quarantine:
    reason: str


class _NeedsKey(Exception):
    """Only Gemini could read this PDF, and there is no key."""


def gemini_key() -> str | None:
    key = os.environ.get(GEMINI_KEY_ENV, "").strip()
    return key or None


def folder_id_of(credential) -> str | None:
    value = (credential.config or {}).get(FOLDER_CONFIG_KEY) if credential else None
    return value.strip() if isinstance(value, str) and value.strip() else None


def is_configured(household) -> bool:
    """A Drive login is stored AND it names a statements folder."""
    return folder_id_of(vault.credential_for(household, SessionSource.DRIVE)) is not None


def kind_of(item: dict) -> str | None:
    """'pdf', 'csv', or None for anything this job does not read."""
    mime = item.get("mimeType") or ""
    name = (item.get("name") or "").lower()
    if mime == PDF_MIME:
        return PDF
    if mime in CSV_MIMES or (mime in CSV_BY_NAME_MIMES and name.endswith(".csv")):
        return CSV
    return None


def previous_watermark(household) -> str | None:
    run = (
        IngestRun.objects.filter(
            household=household, source=Source.DRIVE_STATEMENTS, watermark__isnull=False
        )
        .order_by("-started_at")
        .first()
    )
    return run.watermark if run else None


def list_query(folder_id: str, watermark: str | None) -> str:
    """Files directly in the folder (`in parents` is one level only), not
    trashed, and (with a watermark) new or changed since. `createdTime` too,
    because an upload may keep the file's older local modified time, and such a
    file would otherwise never be seen."""
    query = f"'{_quote(folder_id)}' in parents and trashed = false"
    if watermark:
        mark = _quote(watermark)
        query += f" and (modifiedTime > '{mark}' or createdTime > '{mark}')"
    return query


def _known(household, sha: str):
    from legacy.enums import IngestState
    from legacy.models.ingest import IngestedFile

    return (
        IngestedFile.objects.filter(
            household=household,
            content_sha256=sha,
            state__in=[IngestState.INGESTED, IngestState.QUARANTINED],
        )
        .only("state")
        .first()
    )


def _record_quarantine(household, file_facts: dict, sha: str, reason: str) -> None:
    """The file row, and only the file row, through the endpoint's own writer."""
    from ingest.views import _upsert_file
    from legacy.enums import IngestState

    with transaction.atomic():
        _upsert_file(file_facts, sha, IngestState.QUARANTINED, reason, household)


def route(
    content: bytes,
    *,
    kind: str,
    name: str,
    key: str | None,
    gemini: Callable[..., dict],
) -> _Commit | _Quarantine:
    """Parsers first, for both kinds. Then a CSV stops, and a PDF goes to
    Gemini. Raises `_NeedsKey` when only Gemini could read it and there is no
    key; Gemini's own exceptions other than a refused document propagate."""
    for parser in PARSERS:
        if kind not in parser.kinds:
            continue
        try:
            parsed = parser.parse(content, file_name=name)
        except ParserRefused as exc:
            return _Quarantine(f"{parser.name}: {exc}")
        if parsed is not None:
            payload = {"provenance": gate.DETERMINISTIC, **parsed.payload}
            if parsed.provisional:
                payload["provenance"] = gate.UNRECONCILED
            return _Commit(payload, provisional=parsed.provisional)

    if kind == CSV:
        return _Quarantine(f"{CSV_NOT_RECOGNISED}. Nothing was written.")

    if len(content) > GEMINI_INLINE_MAX_BYTES:
        return _Quarantine(
            f"No parser recognises this PDF, and at {len(content)} bytes it is over the "
            f"{GEMINI_INLINE_MAX_BYTES}-byte limit for sending a document to Gemini inline. "
            f"Nothing was written."
        )
    if key is None:
        raise _NeedsKey
    try:
        extraction = gemini(content, key=key)
    except GeminiRefusedDocument as exc:
        return _Quarantine(str(exc))
    payload, reason = payload_from_extraction(extraction)
    if payload is None:
        return _Quarantine(reason or "")
    return _Commit(payload)


def _commit(household, routed: _Commit, facts: dict):
    """The gate (`commit_statement`), or the rule 7 writer for a provisional
    result. Returns the CommitResult."""
    from ingest.views import commit_statement

    body = {**routed.payload, **facts}
    lines = body.get("lines")
    if isinstance(lines, list):
        # `ledger_transactions.line_ref` is NOT NULL: the line's place in its
        # document, `<file name>:<n>` counted from 1 in printed order, unless a
        # parser gave its own.
        body["lines"] = [
            {**line, "line_ref": line.get("line_ref") or f"{facts['display_name']}:{n}"}
            if isinstance(line, dict)
            else line
            for n, line in enumerate(lines, start=1)
        ]
    if routed.provisional:
        writer = provisional_writer()
        if writer is None:
            # Unreachable until ticket 09 registers a parser that returns a
            # provisional result, and it registers the writer with it.
            raise RuntimeError(
                "A parser returned a provisional result, but no provisional writer is "
                "registered. Nothing was written."
            )
        return writer(body, household)
    return commit_statement(body, household)


def process(
    run: IngestRun,
    *,
    drive_transport: Transport | None = None,
    gemini: Callable[..., dict] | None = None,
    write: Callable[[str], object] = lambda line: None,
) -> tuple[str | None, RunReport]:
    """One household's run. Returns the outcome for `run_job` and the report."""
    household = run.household
    report = RunReport()
    credential, secret = vault.session_for(household, SessionSource.DRIVE)
    folder = folder_id_of(credential)
    if folder is None:
        run.error = "No statements folder is set. Run manage.py set_statements_folder."
        return "skipped", report
    key = gemini_key()
    gemini = gemini or gemini_extract

    client = DriveClient(secret, transport=drive_transport or urllib_transport)
    watermark = previous_watermark(household)
    try:
        items = client.list_files(list_query(folder, watermark))
    except DriveRefused as exc:
        raise vault.refuse_session(credential, str(exc)) from None
    items.sort(key=lambda item: (item.get("modifiedTime") or "", item.get("id") or ""))

    final_times: list[str] = []
    held_times: list[str] = []
    unavailable: str | None = None

    def done(result: FileResult, stamp: str, *, note: bool = False) -> None:
        report.results.append(result)
        if note:
            report.notes.append(f"{result.name}: {result.action}, {result.detail}.")
        final_times.append(stamp)

    for item in items:
        file_id = str(item.get("id"))
        name = str(item.get("name") or file_id)
        stamp = max(item.get("modifiedTime") or "", item.get("createdTime") or "")
        mime = item.get("mimeType") or ""
        kind = kind_of(item)
        if kind is None:
            why = (
                "a Google Docs/Sheets file has no bank's bytes to read"
                if mime.startswith(GOOGLE_NATIVE_PREFIX)
                else f"{mime or 'an unknown type'} is not a PDF or a CSV"
            )
            done(FileResult(file_id, name, "skipped", why), stamp, note=True)
            continue
        listed_size = item.get("size")
        if listed_size is not None and int(listed_size) > MAX_STATEMENT_BYTES:
            why = f"{int(listed_size)} bytes is over the {MAX_STATEMENT_BYTES}-byte limit"
            done(FileResult(file_id, name, "skipped", why), stamp, note=True)
            continue

        try:
            content = client.download_bytes(file_id, max_bytes=MAX_STATEMENT_BYTES)
        except DriveRefused as exc:
            raise vault.refuse_session(credential, str(exc)) from None
        except DriveError as exc:
            report.results.append(FileResult(file_id, name, "deferred", str(exc)))
            report.notes.append(f"{name}: left for the next run, {exc}")
            held_times.append(stamp)
            continue
        sha = hashlib.sha256(content).hexdigest()
        facts = {
            "content_sha256": sha,
            "source_file_id": f"{SOURCE_FILE_PREFIX}{file_id}",
            "display_name": name,
            "size_bytes": len(content),
        }

        known = _known(household, sha)
        if known is not None:
            done(FileResult(file_id, name, "known", str(known.state)), stamp)
            continue

        try:
            routed = route(content, kind=kind, name=name, key=key, gemini=gemini)
        except _NeedsKey:
            report.results.append(FileResult(file_id, name, "deferred", "no Gemini key"))
            held_times.append(stamp)
            continue
        except GeminiUnavailable as exc:
            report.results.append(FileResult(file_id, name, "deferred", str(exc)))
            report.notes.append(f"{name}: left for the next run, {exc}")
            held_times.append(stamp)
            unavailable = str(exc)
            # Every later file would wait out the same outage. They sort after
            # this one, so the watermark held here lists them again next run.
            break

        if isinstance(routed, _Quarantine):
            _record_quarantine(household, facts, sha, routed.reason)
            done(FileResult(file_id, name, "quarantined", routed.reason), stamp)
            continue

        try:
            result = _commit(household, routed, facts)
        except gate.GateInputError as exc:
            reason = f"The statement could not be checked: {exc} Nothing was written."
            _record_quarantine(household, facts, sha, reason)
            done(FileResult(file_id, name, "quarantined", reason), stamp)
            continue
        except (IntegrityError, DataError) as exc:
            text = str(exc).strip()
            first = text.splitlines()[0] if text else type(exc).__name__
            reason = f"The statement was refused by the database: {first}. Nothing was written."
            _record_quarantine(household, facts, sha, reason)
            done(FileResult(file_id, name, "quarantined", reason), stamp)
            continue
        except DatabaseError:
            held_times.append(stamp)
            raise

        inserted = int(result.body.get("inserted", 0) or 0)
        if result.outcome == gate.COMMITTED:
            run.rows_written += inserted
            action = "provisional" if routed.provisional else "committed"
            done(FileResult(file_id, name, action, inserted=inserted), stamp)
        elif result.outcome == gate.QUARANTINED:
            done(FileResult(file_id, name, "quarantined", result.body.get("reason", "")), stamp)
        else:
            done(FileResult(file_id, name, "already_committed"), stamp)

    run.rows_unchanged = sum(1 for r in report.results if r.action == "known")
    run.watermark = advance_watermark(watermark, final_times, held_times)
    for result in report.results:
        line = f"  {result.action:<17} {result.name}"
        if result.inserted:
            line += f"  {result.inserted} rows"
        if result.detail:
            line += f"  ({result.detail})"
        write(line)

    if unavailable is not None:
        raise GeminiUnavailable(
            f"{unavailable} Files were left for the next run; everything else was processed."
        )
    if key is None:
        run.error = NO_KEY_SENTENCE
        return "skipped", report
    return None, report


def advance_watermark(previous: str | None, final: list[str], held: list[str]) -> str | None:
    """The newest time every file at or before it is finished with. A file
    left for the next run holds the watermark strictly below its own time, so
    it is listed again; idempotency makes re-listing a finished file free."""
    candidates = [stamp for stamp in final if stamp]
    held = [stamp for stamp in held if stamp]
    if held:
        floor = min(held)
        candidates = [stamp for stamp in candidates if stamp < floor]
    best = max(candidates, default=None)
    if best is None or (previous is not None and best <= previous):
        return previous
    return best


def quarantined_files(household, *, since: datetime.datetime):
    """This job's quarantines recorded since `since`, newest first."""
    from legacy.enums import IngestState
    from legacy.models.ingest import IngestedFile

    return IngestedFile.objects.filter(
        household=household,
        state=IngestState.QUARANTINED,
        source_file_id__startswith=SOURCE_FILE_PREFIX,
        last_attempt_at__gte=since,
    ).order_by("-last_attempt_at")
