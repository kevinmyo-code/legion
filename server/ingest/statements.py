"""`drive_statements`: raw bank statements from a Drive folder, through the gate
(backend-etl ticket 06, map ruling 3, CLAUDE.md section 4 rule 1 as amended
2026-09-27).

Kevin drops a bank's own PDF or CSV into one Drive folder. Every six hours this
lists that folder, and for each file it has not seen:

1. **Deterministic first.** Every registered parser (`PARSERS`) is offered the
   bytes; the first that recognises the layout returns the commit payload with
   `DETERMINISTIC` provenance. None exists yet: porting the BofA parser is
   ticket 10, and it plugs in by appending to `PARSERS`.
2. **Otherwise Gemini, on the household's own key** (`LEGION_GEMINI_KEY`). The
   model transcribes: the lines, the opening and closing balances, and the
   activity totals the statement prints. The stated total is then computed
   HERE, deterministically, from those printed totals, never from the lines.
   **All three anchors are required**: a statement the model reads without a
   printed total, an opening balance or a closing balance is quarantined with
   the missing ones named. Rows are `LLM_RECONCILED`.
3. **The gate is `ingest.views.commit_statement`**, the same function
   `POST /api/ingest/statement` calls, in-process. Quarantine on any mismatch,
   nothing partial written, anchors persisted on `public.statements`.

**Idempotency is the content hash**, `ingested_files (household, content_sha256)`.
A file already committed or quarantined is never downloaded into Gemini again.
The Drive `modifiedTime` watermark only narrows what is listed; a file seen
twice is still a no-op because of the hash, not because of the watermark.

**Refusals, in words.** A quarantined file is recorded on `ingested_files` with
its reason, and `/api/freshness` says so for `drive_statements`. No key records
`skipped` with a sentence. A refused Drive login records `needs_login`.

**Never logged:** the document's content and the key. The key goes in a header,
not the URL, so no exception message or proxy log can carry it.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import json
import os
import socket
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

from django.db import DataError, IntegrityError

from ingest import gate, vault
from ingest.drive import (
    DriveClient,
    DriveRefused,
    Response,
    Transport,
    _quote,
    urllib_transport,
)
from ingest.models import IngestRun, SessionSource

GEMINI_KEY_ENV = "LEGION_GEMINI_KEY"
FOLDER_CONFIG_KEY = "statements_folder_id"

# The cheap one-shot model family the phone's sub-agents use
# (`ai/SubAgent.kt` DEFAULT_MODEL), on the same v1beta endpoint.
GEMINI_MODEL = "gemini-3.5-flash-lite"
GEMINI_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
)
GEMINI_TIMEOUT_SECONDS = 180

# Gemini refuses an inline request over 20 MB, and base64 grows the bytes by a
# third, so 14 MiB of document is the most that fits. A bank statement is
# usually well under 1 MB. Also keeps the 512Mi job far from its limit.
MAX_STATEMENT_BYTES = 14 * 1024 * 1024

PDF_MIME = "application/pdf"
CSV_MIMES = frozenset({"text/csv", "text/comma-separated-values", "application/csv"})
# Mime types Drive gives a `.csv` depending on the uploader's machine.
CSV_BY_NAME_MIMES = frozenset(
    {"text/plain", "application/vnd.ms-excel", "application/octet-stream"}
)
GOOGLE_NATIVE_PREFIX = "application/vnd.google-apps."

# `ingested_files.source_file_id` for a file this job read: says where it came
# from, and is how freshness finds this job's quarantines.
SOURCE_FILE_PREFIX = "drive:"

NO_KEY_SENTENCE = (
    f"{GEMINI_KEY_ENV} is not set on the server, so a statement no parser recognises "
    f"cannot be read. Nothing was sent to Gemini and nothing was written."
)


# =============================================================================
# The parser seam (ticket 10 plugs in here)
# =============================================================================


class StatementParser(Protocol):
    """A deterministic statement parser. `parse` returns the commit payload
    (everything `commit_statement` reads except `content_sha256`, the file
    facts and `provenance`, which the pipeline sets) when it recognises the
    layout, and None when it does not. It must never guess: a line it does not
    recognise inside a section it does is a quarantine, not a skip (section 4
    rule 6), so it raises `ParserRefused` with the reason."""

    name: str

    def parse(self, content: bytes, *, mime_type: str, file_name: str) -> dict | None: ...


class ParserRefused(Exception):
    """A parser recognised the layout and refused the document. The message is
    the quarantine reason."""


PARSERS: list[StatementParser] = []


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
    from ingest.drive import _OPENER

    with _OPENER.open(request, timeout=GEMINI_TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


_MONEY = {"type": "integer"}
_NULLABLE_MONEY = {"type": "integer", "nullable": True}

RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "account_last4": {"type": "string"},
        "account_nickname": {"type": "string"},
        "currency": {"type": "string", "enum": ["USD", "SGD"]},
        "period_start": {"type": "string", "nullable": True},
        "period_end": {"type": "string", "nullable": True},
        "opening_balance_cents": _NULLABLE_MONEY,
        "closing_balance_cents": _NULLABLE_MONEY,
        "printed_totals": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "label": {"type": "string"},
                    "amount_cents": _MONEY,
                    "direction": {"type": "string", "enum": ["in", "out"]},
                },
                "required": ["label", "amount_cents", "direction"],
            },
        },
        "lines": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "txn_date": {"type": "string"},
                    "description": {"type": "string"},
                    "amount_cents": _MONEY,
                    "balance_cents": _NULLABLE_MONEY,
                },
                "required": ["txn_date", "description", "amount_cents"],
            },
        },
    },
    "required": [
        "account_last4",
        "account_nickname",
        "currency",
        "opening_balance_cents",
        "closing_balance_cents",
        "printed_totals",
        "lines",
    ],
}

PROMPT = """You are transcribing one bank or card statement into JSON. Transcribe; never \
estimate, infer, or fill a gap. Your output is checked against the statement's own printed \
figures, and a statement that does not tie out is refused.

Money is an integer number of cents: $1,234.56 is 123456. Never a decimal.

Balances and amounts are from the ACCOUNT HOLDER's side:
- A bank account's balance is what the holder has. A card's balance is what the holder \
OWES, so it is NEGATIVE: a card showing a new balance of $500.00 owed is -50000.
- Each line's amount_cents moves the holder's balance: money in (a deposit, a refund, a \
payment made TO a card) is positive; money out (a withdrawal, a purchase, a fee, interest \
charged) is negative.
- So closing_balance_cents - opening_balance_cents equals the sum of every line's amount_cents.

Fields:
- account_last4: the last four digits of the account or card number, exactly four digits.
- account_nickname: the bank's name and the account's type, e.g. "BofA checking" or \
"DBS credit card".
- currency: USD or SGD.
- period_start, period_end: the statement period as YYYY-MM-DD, or null if not printed.
- opening_balance_cents: the balance printed at the START of the period (previous balance, \
beginning balance, balance brought forward). null if the statement prints none.
- closing_balance_cents: the balance printed at the END of the period (new balance, ending \
balance, balance carried forward). null if the statement prints none.
- printed_totals: every activity total the statement PRINTS for the period, as printed \
(a positive magnitude), with direction "in" for money in (total deposits, payments and \
other credits) and "out" for money out (total withdrawals, purchases, fees charged, \
interest charged). Include each printed activity total exactly once. Do not include \
balances, credit limits, minimum payments, year-to-date totals, or a total you added up \
yourself. Empty if the statement prints no activity totals.
- lines: EVERY transaction in the period, in the order printed, with txn_date as \
YYYY-MM-DD, the description as printed, the signed amount_cents, and balance_cents where \
the statement prints a running balance on that line (else null). Include fees and interest \
charged as lines. Leave out nothing and add nothing."""


def _gemini_parts(content: bytes, mime_type: str) -> list[dict]:
    if mime_type == PDF_MIME:
        encoded = base64.b64encode(content).decode("ascii")
        return [{"text": PROMPT}, {"inlineData": {"mimeType": PDF_MIME, "data": encoded}}]
    text = content.decode("utf-8-sig", errors="replace")
    return [{"text": f"{PROMPT}\n\nThe statement, exported as CSV by the bank:\n\n{text}"}]


def gemini_extract(
    content: bytes, *, mime_type: str, key: str, transport: Transport | None = None
) -> dict:
    """The model's transcription, as a dict matching `RESPONSE_SCHEMA`."""
    body = json.dumps(
        {
            "contents": [{"role": "user", "parts": _gemini_parts(content, mime_type)}],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": RESPONSE_SCHEMA,
                "temperature": 0,
            },
        }
    ).encode()
    send = transport or gemini_transport
    try:
        response = send(
            "POST",
            GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            body,
        )
    except (TimeoutError, socket.timeout, urllib.error.URLError, ConnectionError) as exc:
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
        payload = response.json()
        candidate = (payload.get("candidates") or [None])[0]
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
# From the model's transcription to the commit payload
# =============================================================================


def stated_total_from(printed_totals: object) -> int | None:
    """The statement's own net total, computed from the activity totals it
    PRINTS: money in minus money out. None when it prints none.

    This is what makes the stated total an anchor rather than an identity: it
    comes from the printed section totals, never from the lines it is checked
    against (section 4 rule 8). A non-integer figure raises `GateInputError`.
    """
    if not isinstance(printed_totals, list) or not printed_totals:
        return None
    total = 0
    for item in printed_totals:
        if not isinstance(item, dict):
            raise gate.GateInputError("A printed total was not an object.")
        magnitude = abs(gate.cents(item.get("amount_cents"), "printed_totals.amount_cents"))
        direction = item.get("direction")
        if direction == "in":
            total += magnitude
        elif direction == "out":
            total -= magnitude
        else:
            raise gate.GateInputError(
                f"A printed total's direction must be in or out; got {direction!r}."
            )
    return total


def missing_anchors(extraction: dict, stated_total: int | None) -> list[str]:
    missing = []
    if stated_total is None:
        missing.append("a printed total")
    if extraction.get("opening_balance_cents") is None:
        missing.append("an opening balance")
    if extraction.get("closing_balance_cents") is None:
        missing.append("a closing balance")
    return missing


def three_anchor_reason(missing: list[str]) -> str:
    return (
        f"The statement as read by Gemini has no {', no '.join(missing)}. A statement read "
        f"by a model must state all three anchors (printed total, opening balance, closing "
        f"balance) to be checked, so nothing was written."
    )


def payload_from_extraction(extraction: dict) -> tuple[dict | None, str | None]:
    """(payload, None), or (None, quarantine reason) when an anchor is missing."""
    stated = stated_total_from(extraction.get("printed_totals"))
    missing = missing_anchors(extraction, stated)
    if missing:
        return None, three_anchor_reason(missing)
    lines = extraction.get("lines") or []
    payload = {
        "provenance": gate.LLM_RECONCILED,
        "lines": [
            {
                "txn_date": line.get("txn_date"),
                "description": line.get("description"),
                "amount_cents": line.get("amount_cents"),
                "balance_cents": line.get("balance_cents"),
            }
            for line in lines
            if isinstance(line, dict)
        ],
        "stated_total_cents": stated,
        "opening_balance_cents": extraction.get("opening_balance_cents"),
        "closing_balance_cents": extraction.get("closing_balance_cents"),
        "account_last4": extraction.get("account_last4"),
        "account_nickname": extraction.get("account_nickname"),
        "currency": extraction.get("currency"),
        "period_start": extraction.get("period_start"),
        "period_end": extraction.get("period_end"),
    }
    if len(payload["lines"]) != len(lines):
        # Section 4 rule 6: a line that is not a line is a hard failure.
        return None, "Gemini returned a transaction that was not an object. Nothing was written."
    return payload, None


# =============================================================================
# One run
# =============================================================================


@dataclass
class FileResult:
    file_id: str
    name: str
    action: str  # committed / already_committed / quarantined / known / skipped / deferred
    detail: str = ""
    inserted: int = 0


@dataclass
class RunReport:
    results: list[FileResult] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


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
        return "pdf"
    if mime in CSV_MIMES or (mime in CSV_BY_NAME_MIMES and name.endswith(".csv")):
        return "csv"
    return None


def previous_watermark(household) -> str | None:
    run = (
        IngestRun.objects.filter(
            household=household, source="drive_statements", watermark__isnull=False
        )
        .order_by("-started_at")
        .first()
    )
    return run.watermark if run else None


def list_query(folder_id: str, watermark: str | None) -> str:
    """Files directly in the folder, not trashed, and (with a watermark) new or
    changed since. `createdTime` too, because an upload may keep the file's
    older local modified time, and such a file would otherwise never be seen."""
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
    from django.db import transaction

    from ingest.views import _upsert_file
    from legacy.enums import IngestState

    with transaction.atomic():
        _upsert_file(file_facts, sha, IngestState.QUARANTINED, reason, household)


def extract_payload(
    content: bytes,
    *,
    kind: str,
    name: str,
    key: str | None,
    gemini: Callable[..., dict],
) -> tuple[dict | None, str | None]:
    """Parsers first, then Gemini. (payload, None) to commit, (None, reason) to
    quarantine. Raises `_NeedsKey` when only Gemini could read it and there is
    no key."""
    mime_type = PDF_MIME if kind == "pdf" else "text/csv"
    for parser in PARSERS:
        try:
            parsed = parser.parse(content, mime_type=mime_type, file_name=name)
        except ParserRefused as exc:
            return None, str(exc)
        if parsed is not None:
            return {**parsed, "provenance": gate.DETERMINISTIC}, None
    if key is None:
        raise _NeedsKey
    try:
        extraction = gemini(content, mime_type=mime_type, key=key)
    except GeminiRefusedDocument as exc:
        return None, str(exc)
    try:
        return payload_from_extraction(extraction)
    except gate.GateInputError as exc:
        return None, f"Gemini's transcription could not be checked: {exc} Nothing was written."


class _NeedsKey(Exception):
    pass


def process(
    run: IngestRun,
    *,
    drive_transport: Transport | None = None,
    gemini: Callable[..., dict] | None = None,
    write: Callable[[str], object] = lambda line: None,
) -> tuple[str | None, RunReport]:
    """One household's run. Returns the outcome for `run_job` and the report."""
    from django.db import DatabaseError

    from ingest.views import commit_statement

    household = run.household
    report = RunReport()
    credential, secret = vault.session_for(household, SessionSource.DRIVE)
    folder = folder_id_of(credential)
    if folder is None:
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
            report.results.append(FileResult(file_id, name, "skipped", why))
            report.notes.append(f"{name}: skipped, {why}.")
            final_times.append(stamp)
            continue
        listed_size = item.get("size")
        if listed_size is not None and int(listed_size) > MAX_STATEMENT_BYTES:
            why = f"{int(listed_size)} bytes is over the {MAX_STATEMENT_BYTES}-byte limit"
            report.results.append(FileResult(file_id, name, "skipped", why))
            report.notes.append(f"{name}: skipped, {why}.")
            final_times.append(stamp)
            continue

        try:
            content = client.download_bytes(file_id, max_bytes=MAX_STATEMENT_BYTES)
        except DriveRefused as exc:
            raise vault.refuse_session(credential, str(exc)) from None
        sha = hashlib.sha256(content).hexdigest()
        facts = {
            "content_sha256": sha,
            "source_file_id": f"{SOURCE_FILE_PREFIX}{file_id}",
            "display_name": name,
            "size_bytes": len(content),
        }

        known = _known(household, sha)
        if known is not None:
            report.results.append(FileResult(file_id, name, "known", str(known.state)))
            final_times.append(stamp)
            continue

        try:
            payload, reason = extract_payload(
                content, kind=kind, name=name, key=key, gemini=gemini
            )
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

        if payload is None:
            _record_quarantine(household, facts, sha, reason or "")
            report.results.append(FileResult(file_id, name, "quarantined", reason or ""))
            final_times.append(stamp)
            continue

        try:
            result = commit_statement({**payload, **facts}, household)
        except gate.GateInputError as exc:
            reason = f"The statement could not be checked: {exc} Nothing was written."
            _record_quarantine(household, facts, sha, reason)
            report.results.append(FileResult(file_id, name, "quarantined", reason))
            final_times.append(stamp)
            continue
        except (IntegrityError, DataError) as exc:
            first = str(exc).strip().splitlines()[0] if str(exc).strip() else type(exc).__name__
            reason = f"The statement was refused by the database: {first}. Nothing was written."
            _record_quarantine(household, facts, sha, reason)
            report.results.append(FileResult(file_id, name, "quarantined", reason))
            final_times.append(stamp)
            continue
        except DatabaseError:
            held_times.append(stamp)
            raise
        final_times.append(stamp)
        if result.outcome == gate.COMMITTED:
            run.rows_written += int(result.body.get("inserted", 0))
            report.results.append(
                FileResult(file_id, name, "committed", inserted=result.body.get("inserted", 0))
            )
        elif result.outcome == gate.QUARANTINED:
            report.results.append(
                FileResult(file_id, name, "quarantined", result.body.get("reason", ""))
            )
        else:
            report.results.append(FileResult(file_id, name, "already_committed"))

    run.rows_unchanged = sum(1 for r in report.results if r.action in ("known",))
    run.watermark = advance_watermark(watermark, final_times, held_times)
    for result in report.results:
        write(f"  {result.action:<17} {result.name}" + (f" ({result.detail})" if result.detail else ""))

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
    if held:
        floor = min(stamp for stamp in held if stamp) if any(held) else ""
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
