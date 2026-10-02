"""backend-etl ticket 06: `drive_statements`, with Drive and Gemini faked.

Drive is faked at the HTTP level (the real `DriveClient` runs over a fake
transport), Gemini at the function level (`process(gemini=...)`), plus one
test of `gemini_extract`'s own request over a fake transport.

Owed and not provable here: the live verification box, one real statement
dropped in the folder committing with its anchors persisted.
"""

from __future__ import annotations

import base64
import datetime
import io
import json
import re
import urllib.parse
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from django.core.management import call_command
from django.core.management.base import CommandError

from ingest import statements, vault
from ingest.drive import API, TOKEN_URI, Response
from ingest.folder_ids import FolderIdError, folder_id_from
from ingest.freshness import freshness_for
from ingest.jobs import run_job
from ingest.models import IngestRun, Outcome, Source, SourceCredential
from legacy.enums import IngestState, Provenance
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import LedgerTransaction, Statement

FOLDER = "19tqQKzPKZVm0zCVG-lt7zaERqstIPkNd"
DRIVE_SECRET = {
    "refresh_token": "1//refresh-token-value",
    "client_id": "client.apps.googleusercontent.com",
    "client_secret": "client-secret-value",
}
KEY = "AIza-test-key-never-logged"
PDF_BYTES = b"%PDF-1.7 a bank statement"


def a_reading(**overrides) -> dict:
    """What Gemini returns for a statement that ties out on all three anchors:
    the lines sum to 149550, the printed total, and 649550 - 500000."""
    reading = {
        "account_last4": "1234",
        "account_nickname": "BofA checking",
        "currency": "USD",
        "stated_total_cents": 149550,
        "opening_balance_cents": 500000,
        "closing_balance_cents": 649550,
        "period_start": "2026-07-01",
        "period_end": "2026-07-31",
        "lines": [
            {"txn_date": "2026-07-03", "description": "COFFEE", "amount_cents": -450},
            {"txn_date": "2026-07-11", "description": "SALARY", "amount_cents": 300000},
            {"txn_date": "2026-07-20", "description": "RENT", "amount_cents": -150000},
        ],
    }
    reading.update(overrides)
    return reading


# =============================================================================
# Fakes
# =============================================================================


class FakeDrive:
    """Drive's token endpoint, files.list and alt=media download.

    `honour_watermark=False` lists every file every time, so a test can prove
    idempotency does not depend on the watermark."""

    def __init__(self):
        self.files: list[dict] = []
        self.contents: dict[str, bytes] = {}
        self.queued: dict[str, list[Response]] = {}
        self.downloads: list[str] = []
        self.queries: list[str] = []
        self.honour_watermark = True

    def add(self, name, content=PDF_BYTES, *, mime="application/pdf", modified=None, size=True):
        file_id = f"file{len(self.files) + 1}"
        stamp = modified or f"2026-08-0{len(self.files) + 1}T12:00:00.000Z"
        entry = {
            "id": file_id,
            "name": name,
            "mimeType": mime,
            "parents": [FOLDER],
            "createdTime": stamp,
            "modifiedTime": stamp,
        }
        if content is not None:
            self.contents[file_id] = content
            if size:
                entry["size"] = str(len(content))
        self.files.append(entry)
        return entry

    def __call__(self, method, url, headers, body):
        for prefix, replies in self.queued.items():
            if url.startswith(prefix) and replies:
                return replies.pop(0)
        if url == TOKEN_URI:
            return Response(200, {}, json.dumps({"access_token": "ya29.access"}).encode())
        media = re.match(rf"{re.escape(API)}/files/([^?]+)\?alt=media$", url)
        if media:
            file_id = urllib.parse.unquote(media.group(1))
            self.downloads.append(file_id)
            return Response(200, {}, self.contents[file_id])
        if url.startswith(f"{API}/files?"):
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["q"][0]
            self.queries.append(query)
            out = [f for f in self.files if f"'{FOLDER}' in parents" in query]
            mark = re.search(r"modifiedTime > '([^']+)'", query)
            if mark and self.honour_watermark:
                out = [f for f in out if f["modifiedTime"] > mark.group(1)]
            return Response(200, {}, json.dumps({"files": out}).encode())
        return Response(404, {}, b'{"error": {"message": "not found"}}')


class FakeGemini:
    def __init__(self, reading: dict | None = None):
        self.reading = reading if reading is not None else a_reading()
        self.calls: list[tuple[bytes, str]] = []
        self.raises: Exception | None = None

    def __call__(self, content, *, key):
        self.calls.append((content, key))
        if self.raises is not None:
            raise self.raises
        return self.reading


@pytest.fixture
def vault_key(monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())


@pytest.fixture
def gemini_key(monkeypatch):
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, KEY)


@pytest.fixture
def no_gemini_key(monkeypatch):
    monkeypatch.delenv(statements.GEMINI_KEY_ENV, raising=False)


@pytest.fixture
def connected(vault_key, household_a):
    return vault.store(
        household_a, "drive", DRIVE_SECRET, config={statements.FOLDER_CONFIG_KEY: FOLDER}
    )


@pytest.fixture
def drive():
    return FakeDrive()


@pytest.fixture
def gemini():
    return FakeGemini()


@pytest.fixture
def registry(monkeypatch):
    """An empty parser registry and no provisional writer, restored after."""
    monkeypatch.setattr(statements, "PARSERS", [])
    monkeypatch.setattr(statements, "_PROVISIONAL_WRITER", [])
    return statements.PARSERS


def run(household, drive, gemini) -> IngestRun:
    lines: list[str] = []

    def job(ingest_run):
        outcome, _ = statements.process(
            ingest_run, drive_transport=drive, gemini=gemini, write=lines.append
        )
        return outcome

    result = run_job(Source.DRIVE_STATEMENTS, household, job)
    result.output = "\n".join(lines)
    return result


def file_row(name) -> IngestedFile:
    return IngestedFile.objects.get(display_name=name)


# =============================================================================
# Commit, and idempotency
# =============================================================================


@pytest.mark.django_db
def test_a_statement_that_ties_out_commits_with_its_anchors(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.add("bofa_1234_2026-07.pdf")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.OK, result.error
    assert result.rows_written == 3

    statement = Statement.objects.get(household=household_a)
    assert statement.provenance == Provenance.LLM_RECONCILED
    # Section 4 rule 8: the three anchors the gate checked, persisted.
    assert statement.stated_total_cents == 149550
    assert statement.opening_balance_cents == 500000
    assert statement.closing_balance_cents == 649550
    assert statement.account_last4 == "1234"
    rows = LedgerTransaction.objects.filter(statement=statement)
    assert sorted(rows.values_list("amount_cents", flat=True)) == [-150000, -450, 300000]
    assert sorted(rows.values_list("line_ref", flat=True)) == [
        "bofa_1234_2026-07.pdf:1",
        "bofa_1234_2026-07.pdf:2",
        "bofa_1234_2026-07.pdf:3",
    ]
    assert set(rows.values_list("provenance", flat=True)) == {Provenance.LLM_RECONCILED}

    stored = file_row("bofa_1234_2026-07.pdf")
    assert stored.state == IngestState.INGESTED
    assert stored.source_file_id == "drive:file1"
    assert gemini.calls == [(PDF_BYTES, KEY)]


@pytest.mark.django_db
def test_the_same_file_twice_gives_one_statement_and_one_gemini_call(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.honour_watermark = False  # idempotency must not depend on it
    drive.add("statement.pdf")
    run(household_a, drive, gemini)
    # Listed again, and the same bytes re-uploaded under a new file id.
    drive.add("statement (1).pdf", modified="2026-09-01T00:00:00.000Z")
    second = run(household_a, drive, gemini)

    assert second.outcome == Outcome.OK
    assert second.rows_written == 0
    assert second.rows_unchanged == 2
    assert Statement.objects.filter(household=household_a).count() == 1
    assert LedgerTransaction.objects.filter(household=household_a).count() == 3
    assert len(gemini.calls) == 1


@pytest.mark.django_db
def test_the_watermark_narrows_the_next_listing(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.add("statement.pdf", modified="2026-08-05T10:00:00.000Z")
    first = run(household_a, drive, gemini)
    assert first.watermark == "2026-08-05T10:00:00.000Z"
    run(household_a, drive, gemini)
    assert "modifiedTime > '2026-08-05T10:00:00.000Z'" in drive.queries[-1]
    assert drive.downloads == ["file1"]


# =============================================================================
# Quarantine
# =============================================================================


@pytest.mark.django_db
def test_a_mismatched_total_quarantines_and_writes_no_rows(
    connected, household_a, drive, gemini_key, registry
):
    gemini = FakeGemini(a_reading(stated_total_cents=149551))
    drive.add("bad-total.pdf")
    result = run(household_a, drive, gemini)

    assert result.outcome == Outcome.OK
    assert result.rows_written == 0
    assert not Statement.objects.exists()
    assert not LedgerTransaction.objects.exists()
    stored = file_row("bad-total.pdf")
    assert stored.state == IngestState.QUARANTINED
    assert "149551" in stored.quarantine_reason

    # Never re-sent to Gemini, even when listed again.
    drive.honour_watermark = False
    run(household_a, drive, gemini)
    assert len(gemini.calls) == 1


@pytest.mark.django_db
def test_balances_that_disagree_with_the_lines_quarantine(
    connected, household_a, drive, gemini_key, registry
):
    gemini = FakeGemini(a_reading(closing_balance_cents=649000))
    drive.add("bad-balance.pdf")
    run(household_a, drive, gemini)
    assert file_row("bad-balance.pdf").state == IngestState.QUARANTINED
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("missing", "named"),
    [
        (["stated_total_cents"], "has no printed total."),
        (["opening_balance_cents"], "has no opening balance."),
        (["closing_balance_cents", "stated_total_cents"], "no printed total, no closing balance."),
    ],
)
def test_fewer_than_three_anchors_quarantines(
    connected, household_a, drive, gemini_key, registry, missing, named
):
    gemini = FakeGemini(a_reading(**{key: None for key in missing}))
    drive.add("two-anchors.pdf")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.OK
    stored = file_row("two-anchors.pdf")
    assert stored.state == IngestState.QUARANTINED
    assert named in stored.quarantine_reason
    assert "all three anchors" in stored.quarantine_reason
    assert not Statement.objects.exists()
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
def test_a_float_amount_from_gemini_quarantines_rather_than_rounding(
    connected, household_a, drive, gemini_key, registry
):
    reading = a_reading()
    reading["lines"][0]["amount_cents"] = -450.0
    drive.add("float.pdf")
    run(household_a, drive, FakeGemini(reading))
    stored = file_row("float.pdf")
    assert stored.state == IngestState.QUARANTINED
    assert "could not be checked" in stored.quarantine_reason
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
def test_a_document_gemini_cannot_read_quarantines(
    connected, household_a, drive, gemini_key, registry
):
    gemini = FakeGemini()
    gemini.raises = statements.GeminiRefusedDocument("Gemini could not read this. Nothing.")
    drive.add("scan.pdf")
    run(household_a, drive, gemini)
    assert file_row("scan.pdf").quarantine_reason == "Gemini could not read this. Nothing."


@pytest.mark.django_db
def test_a_quarantine_surfaces_in_words_through_freshness(
    connected, household_a, drive, gemini_key, registry, auth_client
):
    drive.add("bad-total.pdf")
    run(household_a, drive, FakeGemini(a_reading(stated_total_cents=1)))
    body = auth_client.get("/api/freshness").json()
    entry = next(s for s in body["sources"] if s["source"] == Source.DRIVE_STATEMENTS)
    assert "1 statement file was quarantined and nothing from it was written" in entry["sentence"]
    assert "bad-total.pdf" in entry["sentence"]
    assert "Lines sum to" in entry["sentence"]
    canvas = next(s for s in body["sources"] if s["source"] == Source.CANVAS)
    assert "quarantined" not in canvas["sentence"]


# =============================================================================
# No key, a refused login, what is skipped
# =============================================================================


@pytest.mark.django_db
def test_no_key_records_skipped_with_a_sentence_and_sends_nothing(
    connected, household_a, drive, gemini, no_gemini_key, registry
):
    drive.add("statement.pdf")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.SKIPPED
    assert result.error == statements.NO_KEY_SENTENCE
    assert gemini.calls == []
    assert not IngestedFile.objects.exists()  # left for when there is a key
    entry = freshness_for(
        IngestRun.objects.filter(household=household_a), Source.DRIVE_STATEMENTS,
        result.finished_at,
    )
    assert "LEGION_GEMINI_KEY is not set" in entry["sentence"]


@pytest.mark.django_db
def test_a_file_held_for_want_of_a_key_is_read_once_there_is_one(
    connected, household_a, drive, gemini, registry, monkeypatch
):
    monkeypatch.delenv(statements.GEMINI_KEY_ENV, raising=False)
    drive.add("statement.pdf")
    run(household_a, drive, gemini)
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, KEY)
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.OK
    assert Statement.objects.count() == 1


@pytest.mark.django_db
def test_a_refused_refresh_token_is_needs_login(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.queued[TOKEN_URI] = [
        Response(400, {}, b'{"error": "invalid_grant", "error_description": "Bad"}')
    ]
    drive.add("statement.pdf")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.NEEDS_LOGIN
    assert SourceCredential.objects.get(household=household_a).invalid_since is not None
    assert gemini.calls == []


@pytest.mark.django_db
def test_a_401_from_drive_is_needs_login(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.queued[f"{API}/files?"] = [Response(401, {}, b'{"error": {"message": "nope"}}')]
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.NEEDS_LOGIN


@pytest.mark.django_db
def test_unsupported_types_and_oversize_files_are_skipped_with_a_note(
    connected, household_a, drive, gemini, gemini_key, registry
):
    drive.add("Notes", content=None, mime="application/vnd.google-apps.document")
    drive.add("photo.jpg", content=b"\xff\xd8", mime="image/jpeg")
    big = drive.add("huge.pdf", content=b"x")
    big["size"] = str(statements.MAX_STATEMENT_BYTES + 1)
    lines: list[str] = []

    def job(ingest_run):
        outcome, report = statements.process(ingest_run, drive_transport=drive, gemini=gemini)
        lines.extend(report.notes)
        return outcome

    result = run_job(Source.DRIVE_STATEMENTS, household_a, job)
    assert result.outcome == Outcome.OK
    assert drive.downloads == []
    assert gemini.calls == []
    assert not IngestedFile.objects.exists()
    text = "\n".join(lines)
    assert "Notes: skipped, a Google Docs/Sheets file" in text
    assert "photo.jpg: skipped, image/jpeg is not a PDF or a CSV" in text
    assert f"over the {statements.MAX_STATEMENT_BYTES}-byte limit" in text


@pytest.mark.django_db
def test_gemini_unavailable_fails_the_run_and_leaves_the_file_for_next_time(
    connected, household_a, drive, gemini_key, registry
):
    gemini = FakeGemini()
    gemini.raises = statements.GeminiUnavailable("Gemini is unavailable right now (HTTP 503).")
    drive.add("statement.pdf")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.FAILED
    assert "HTTP 503" in result.error
    assert not IngestedFile.objects.exists()

    gemini.raises = None
    assert run(household_a, drive, gemini).outcome == Outcome.OK
    assert Statement.objects.count() == 1


@pytest.mark.django_db
def test_a_refused_key_fails_the_run_without_saying_the_key(
    connected, household_a, drive, gemini_key, registry
):
    def refusing(content, *, key):
        return statements.gemini_extract(
            content, key=key, transport=lambda *a: Response(403, {}, b"{}")
        )

    drive.add("statement.pdf")
    result = run(household_a, drive, refusing)
    assert result.outcome == Outcome.FAILED
    assert "refused LEGION_GEMINI_KEY" in result.error
    assert KEY not in result.error
    assert not IngestedFile.objects.exists()


# =============================================================================
# CSVs, and the parser registry
# =============================================================================


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("name", "mime"),
    [
        ("bofa_1234_activity_2026-09-27.csv", "text/csv"),
        ("export.csv", "application/vnd.ms-excel"),
        ("export.csv", "text/plain"),
    ],
)
def test_a_csv_never_reaches_gemini(
    connected, household_a, drive, gemini, gemini_key, registry, name, mime
):
    drive.add(name, content=b"Date,Description,Amount\n09/01/2026,COFFEE,-4.50\n", mime=mime)
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.OK
    assert gemini.calls == []
    stored = file_row(name)
    assert stored.state == IngestState.QUARANTINED
    assert stored.quarantine_reason.startswith(statements.CSV_NOT_RECOGNISED)
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
def test_a_csv_is_not_sent_to_gemini_even_through_the_real_client(
    connected, household_a, drive, gemini_key, registry, monkeypatch
):
    """The default Gemini path, not an injected fake: its transport must
    never be touched for a CSV."""
    touched = []
    monkeypatch.setattr(statements, "gemini_transport", lambda *a: touched.append(a))
    monkeypatch.setattr(statements, "urllib_transport", drive)
    drive.add("activity.csv", content=b"a,b\n1,2\n", mime="text/csv")
    out = io.StringIO()
    call_command("drive_statements", stdout=out)
    assert touched == []
    assert file_row("activity.csv").state == IngestState.QUARANTINED


class _Parser:
    def __init__(self, name, kinds, result=None, refuse=None):
        self.name = name
        self.kinds = frozenset(kinds)
        self.result = result
        self.refuse = refuse
        self.seen: list[str] = []

    def parse(self, content, *, file_name):
        self.seen.append(file_name)
        if self.refuse:
            raise statements.ParserRefused(self.refuse)
        return self.result


def _parsed_payload() -> dict:
    reading = a_reading()
    return {key: reading[key] for key in reading}


@pytest.mark.django_db
def test_the_registry_is_tried_before_gemini(
    connected, household_a, drive, gemini, gemini_key, registry
):
    csv_only = statements.register_parser(_Parser("csv-only", {"csv"}))
    declines = statements.register_parser(_Parser("declines", {"pdf"}, result=None))
    bofa = statements.register_parser(
        _Parser("bofa", {"pdf"}, result=statements.Parsed(_parsed_payload()))
    )
    drive.add("bofa.pdf")
    result = run(household_a, drive, gemini)

    assert result.outcome == Outcome.OK
    assert gemini.calls == []
    assert csv_only.seen == []  # a CSV reader is never offered a PDF
    assert declines.seen == ["bofa.pdf"] and bofa.seen == ["bofa.pdf"]
    statement = Statement.objects.get()
    assert statement.provenance == Provenance.DETERMINISTIC
    assert statement.stated_total_cents == 149550


@pytest.mark.django_db
def test_a_pdf_no_parser_recognises_falls_to_gemini(
    connected, household_a, drive, gemini, gemini_key, registry
):
    declines = statements.register_parser(_Parser("declines", {"pdf"}, result=None))
    drive.add("other-bank.pdf")
    run(household_a, drive, gemini)
    assert declines.seen == ["other-bank.pdf"]
    assert len(gemini.calls) == 1
    assert Statement.objects.get().provenance == Provenance.LLM_RECONCILED


@pytest.mark.django_db
def test_a_parser_that_refuses_quarantines_without_gemini(
    connected, household_a, drive, gemini, gemini_key, registry
):
    statements.register_parser(
        _Parser("bofa", {"pdf"}, refuse="An interest line did not parse. Nothing was written.")
    )
    drive.add("bofa.pdf")
    run(household_a, drive, gemini)
    assert gemini.calls == []
    assert file_row("bofa.pdf").quarantine_reason == (
        "bofa: An interest line did not parse. Nothing was written."
    )


@pytest.mark.django_db
def test_a_provisional_result_goes_to_the_writer_never_the_gate(
    connected, household_a, drive, gemini, gemini_key, registry, monkeypatch
):
    """Ticket 09's seam: a recognised activity CSV and the rule 7 writer."""
    from ingest import views

    payload = {
        "account_last4": "1234",
        "lines": [{"txn_date": "2026-09-01", "description": "COFFEE", "amount_cents": -450}],
    }
    statements.register_parser(
        _Parser("bofa-activity", {"csv"}, result=statements.Parsed(payload, provisional=True))
    )
    written = []

    def writer(body, household):
        written.append((body, household))
        return views.CommitResult({"outcome": "COMMITTED", "inserted": 1}, 201)

    statements.register_provisional_writer(writer)
    monkeypatch.setattr(views, "commit_statement", lambda *a: pytest.fail("the gate was called"))
    drive.add("bofa_1234_activity_2026-09-27.csv", content=b"x,y\n", mime="text/csv")
    result = run(household_a, drive, gemini)

    assert result.outcome == Outcome.OK
    assert result.rows_written == 1
    assert gemini.calls == []
    (body, household), = written
    assert household == household_a
    assert body["provenance"] == Provenance.UNRECONCILED
    assert body["content_sha256"] and body["source_file_id"] == "drive:file1"


@pytest.mark.django_db
def test_a_provisional_result_with_no_writer_fails_loudly(
    connected, household_a, drive, gemini, gemini_key, registry
):
    statements.register_parser(
        _Parser("bofa-activity", {"csv"}, result=statements.Parsed({}, provisional=True))
    )
    drive.add("activity.csv", content=b"x\n", mime="text/csv")
    result = run(household_a, drive, gemini)
    assert result.outcome == Outcome.FAILED
    assert "no provisional writer is registered" in result.error
    assert not IngestedFile.objects.exists()


# =============================================================================
# Ticket 10: the real BofA parsers, end to end, with no Gemini call
# =============================================================================

BOFA_FIXTURES = Path(__file__).parent / "bofa_fixtures"


class NoGemini(FakeGemini):
    def __call__(self, content, *, key):
        pytest.fail("Gemini was asked about a statement a parser reads")


@pytest.fixture
def bofa_registry(monkeypatch):
    """Only the real BofA parsers, as `IngestConfig.ready` registers them."""
    from ingest.parsers import bofa

    monkeypatch.setattr(statements, "PARSERS", [])
    monkeypatch.setattr(statements, "_PROVISIONAL_WRITER", [])
    bofa.register()
    return statements.PARSERS


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("fixture", "last4", "nickname", "opening", "closing", "amounts"),
    [
        (
            "bofa_multiline_wire.pdf",
            "1000",
            "BofA checking",
            500000,
            661733,
            [-4567, -2500, -1200, 50000, 120000],
        ),
        (
            "bofa_card_happy_path.pdf",
            "7823",
            "BofA card",
            -842150,
            -762125,
            [-90000, -60000, -45025, -500, 125550, 150000],
        ),
    ],
)
def test_a_bofa_statement_commits_deterministically_with_no_gemini_call(
    connected, household_a, drive, gemini_key, bofa_registry,
    fixture, last4, nickname, opening, closing, amounts,
):
    drive.add(fixture, content=(BOFA_FIXTURES / fixture).read_bytes())
    result = run(household_a, drive, NoGemini())
    assert result.outcome == Outcome.OK, result.error
    assert result.rows_written == len(amounts)

    statement = Statement.objects.get(household=household_a)
    assert statement.provenance == Provenance.DETERMINISTIC
    # Section 4 rule 8: the two printed anchors persisted, the absent one NULL.
    assert statement.stated_total_cents is None
    assert statement.opening_balance_cents == opening
    assert statement.closing_balance_cents == closing
    assert (statement.account_last4, statement.account_nickname) == (last4, nickname)
    rows = LedgerTransaction.objects.filter(statement=statement)
    assert sorted(rows.values_list("amount_cents", flat=True)) == amounts
    assert set(rows.values_list("provenance", flat=True)) == {Provenance.DETERMINISTIC}
    assert all(ref.startswith(f"{fixture}:'") for ref in rows.values_list("line_ref", flat=True))
    assert file_row(fixture).state == IngestState.INGESTED


@pytest.mark.django_db
def test_a_bofa_statement_the_parser_refuses_is_quarantined_with_no_gemini_call(
    connected, household_a, drive, gemini_key, bofa_registry
):
    name = "bofa_card_unparseable_row.pdf"
    drive.add(name, content=(BOFA_FIXTURES / name).read_bytes())
    result = run(household_a, drive, NoGemini())
    assert result.outcome == Outcome.OK, result.error
    assert not Statement.objects.exists()
    assert not LedgerTransaction.objects.exists()
    stored = file_row(name)
    assert stored.state == IngestState.QUARANTINED
    assert stored.quarantine_reason.startswith("bofa-card-pdf: ")


@pytest.mark.django_db
def test_a_non_bofa_pdf_still_goes_to_gemini_past_the_real_parsers(
    connected, household_a, drive, gemini, gemini_key, bofa_registry
):
    name = "unrecognized_reconciling.pdf"
    content = (BOFA_FIXTURES / name).read_bytes()
    drive.add(name, content=content)
    run(household_a, drive, gemini)
    assert gemini.calls == [(content, KEY)]
    assert Statement.objects.get().provenance == Provenance.LLM_RECONCILED


# =============================================================================
# One gate, two callers
# =============================================================================


@pytest.mark.django_db
def test_the_view_and_the_job_call_the_same_commit_function(
    connected, household_a, drive, gemini, gemini_key, registry, auth_client, monkeypatch
):
    from ingest import views

    real = views.commit_statement
    callers = []

    def spy(payload, household):
        callers.append(payload["content_sha256"])
        return real(payload, household)

    monkeypatch.setattr(views, "commit_statement", spy)
    drive.add("statement.pdf")
    run(household_a, drive, gemini)

    posted = dict(a_reading(), content_sha256="sha-from-the-phone", provenance="LLM_RECONCILED")
    posted["lines"] = [dict(line, line_ref=f"phone:{n}") for n, line in enumerate(posted["lines"])]
    response = auth_client.post("/api/ingest/statement", posted, format="json")
    assert response.status_code == 201, response.data
    assert len(callers) == 2
    assert callers[1] == "sha-from-the-phone"


# =============================================================================
# Gemini's request
# =============================================================================


def test_the_gemini_request_is_the_pdf_inline_with_the_format_schema():
    sent = []

    def transport(method, url, headers, body):
        sent.append((method, url, headers, json.loads(body)))
        text = json.dumps(a_reading())
        reply = {"candidates": [{"content": {"parts": [{"text": text}]}}]}
        return Response(200, {}, json.dumps(reply).encode())

    reading = statements.gemini_extract(PDF_BYTES, key=KEY, transport=transport)
    assert reading == a_reading()
    ((method, url, headers, body),) = sent
    assert method == "POST"
    assert url.endswith("/models/gemini-3.5-flash-lite:generateContent")
    assert KEY not in url
    assert headers["x-goog-api-key"] == KEY
    parts = body["contents"][0]["parts"]
    assert parts[1]["inlineData"] == {
        "mimeType": "application/pdf",
        "data": base64.b64encode(PDF_BYTES).decode(),
    }
    config = body["generationConfig"]
    assert config["responseMimeType"] == "application/json"
    schema = config["responseSchema"]
    # LEGION's statement format, field for field (docs/ledger-csv-import-format.md).
    assert set(schema["required"]) == {
        "account_last4",
        "account_nickname",
        "currency",
        "stated_total_cents",
        "opening_balance_cents",
        "closing_balance_cents",
        "lines",
    }
    assert set(schema["properties"]["lines"]["items"]["properties"]) == {
        "txn_date",
        "description",
        "amount_cents",
    }
    assert statements.GEMINI_TIMEOUT_SECONDS > 0


@pytest.mark.parametrize(
    ("status", "error"),
    [
        (401, statements.GeminiKeyRefused),
        (403, statements.GeminiKeyRefused),
        (429, statements.GeminiUnavailable),
        (503, statements.GeminiUnavailable),
        (400, statements.GeminiRefusedDocument),
    ],
)
def test_gemini_statuses(status, error):
    with pytest.raises(error) as caught:
        statements.gemini_extract(
            PDF_BYTES, key=KEY, transport=lambda *a: Response(status, {}, b"{}")
        )
    assert KEY not in str(caught.value)


def test_an_unparseable_gemini_answer_is_a_refused_document():
    reply = {
        "candidates": [{"content": {"parts": [{"text": "not json"}]}, "finishReason": "MAX_TOKENS"}]
    }
    with pytest.raises(statements.GeminiRefusedDocument, match="MAX_TOKENS"):
        statements.gemini_extract(
            PDF_BYTES, key=KEY, transport=lambda *a: Response(200, {}, json.dumps(reply).encode())
        )


def test_a_timeout_is_unavailable_not_a_quarantine():
    def slow(*args):
        raise TimeoutError("timed out")

    with pytest.raises(statements.GeminiUnavailable):
        statements.gemini_extract(PDF_BYTES, key=KEY, transport=slow)


# =============================================================================
# The watermark
# =============================================================================


def test_a_held_file_holds_the_watermark_below_it():
    assert statements.advance_watermark(None, ["b", "d"], ["c"]) == "b"
    assert statements.advance_watermark("a", ["b"], []) == "b"
    assert statements.advance_watermark("c", ["b"], []) == "c"
    assert statements.advance_watermark("a", [], ["b"]) == "a"


# =============================================================================
# The folder, and wiring
# =============================================================================


@pytest.mark.parametrize(
    "value",
    [
        FOLDER,
        f"https://drive.google.com/drive/folders/{FOLDER}",
        f"https://drive.google.com/drive/folders/{FOLDER}?usp=sharing",
        f"https://drive.google.com/drive/u/0/folders/{FOLDER}",
        f"https://drive.google.com/open?id={FOLDER}",
        f"  {FOLDER}\n",
    ],
)
def test_a_folder_id_from_an_id_or_a_url(value):
    assert folder_id_from(value) == FOLDER


@pytest.mark.parametrize("value", ["", "not a folder!", "https://example.com/folders/abcdefghijk"])
def test_a_bad_folder_value_is_refused_in_words(value):
    with pytest.raises(FolderIdError, match="Nothing was changed"):
        folder_id_from(value)


@pytest.mark.django_db
def test_set_statements_folder_merges_into_the_drive_config(vault_key, household_a):
    vault.store(household_a, "drive", DRIVE_SECRET, config={"backup": True})
    out = io.StringIO()
    call_command(
        "set_statements_folder", f"https://drive.google.com/drive/folders/{FOLDER}", stdout=out
    )
    config = SourceCredential.objects.get(household=household_a).config
    assert config == {"backup": True, statements.FOLDER_CONFIG_KEY: FOLDER}
    assert FOLDER in out.getvalue()
    assert statements.is_configured(household_a)


@pytest.mark.django_db
def test_set_statements_folder_needs_a_drive_login(vault_key, household_a):
    with pytest.raises(CommandError, match="Nothing was changed"):
        call_command("set_statements_folder", FOLDER)


@pytest.mark.django_db
def test_a_household_without_a_folder_records_not_set_up(
    vault_key, household_a, drive, gemini_key, monkeypatch
):
    vault.store(household_a, "drive", DRIVE_SECRET, config={"backup": True})
    monkeypatch.setattr(statements, "urllib_transport", drive)
    call_command("drive_statements", stdout=io.StringIO())
    latest = IngestRun.objects.get(household=household_a, source=Source.DRIVE_STATEMENTS)
    assert latest.outcome == Outcome.SKIPPED
    assert drive.queries == []
    entry = freshness_for(
        IngestRun.objects.filter(household=household_a),
        Source.DRIVE_STATEMENTS,
        datetime.datetime.now(datetime.UTC),
    )
    assert entry["sentence"] == "The statements folder is not set up."


def test_the_crontab_runs_drive_statements_every_six_hours():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "0 */6 * * * python manage.py drive_statements" in crontab.read_text().splitlines()


def test_the_gemini_key_is_a_cloud_run_secret():
    import importlib.util

    path = Path(__file__).resolve().parents[2] / "deploy" / "cloudrun" / "_common.py"
    spec = importlib.util.spec_from_file_location("cloudrun_common", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert "LEGION_GEMINI_KEY" in module.SECRET_ENV_VARS
    example = (path.parents[1] / ".env.example").read_text(encoding="utf-8")
    assert "\nLEGION_GEMINI_KEY=" in example
