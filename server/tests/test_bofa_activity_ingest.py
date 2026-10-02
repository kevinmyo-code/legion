"""backend-etl ticket 09: BofA activity CSVs through `drive_statements`, the rule
7 provisional writer, and the ledger API's words for an unverified row.

Drive is faked at the HTTP level (`FakeDrive` from ticket 06's tests). Gemini is
a fake that FAILS the test if it is ever asked: a CSV is never sent to it.
"""
from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest
from cryptography.fernet import Fernet

from ingest import gate, statements, vault
from ingest.models import Outcome
from ingest.provisional import PROVISIONAL_NOTE, ROW_NOTE, commit_provisional
from ingest.views import commit_statement
from legacy.enums import IngestState, Provenance
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import LedgerTransaction, Statement
from tests.test_drive_statements import DRIVE_SECRET, FOLDER, FakeDrive, NoGemini, run

FIXTURES = Path(__file__).parent / "bofa_fixtures"
CARD_NAME = "bofa_7823_activity_2026-09-27.csv"
CHECKING_NAME = "bofa_1000_period_2026-08-03.csv"
CARD_HEADER = "Posted Date,Reference Number,Payee,Address,Amount"


@pytest.fixture
def connected(monkeypatch, household_a):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, "AIza-test-key-never-logged")
    return vault.store(
        household_a, "drive", DRIVE_SECRET, config={statements.FOLDER_CONFIG_KEY: FOLDER}
    )


@pytest.fixture
def drive():
    return FakeDrive()


def card(*rows: str) -> bytes:
    return "\r\n".join([CARD_HEADER, *rows, ""]).encode()


def provisional_rows(household):
    return LedgerTransaction.objects.filter(
        household=household, provenance=Provenance.UNRECONCILED
    ).order_by("txn_date", "description")


def pull(household, drive, content: bytes, name: str = CARD_NAME, *, stamp: str | None = None):
    """One daily pull landing in the folder, then one watcher run."""
    drive.add(name, content=content, mime="text/csv", modified=stamp)
    result = run(household, drive, NoGemini())
    assert result.outcome == Outcome.OK, result.error
    return result


# =============================================================================
# Card: provisional, through the watcher
# =============================================================================


@pytest.mark.django_db
def test_a_card_activity_csv_lands_unverified_with_no_header_and_no_gemini(
    connected, household_a, drive
):
    content = (FIXTURES / "currentTransaction_7823.csv").read_bytes()
    result = pull(household_a, drive, content, "currentTransaction_7823.csv")

    rows = list(provisional_rows(household_a))
    assert [(r.txn_date.isoformat(), r.amount_cents) for r in rows] == [
        ("2026-06-09", -150000),
        ("2026-06-13", 60000),
    ]
    assert all(r.statement_id is None for r in rows)
    assert all((r.account_last4, r.account_nickname) == ("7823", "BofA card") for r in rows)
    assert all(r.line_ref.startswith("currentTransaction_7823.csv:") for r in rows)
    assert not Statement.objects.exists()
    assert result.rows_written == 2
    stored = IngestedFile.objects.get(display_name="currentTransaction_7823.csv")
    assert stored.state == IngestState.INGESTED
    # The job log is a surface: it says so in words.
    assert "provisional" in result.output and "unverified" in result.output


@pytest.mark.django_db
def test_re_pulling_the_same_day_replaces_rather_than_duplicates(connected, household_a, drive):
    first = card("09/01/2026,1,COFFEE,,-4.50", "09/02/2026,2,GROCER,,-60.00")
    pull(household_a, drive, first, stamp="2026-09-02T12:00:00.000Z")
    # The next pull the same day: the same two, plus one more.
    second = card(
        "09/01/2026,1,COFFEE,,-4.50", "09/02/2026,2,GROCER,,-60.00", "09/02/2026,3,FUEL,,-40.00"
    )
    result = pull(household_a, drive, second, stamp="2026-09-02T18:00:00.000Z")

    rows = provisional_rows(household_a)
    assert sorted(r.amount_cents for r in rows) == [-6000, -4000, -450]
    assert result.rows_written == 1
    assert "1 new, 2 already held, 0 no longer listed" in result.output


@pytest.mark.django_db
def test_identical_bytes_are_not_read_twice(connected, household_a, drive):
    content = card("09/01/2026,1,COFFEE,,-4.50")
    pull(household_a, drive, content, stamp="2026-09-02T12:00:00.000Z")
    drive.add("bofa_7823_activity_2026-09-27 (1).csv", content=content, mime="text/csv",
              modified="2026-09-02T13:00:00.000Z")
    result = run(household_a, drive, NoGemini())
    assert result.outcome == Outcome.OK
    assert provisional_rows(household_a).count() == 1
    assert "known" in result.output


@pytest.mark.django_db
def test_same_day_duplicates_match_by_position(connected, household_a, drive):
    """Two genuine $4.50 coffees on one day are two rows, on every pull. A
    later pull that lists one of them removes the other."""
    two = card("09/01/2026,1,COFFEE,,-4.50", "09/01/2026,2,COFFEE,,-4.50")
    pull(household_a, drive, two, stamp="2026-09-02T12:00:00.000Z")
    assert provisional_rows(household_a).count() == 2

    two_and_more = card(
        "09/01/2026,1,COFFEE,,-4.50", "09/01/2026,2,COFFEE,,-4.50", "09/01/2026,3,BAGEL,,-3.00"
    )
    pull(household_a, drive, two_and_more, stamp="2026-09-02T13:00:00.000Z")
    assert sorted(r.amount_cents for r in provisional_rows(household_a)) == [-450, -450, -300]

    one = card("09/01/2026,1,COFFEE,,-4.50", "09/01/2026,3,BAGEL,,-3.00")
    result = pull(household_a, drive, one, stamp="2026-09-02T14:00:00.000Z")
    assert sorted(r.amount_cents for r in provisional_rows(household_a)) == [-450, -300]
    assert "0 new, 2 already held, 1 no longer listed and removed" in result.output


@pytest.mark.django_db
def test_a_re_pull_leaves_another_account_and_a_voice_logged_charge_alone(
    connected, household_a, drive
):
    def provisional(last4, nickname, description, *, pending=False):
        return LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            statement=None,
            account_last4=last4,
            account_nickname=nickname,
            currency="USD",
            txn_date="2026-09-01",
            description=description,
            amount_cents=-999,
            line_ref="voice" if pending else "other.csv:1",
            category_pending=True,
            pending_logged_at="2026-09-01T10:00:00Z" if pending else None,
            provenance=Provenance.UNRECONCILED,
            created_at="2026-09-01T10:00:00Z",
        )

    voice = provisional("7823", "BofA card", "LUNCH (voice-logged)", pending=True)
    other = provisional("4444", "BofA card", "OTHER CARD")
    pull(household_a, drive, card("09/01/2026,1,COFFEE,,-4.50"))

    assert LedgerTransaction.objects.filter(id__in=[voice.id, other.id]).count() == 2
    assert provisional_rows(household_a).count() == 3


# =============================================================================
# Transience, both directions
# =============================================================================


def a_card_statement(**overrides) -> dict:
    """A gated BofA card statement for 7823 covering 09/01 to 09/05, which
    ties out: -1000 - 450 - 6000 = -7450."""
    payload = {
        "content_sha256": "sha-card-statement",
        "provenance": gate.DETERMINISTIC,
        "account_last4": "7823",
        "account_nickname": "BofA card",
        "currency": "USD",
        "stated_total_cents": None,
        "opening_balance_cents": -1000,
        "closing_balance_cents": -7450,
        "period_start": "2026-09-01",
        "period_end": "2026-09-05",
        "lines": [
            {"txn_date": "2026-09-01", "description": "COFFEE SHOP", "amount_cents": -450,
             "line_ref": "stmt:1"},
            {"txn_date": "2026-09-05", "description": "GROCER", "amount_cents": -6000,
             "line_ref": "stmt:2"},
        ],
    }
    payload.update(overrides)
    return payload


@pytest.mark.django_db
def test_a_gated_statement_deletes_the_provisional_rows_in_its_window(
    connected, household_a, drive
):
    pull(household_a, drive, card(
        "09/01/2026,1,COFFEE,,-4.50", "09/05/2026,2,GROCER,,-60.00", "09/08/2026,3,FUEL,,-40.00"
    ))
    assert provisional_rows(household_a).count() == 3

    result = commit_statement(a_card_statement(), household_a)

    assert result.outcome == gate.COMMITTED
    assert result.body["provisional_superseded"] == 2
    # 09/08 is after the statement's last row, so it stays unverified.
    assert [r.description for r in provisional_rows(household_a)] == ["FUEL"]


@pytest.mark.django_db
def test_a_provisional_line_a_verified_statement_already_lists_is_never_written(
    connected, household_a, drive
):
    """The other direction: the statement first, then an activity pull whose
    first days overlap it. Those days are already listed completely by a
    verified statement, so the unverified copies are not written."""
    commit_statement(a_card_statement(), household_a)
    result = pull(household_a, drive, card(
        "09/05/2026,2,GROCER,,-60.00", "09/08/2026,3,FUEL,,-40.00"
    ))

    assert [r.description for r in provisional_rows(household_a)] == ["FUEL"]
    assert (
        "1 new, 0 already held, 0 no longer listed and removed, "
        "1 already on a verified statement"
    ) in result.output


# =============================================================================
# Checking: gated, through the watcher
# =============================================================================


@pytest.mark.django_db
def test_a_checking_closed_period_is_gated_with_its_anchors_persisted(
    connected, household_a, drive
):
    content = (FIXTURES / "bofa_csv_happy_path.csv").read_bytes()
    result = pull(household_a, drive, content, CHECKING_NAME)

    statement = Statement.objects.get(household=household_a)
    assert statement.provenance == Provenance.DETERMINISTIC
    # Rule 8: the two printed balances in their own columns, the absent single
    # total NULL.
    assert statement.stated_total_cents is None
    assert statement.opening_balance_cents == -631
    assert statement.closing_balance_cents == 222061
    assert (statement.account_last4, statement.account_nickname) == ("1000", "BofA checking")
    assert (statement.period_start.isoformat(), statement.period_end.isoformat()) == (
        "2026-07-01", "2026-08-03"
    )
    rows = LedgerTransaction.objects.filter(statement=statement)
    assert rows.count() == 7 == result.rows_written
    assert set(rows.values_list("provenance", flat=True)) == {Provenance.DETERMINISTIC}
    assert not provisional_rows(household_a).exists()


@pytest.mark.django_db
def test_a_checking_csv_that_does_not_tie_out_is_quarantined_with_a_sentence(
    connected, household_a, drive
):
    content = (FIXTURES / "bofa_csv_balance_mismatch.csv").read_bytes()
    pull(household_a, drive, content, CHECKING_NAME)

    stored = IngestedFile.objects.get(display_name=CHECKING_NAME)
    assert stored.state == IngestState.QUARANTINED
    assert stored.quarantine_reason.startswith("bofa-checking-activity-csv: ")
    assert "do not tie out" in stored.quarantine_reason
    assert not LedgerTransaction.objects.exists()
    assert not Statement.objects.exists()


# =============================================================================
# Overlapping downloads (Kevin, 2026-09-28: transaction history only, no
# statement PDFs). Every test here runs the real watcher, parsers, gate and
# rule 7 writer against the database; nothing is mocked but Drive's HTTP.
# =============================================================================


def _money(cents: int) -> str:
    return f'"{cents / 100:,.2f}"'


def checking_csv(
    begin: int, rows: list[tuple[str, str, int]], *, start: str, end: str
) -> bytes:
    """A BofA checking export that ties out: summary block, then the table
    with the running balance. `rows` are (MM/DD/YYYY, description, cents)."""
    credits = sum(a for _, _, a in rows if a > 0)
    debits = sum(a for _, _, a in rows if a < 0)
    ending = begin + credits + debits
    lines = [
        "Description,,Summary Amt.",
        f"Beginning balance as of {start},,{_money(begin)}",
        f"Total credits,,{_money(credits)}",
        f"Total debits,,{_money(debits)}",
        f"Ending balance as of {end},,{_money(ending)}",
        "",
        "Date,Description,Amount,Running Bal.",
        f"{start},Beginning balance as of {start},,{_money(begin)}",
    ]
    balance = begin
    for day, description, amount in rows:
        balance += amount
        lines.append(f'{day},"{description}",{_money(amount)},{_money(balance)}')
    return ("\r\n".join(lines) + "\r\n").encode()


def ledger(household, last4: str):
    """Every row the ledger holds for one account, as (date, description,
    cents, provenance), in a stable order."""
    return sorted(
        (r.txn_date.isoformat(), r.description, r.amount_cents, r.provenance)
        for r in LedgerTransaction.objects.filter(household=household, account_last4=last4)
    )


def as_ledger(rows, provenance):
    return sorted(
        (f"{day[6:10]}-{day[0:2]}-{day[3:5]}", description, amount, provenance)
        for day, description, amount in rows
    )


# Checking 1000. The last statement closed 09/04, so "current" runs from 09/05.
DAY_1 = [
    ("09/05/2026", "COFFEE", -450),
    ("09/05/2026", "COFFEE", -450),  # two genuine coffees: two rows, always
    ("09/08/2026", "EMPLOYER PAYROLL", 240000),
]
DAY_2 = DAY_1 + [("09/10/2026", "GROCER", -6000)]
DAY_2_CLOSING = 10000 + 240000 - 900 - 6000


@pytest.mark.django_db
def test_checking_current_day_1_then_day_2_then_the_closed_period_holds_each_row_once(
    connected, household_a, drive
):
    """The bug this branch closes. Before it, each daily current file was gated
    as its own verified statement: overlapping "statements" piled up one per
    day, and the days between the last pull and the close were listed by none.
    Now current files are provisional and the closed period verifies them."""
    day_1 = checking_csv(10000, DAY_1, start="09/05/2026", end="09/09/2026")
    pull(household_a, drive, day_1, "bofa_1000_activity_2026-09-09.csv",
         stamp="2026-09-09T12:00:00.000Z")
    day_2 = checking_csv(10000, DAY_2, start="09/05/2026", end="09/10/2026")
    result = pull(household_a, drive, day_2, "bofa_1000_activity_2026-09-10.csv",
                  stamp="2026-09-10T12:00:00.000Z")

    # Before the period closes: each row once, none of them verified, no
    # statement on record, and the log says "unverified".
    assert ledger(household_a, "1000") == as_ledger(DAY_2, Provenance.UNRECONCILED)
    assert not Statement.objects.exists()
    assert "unverified: 1 new, 3 already held, 0 no longer listed" in result.output

    # The closed period (09/05 to 09/10) arrives with the same rows. A trailing
    # blank line makes its bytes differ from day 2's file; the content is equal.
    period = checking_csv(10000, DAY_2, start="09/05/2026", end="09/10/2026") + b"\r\n"
    result = pull(household_a, drive, period, "bofa_1000_period_2026-09-10.csv",
                  stamp="2026-09-11T12:00:00.000Z")

    assert ledger(household_a, "1000") == as_ledger(DAY_2, Provenance.DETERMINISTIC)
    statement = Statement.objects.get(household=household_a)
    assert (statement.opening_balance_cents, statement.closing_balance_cents) == (
        10000, DAY_2_CLOSING
    )
    assert statement.stated_total_cents is None
    assert LedgerTransaction.objects.filter(statement=statement).count() == 4
    assert "committed" in result.output


@pytest.mark.django_db
def test_checking_current_file_after_a_closed_period_leaves_the_verified_rows_untouched(
    connected, household_a, drive
):
    period = checking_csv(10000, DAY_2, start="09/05/2026", end="09/10/2026")
    pull(household_a, drive, period, "bofa_1000_period_2026-09-10.csv",
         stamp="2026-09-11T12:00:00.000Z")
    verified = set(
        LedgerTransaction.objects.filter(provenance=Provenance.DETERMINISTIC)
        .values_list("id", flat=True)
    )
    assert len(verified) == 4

    # The next period's window, as the daily current file shows it.
    next_rows = [("09/11/2026", "FUEL", -4000), ("09/12/2026", "RENT", -150000)]
    current = checking_csv(DAY_2_CLOSING, next_rows, start="09/11/2026", end="09/12/2026")
    result = pull(household_a, drive, current, "bofa_1000_activity_2026-09-12.csv",
                  stamp="2026-09-12T12:00:00.000Z")

    expected = sorted(
        as_ledger(DAY_2, Provenance.DETERMINISTIC)
        + as_ledger(next_rows, Provenance.UNRECONCILED)
    )
    assert set(
        LedgerTransaction.objects.filter(provenance=Provenance.DETERMINISTIC)
        .values_list("id", flat=True)
    ) == verified
    assert ledger(household_a, "1000") == expected
    assert Statement.objects.count() == 1
    assert "unverified: 2 new, 0 already held" in result.output

    # A current file BofA produced before it moved the window on still lists
    # the closed days: those are already on a verified statement and are not
    # written again.
    late = checking_csv(10000, DAY_2 + next_rows, start="09/05/2026", end="09/12/2026")
    result = pull(household_a, drive, late, "bofa_1000_activity_2026-09-12 (1).csv",
                  stamp="2026-09-12T18:00:00.000Z")
    assert ledger(household_a, "1000") == expected
    assert set(
        LedgerTransaction.objects.filter(provenance=Provenance.DETERMINISTIC)
        .values_list("id", flat=True)
    ) == verified
    assert "4 already on a verified statement" in result.output


@pytest.mark.django_db
def test_card_current_then_closed_period_then_next_current_holds_each_row_once(
    connected, household_a, drive
):
    """Card 7823, statement closing 09/05 (P). The current file on 09/10 (X)
    covers 09/01 to 09/10, the period file covers 09/01 to 09/05, the next
    day's current file covers 09/06 to 09/11. The card prints no anchor, so all
    of it stays UNRECONCILED, and none of it may be counted twice."""
    current_x = card(
        "09/02/2026,1,COFFEE,,-4.50",
        "09/04/2026,2,BAGEL,,-3.00",
        "09/04/2026,3,BAGEL,,-3.00",
        "09/05/2026,4,REFUND,,12.00",
        "09/09/2026,5,FUEL,,-40.00",
    )
    pull(household_a, drive, current_x, "bofa_7823_activity_2026-09-10.csv",
         stamp="2026-09-10T12:00:00.000Z")

    period = card(
        "09/02/2026,1,COFFEE,,-4.50",
        "09/04/2026,2,BAGEL,,-3.00",
        "09/04/2026,3,BAGEL,,-3.00",
        "09/05/2026,4,REFUND,,12.00",
    )
    result = pull(household_a, drive, period, "bofa_7823_period_2026-09-05.csv",
                  stamp="2026-09-10T18:00:00.000Z")
    assert "unverified: 0 new, 4 already held, 0 no longer listed" in result.output

    next_day = card("09/09/2026,5,FUEL,,-40.00", "09/11/2026,6,GROCER,,-60.00")
    pull(household_a, drive, next_day, "bofa_7823_activity_2026-09-11.csv",
         stamp="2026-09-11T12:00:00.000Z")

    u = Provenance.UNRECONCILED
    assert ledger(household_a, "7823") == sorted([
        ("2026-09-02", "COFFEE", -450, u),
        ("2026-09-04", "BAGEL", -300, u),
        ("2026-09-04", "BAGEL", -300, u),
        ("2026-09-05", "REFUND", 1200, u),
        ("2026-09-09", "FUEL", -4000, u),
        ("2026-09-11", "GROCER", -6000, u),
    ])
    assert not Statement.objects.exists()


@pytest.mark.django_db
@pytest.mark.parametrize(
    "name, content",
    [
        ("bofa_1000_activity_2026-09-10.csv",
         checking_csv(10000, DAY_2, start="09/05/2026", end="09/10/2026")),
        ("bofa_1000_period_2026-09-10.csv",
         checking_csv(10000, DAY_2, start="09/05/2026", end="09/10/2026")),
        ("bofa_7823_period_2026-09-05.csv", card("09/02/2026,1,COFFEE,,-4.50")),
    ],
)
def test_identical_bytes_twice_are_a_no_op(connected, household_a, drive, name, content):
    pull(household_a, drive, content, name, stamp="2026-09-10T12:00:00.000Z")
    before = sorted(LedgerTransaction.objects.values_list("id", flat=True))
    assert before
    statements_before = Statement.objects.count()

    stem = name[: -len(".csv")]
    result = pull(household_a, drive, content, f"{stem} (1).csv",
                  stamp="2026-09-10T13:00:00.000Z")

    assert sorted(LedgerTransaction.objects.values_list("id", flat=True)) == before
    assert Statement.objects.count() == statements_before
    assert result.rows_written == 0
    assert "known" in result.output


@pytest.mark.django_db
def test_a_checking_file_under_bofa_s_own_name_is_quarantined_in_words(
    connected, household_a, drive
):
    content = checking_csv(10000, DAY_1, start="09/05/2026", end="09/09/2026")
    pull(household_a, drive, content, "currentTransaction_1000.csv")
    stored = IngestedFile.objects.get(display_name="currentTransaction_1000.csv")
    assert stored.state == IngestState.QUARANTINED
    assert "which window it covers" in stored.quarantine_reason
    assert not LedgerTransaction.objects.exists()


# =============================================================================
# Routing
# =============================================================================


@pytest.mark.django_db
@pytest.mark.parametrize(
    "content",
    [
        b"Date,Description,Amount\n09/01/2026,COFFEE,-4.50\n",
        b"Date,Description,Amount,Running Bal.\n09/01/2026,COFFEE,-4.50,10.00\n",
    ],
)
def test_an_unrecognised_csv_is_quarantined_by_the_real_registry_never_sent_to_gemini(
    connected, household_a, drive, content
):
    pull(household_a, drive, content, "export.csv")
    stored = IngestedFile.objects.get(display_name="export.csv")
    assert stored.state == IngestState.QUARANTINED
    assert stored.quarantine_reason.startswith(statements.CSV_NOT_RECOGNISED)
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
def test_a_card_export_under_an_unhelpful_name_is_quarantined_not_filed(
    connected, household_a, drive
):
    pull(household_a, drive, card("09/01/2026,1,COFFEE,,-4.50"), "export.csv")
    stored = IngestedFile.objects.get(display_name="export.csv")
    assert stored.state == IngestState.QUARANTINED
    assert "prints no account number" in stored.quarantine_reason
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
def test_a_bad_row_quarantines_the_whole_card_file(connected, household_a, drive):
    pull(household_a, drive, card("09/01/2026,1,COFFEE,,-4.50", "09/02/2026,2,,,-1.00"))
    stored = IngestedFile.objects.get(display_name=CARD_NAME)
    assert stored.state == IngestState.QUARANTINED
    assert "Row 2" in stored.quarantine_reason
    assert not LedgerTransaction.objects.exists()


# =============================================================================
# The writer's own refusals
# =============================================================================


def a_provisional(**overrides) -> dict:
    payload = {
        "content_sha256": "sha-provisional",
        "provenance": Provenance.UNRECONCILED,
        "account_last4": "7823",
        "account_nickname": "BofA card",
        "currency": "USD",
        "lines": [
            {"txn_date": "2026-09-01", "description": "COFFEE", "amount_cents": -450,
             "line_ref": "f.csv:1"},
        ],
    }
    payload.update(overrides)
    return payload


@pytest.mark.django_db
@pytest.mark.parametrize("provenance", [gate.DETERMINISTIC, gate.LLM_RECONCILED, None])
def test_the_writer_is_no_side_door_around_the_gate(household_a, provenance):
    with pytest.raises(gate.GateInputError, match="only UNRECONCILED"):
        commit_provisional(a_provisional(provenance=provenance), household_a)
    assert not LedgerTransaction.objects.exists()
    assert not IngestedFile.objects.exists()


@pytest.mark.django_db
def test_the_writer_says_unverified_in_its_answer(household_a):
    result = commit_provisional(a_provisional(), household_a)
    assert result.outcome == gate.COMMITTED
    assert result.body["verification"] == "unverified"
    assert result.body["note"] == PROVISIONAL_NOTE
    assert "unverified" in PROVISIONAL_NOTE
    again = commit_provisional(a_provisional(), household_a)
    assert again.outcome == gate.ALREADY_COMMITTED
    assert LedgerTransaction.objects.count() == 1


@pytest.mark.django_db
def test_an_empty_provisional_file_is_quarantined(household_a):
    result = commit_provisional(a_provisional(lines=[]), household_a)
    assert result.outcome == gate.QUARANTINED
    assert not LedgerTransaction.objects.exists()


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("override", "match"),
    [
        ({"account_last4": "78"}, "four digits"),
        ({"lines": [{"txn_date": "2026-09-01", "description": "X", "amount_cents": -4.5,
                     "line_ref": "f:1"}]}, "never a float"),
        ({"lines": [{"txn_date": "2026-09-01", "description": " ", "amount_cents": -450,
                     "line_ref": "f:1"}]}, "description"),
    ],
)
def test_a_payload_the_writer_cannot_evaluate_writes_nothing(household_a, override, match):
    with pytest.raises(gate.GateInputError, match=match):
        commit_provisional(a_provisional(**override), household_a)
    assert not LedgerTransaction.objects.exists()


# =============================================================================
# The API says "unverified" in words
# =============================================================================


@pytest.mark.django_db
def test_the_ledger_api_says_unverified_on_a_provisional_row_and_nothing_on_a_gated_one(
    auth_client, household_a
):
    commit_provisional(a_provisional(), household_a)
    commit_statement(a_card_statement(content_sha256="sha-other-card", account_last4="1111"),
                     household_a)

    response = auth_client.get("/api/ledger/transactions/?since=1970-01-01T00:00:00Z")
    assert response.status_code == 200
    by_provenance = {}
    for row in json.loads(response.content)["results"]:
        by_provenance.setdefault(row["provenance"], set()).add(row["verification_note"])
    assert by_provenance[Provenance.UNRECONCILED] == {ROW_NOTE}
    assert ROW_NOTE.startswith("Unverified")
    assert by_provenance[Provenance.DETERMINISTIC] == {None}
