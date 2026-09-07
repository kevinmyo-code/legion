"""`ingest/dedup.py` - the port of `private.ledger_resolve_dedup`.

Pure, so none of this needs a database. Each test names the behaviour the SQL's
own header calls load-bearing, because a port that keeps the counts right and
the credit accounting wrong would pass a lazier test than this one.
"""
from __future__ import annotations

from datetime import date

from ingest.dedup import ExistingRow, normalize_description, resolve_dedup

JULY_6 = date(2026, 7, 6)
JULY_7 = date(2026, 7, 7)


def line(day: date, amount: int, description: str) -> dict:
    return {"txn_date": day, "amount_cents": amount, "description": description}


def test_nothing_committed_yet_means_everything_inserts():
    result = resolve_dedup([line(JULY_6, -450, "COFFEE")], existing=[], windows=[])

    assert result.insert_ordinals == [0]
    assert result.duplicates_skipped == 0
    assert result.restatements_skipped == 0


def test_an_exact_match_is_skipped_with_no_window_needed():
    """Pass one carries NO window condition. A row that matches an existing one
    on date, amount and normalised description is the same row however it got
    here."""
    result = resolve_dedup(
        [line(JULY_6, -450, "COFFEE")],
        existing=[ExistingRow(JULY_6, -450, "COFFEE")],
        windows=[],
    )

    assert result.insert_ordinals == []
    assert result.duplicates_skipped == 1
    assert result.restatements_skipped == 0


def test_a_differently_worded_restatement_inside_a_window_is_skipped():
    """The problem the whole file exists for. BofA words the same transaction two
    ways in two exports:

        'PURCHASE   0706 VPN24.ME EDINBURGH    00'
        'VPN24.ME 07/06 PURCHASE EDINBURGH 00'

    A description-sensitive key catches neither, so the row double-counts, every
    month, by construction.
    """
    result = resolve_dedup(
        [line(JULY_6, -2400, "VPN24.ME 07/06 PURCHASE EDINBURGH 00")],
        existing=[ExistingRow(JULY_6, -2400, "PURCHASE   0706 VPN24.ME EDINBURGH    00")],
        windows=[(JULY_6, JULY_7)],
    )

    assert result.insert_ordinals == []
    assert result.duplicates_skipped == 1
    assert result.restatements_skipped == 1


def test_the_same_pair_outside_a_window_inserts():
    """The narrowing that makes the relaxation safe. Outside a span some other
    committed statement has already enumerated completely, nothing relaxes -
    because no prior statement claims to have listed those rows, and two
    genuinely separate $4.50 coffees on one day must not collapse into one."""
    result = resolve_dedup(
        [line(JULY_6, -2400, "VPN24.ME 07/06 PURCHASE EDINBURGH 00")],
        existing=[ExistingRow(JULY_6, -2400, "PURCHASE   0706 VPN24.ME EDINBURGH    00")],
        windows=[],
    )

    assert result.insert_ordinals == [0]
    assert result.duplicates_skipped == 0
    assert result.restatements_skipped == 0


def test_incoming_is_never_deduplicated_against_itself():
    """Two identical lines in one statement are two genuine purchases, and
    collapsing them is the original bug this algorithm was written to fix. The
    credit pool is built from EXISTING rows only."""
    result = resolve_dedup(
        [line(JULY_6, -450, "COFFEE"), line(JULY_6, -450, "COFFEE")],
        existing=[],
        windows=[(JULY_6, JULY_7)],
    )

    assert result.insert_ordinals == [0, 1]
    assert result.duplicates_skipped == 0


def test_one_committed_row_absorbs_exactly_one_incoming_row():
    """Both passes draw on the SAME pool, so a strict match consumes a loose
    credit too. Without that, one existing row would absorb two incoming rows -
    once strictly, once loosely - and a real transaction would vanish."""
    result = resolve_dedup(
        [line(JULY_6, -450, "COFFEE"), line(JULY_6, -450, "CAFE")],
        existing=[ExistingRow(JULY_6, -450, "COFFEE")],
        windows=[(JULY_6, JULY_7)],
    )

    assert result.duplicates_skipped == 1
    assert len(result.insert_ordinals) == 1


def test_pass_one_runs_completely_before_pass_two():
    """A row that CAN be matched precisely never spends a loose credit some other
    row needs. Fusing the passes changes the answer, which is why the SQL is a
    loop and not a join, and why this test exists at all.

    Fused, ordinal 0 would take the loose credit as a restatement and ordinal 1
    would then take the strict one, inserting nothing. Split, ordinal 1 matches
    strictly, the shared credit is spent, and ordinal 0 survives to insert.
    """
    result = resolve_dedup(
        [line(JULY_6, -450, "CAFE"), line(JULY_6, -450, "COFFEE")],
        existing=[ExistingRow(JULY_6, -450, "COFFEE")],
        windows=[(JULY_6, JULY_7)],
    )

    assert result.insert_ordinals == [0]
    assert result.duplicates_skipped == 1
    assert result.restatements_skipped == 0


def test_description_normalisation_collapses_whitespace_and_case():
    """`upper(btrim(regexp_replace(coalesce(d, ''), '\\s+', ' ', 'g')))`, and a
    null description normalises to the empty string rather than throwing."""
    assert normalize_description("  purchase   0706   vpn24.me  ") == "PURCHASE 0706 VPN24.ME"
    assert normalize_description(None) == ""
    assert normalize_description("\tCOFFEE\n") == "COFFEE"
