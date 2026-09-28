"""The rule 7 provisional write path (backend-etl ticket 09, django-engine
ticket 13 resolved to option 2).

CLAUDE.md section 4 rule 7: a source that states NO anchor may be stored
PROVISIONALLY, never as fact, on four conditions that bind together:

1. **Deterministic extraction.** The only caller is `drive_statements`, and the
   only producer of a provisional result is `ingest/parsers/bofa_activity.py`,
   a CSV reader. A CSV is never sent to Gemini. This function also refuses any
   payload whose provenance is not `UNRECONCILED`, so it cannot become a side
   door around the gate.
2. **Every row `UNRECONCILED`, with no statement header.** The schema demands it
   (`ledger_txn_header_matches_provenance`), and nothing here writes a
   `public.statements` row.
3. **Said in words on every surface.** The body this returns carries
   `verification: "unverified"` and a sentence; the ledger API adds
   `verification_note` to every UNRECONCILED row (`api/ledger.py`).
4. **Transient.** When a gated statement commits over the same account and
   dates, `commit_statement` deletes these rows (built before this file:
   `test_a_gated_statement_supersedes_provisional_rows_in_its_window`). The
   other direction is here: a line dated inside a window some verified
   statement has already listed completely is not written at all, so a
   provisional row can never double-count against a verified one, whichever
   arrives first.

## A re-pull replaces, never duplicates

Ticket 09 pulls each account's activity every day, and each day's file repeats
everything since the last statement. A file with new bytes is a new file to
`ingested_files`, so without this every row would be written again daily.

So within the new file's date window, for this account, the existing
provisional rows and the file's lines are matched as MULTISETS on
`(txn_date, amount_cents, description)`: the k-th identical line in the file
matches the k-th identical row stored ("position among same-day duplicates",
ticket 09). Matched rows are kept as they are (the trigger forbids UPDATE
anyway), stored rows the new file no longer lists are deleted (a pending charge
that dropped off, a reversal), and lines with no stored match are inserted.
Two genuine $4.50 coffees on one day stay two rows, and the third pull of the
same day still has two.

Voice-logged pending charges (`pending_logged_at` set, the phone's
`logPendingTransaction`) are also UNRECONCILED and headerless, and are left
alone: they are not a previous pull of this file.

**`forbid_mutation_of_facts` permits exactly this**: DELETE only on
UNRECONCILED rows, UPDATE never.
"""
from __future__ import annotations

import uuid
from collections import Counter, defaultdict
from datetime import date
from typing import Any

from django.db import transaction
from django.db.models.functions import Now
from rest_framework import status

from ingest import gate
from legacy.enums import IngestState, Provenance
from legacy.models.ledger import LedgerTransaction

UNVERIFIED = "unverified"

PROVISIONAL_NOTE = (
    "These rows are unverified. The file states no balance or total to check them against, "
    "so they were stored as provisional (UNRECONCILED) and are never a verified figure. "
    "They are replaced when the account's statement for these dates passes the gate."
)
ROW_NOTE = (
    "Unverified: from a bank activity export that states no balance or total to check it "
    "against. It is replaced when the statement for this date passes the gate."
)
EMPTY_PROVISIONAL_REASON = (
    "No transactions were read from this file. An empty file is never a successful import "
    "of nothing. Nothing was written."
)


def commit_provisional(payload: dict[str, Any], household):
    """The rule 7 writer `drive_statements` calls in-process
    (`statements.register_provisional_writer`). Returns a `CommitResult`.

    One transaction: a payload this cannot evaluate raises
    `gate.GateInputError` with everything rolled back, like the gate does."""
    with transaction.atomic():
        return _commit(payload, household)


def _key(txn_date: date, amount_cents: int, description: str) -> tuple[date, int, str]:
    return (txn_date, amount_cents, description)


def _commit(payload: dict[str, Any], household):
    # Imported here: views imports statements, and statements must not import
    # this module back at load time.
    from ingest.views import (
        CommitResult,
        StatementIngestView,
        _already_committed,
        _parse_date,
        _quarantine,
        _require_sha,
        _upsert_file,
    )

    sha = _require_sha(payload, "commit_provisional")
    if _already_committed(sha, household) is not None:
        return CommitResult(
            {
                "outcome": gate.ALREADY_COMMITTED,
                "content_sha256": sha,
                "inserted": 0,
                "note": "This file was already read. Nothing was written again.",
            },
            status.HTTP_200_OK,
        )

    if payload.get("provenance") != Provenance.UNRECONCILED:
        raise gate.GateInputError(
            "The provisional path takes only UNRECONCILED rows. A document with anchors goes "
            "through the gate. Nothing was written."
        )

    last4 = payload.get("account_last4")
    nickname = payload.get("account_nickname")
    currency = payload.get("currency")
    if not (isinstance(last4, str) and len(last4) == 4 and last4.isdigit()):
        raise gate.GateInputError("account_last4 must be exactly four digits.")
    if not isinstance(nickname, str) or not nickname.strip():
        raise gate.GateInputError("account_nickname is required.")
    if not isinstance(currency, str) or not currency:
        raise gate.GateInputError("currency is required.")

    raw_lines = payload.get("lines")
    if not isinstance(raw_lines, list) or not all(isinstance(x, dict) for x in raw_lines):
        raise gate.GateInputError("lines must be a JSON array of objects.")
    if not raw_lines:
        # Rule 6: the parser refuses an empty file first; this is the backstop.
        return _quarantine(payload, sha, EMPTY_PROVISIONAL_REASON, household)

    lines = []
    for line in raw_lines:
        description = line.get("description")
        if not isinstance(description, str) or not description.strip():
            raise gate.GateInputError("Every line needs a description.")
        lines.append(
            {
                "txn_date": _parse_date(line.get("txn_date"), "txn_date"),
                "description": description.strip(),
                "amount_cents": gate.cents(line.get("amount_cents"), "amount_cents"),
                "line_ref": line.get("line_ref"),
            }
        )
        if not isinstance(lines[-1]["line_ref"], str) or not lines[-1]["line_ref"]:
            raise gate.GateInputError("Every line needs a line_ref.")

    file_from = min(line["txn_date"] for line in lines)
    file_to = max(line["txn_date"] for line in lines)

    # Condition 4, the direction `commit_statement` cannot cover: dates a
    # verified statement has already listed completely. Same windows dedup
    # uses (actual first and last row dates, never the printed period).
    windows = StatementIngestView._enumerated_windows(
        last4, nickname, None, file_from, file_to, household
    )

    def verified(day: date) -> bool:
        return any(start <= day <= end for start, end in windows)

    fresh = [line for line in lines if not verified(line["txn_date"])]
    covered = len(lines) - len(fresh)

    kept = removed = 0
    to_insert = []
    if fresh:
        window_from = min(line["txn_date"] for line in fresh)
        window_to = max(line["txn_date"] for line in fresh)
        stored = LedgerTransaction.objects.filter(
            household=household,
            provenance=Provenance.UNRECONCILED,
            statement__isnull=True,
            pending_logged_at__isnull=True,
            account_last4=last4,
            account_nickname=nickname,
            txn_date__gte=window_from,
            txn_date__lte=window_to,
        ).order_by("txn_date", "created_at", "line_ref", "id")

        by_key: dict[tuple, list] = defaultdict(list)
        for row in stored:
            by_key[_key(row.txn_date, row.amount_cents, row.description)].append(row.id)
        wanted = Counter(
            _key(line["txn_date"], line["amount_cents"], line["description"]) for line in fresh
        )

        stale_ids = []
        for key, ids in by_key.items():
            keep = min(len(ids), wanted.get(key, 0))
            kept += keep
            stale_ids.extend(ids[keep:])
        seen: Counter = Counter()
        for line in fresh:
            key = _key(line["txn_date"], line["amount_cents"], line["description"])
            seen[key] += 1
            if seen[key] > len(by_key.get(key, [])):
                to_insert.append(line)
        if stale_ids:
            removed, _ = LedgerTransaction.objects.filter(id__in=stale_ids).delete()

    _upsert_file(payload, sha, IngestState.INGESTED, None, household)
    LedgerTransaction.objects.bulk_create(
        [
            LedgerTransaction(
                id=uuid.uuid4(),
                household=household,
                statement=None,
                account_last4=last4,
                account_nickname=nickname,
                currency=currency,
                txn_date=line["txn_date"],
                description=line["description"],
                amount_cents=line["amount_cents"],
                balance_cents=None,
                line_ref=line["line_ref"],
                category=None,
                category_pending=True,
                provenance=Provenance.UNRECONCILED,
                created_at=Now(),
            )
            for line in to_insert
        ]
    )

    return CommitResult(
        {
            "outcome": gate.COMMITTED,
            "provisional": True,
            "verification": UNVERIFIED,
            "inserted": len(to_insert),
            "kept": kept,
            "removed": removed,
            "covered_by_statement": covered,
            "pending_left_out": int(payload.get("pending_left_out", 0) or 0),
            "note": PROVISIONAL_NOTE,
        },
        status.HTTP_201_CREATED,
    )
