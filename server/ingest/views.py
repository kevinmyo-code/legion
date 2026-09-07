"""`POST /api/ingest/statement` and `POST /api/ingest/receipt` - the two commit
paths `public.commit_statement` and `public.commit_receipt` have owned since
2026-08-25, moved into Django per ADR 0044 decision 2 and django-engine ticket
03.

## The contract

**The request body IS the RPC's `payload`, unchanged, and the response body is a
superset of the RPC's return.** That is ticket 03's own wording, and it is what
lets `SupabaseLedgerBackend`/`SupabasePantryBackend`'s DTOs survive into ticket
09 with only the transport swapped: `CommitReceiptResponseDto` declares
`outcome`, `receipt_id`, `inserted`, `reason` and every one of those still means
what it meant.

**Superset, not identical**, for two reasons that are rules rather than taste:

- Section 4 rule 5 wants the macro estimates labelled as estimates in what comes
  back, not only in the column name.
- Section 4 rule 8 wants the anchors the gate checked against to be retrievable.
  They are persisted (that is the load-bearing half), and echoing them here means
  the caller that has just been told COMMITTED can see the three numbers that
  earned it without a second round trip.

The additive keys are safe because every Django-facing client in the Android app
already decodes with `ignoreUnknownKeys = true` (`DjangoEventsBackend`,
`DjangoChecklistsBackend`, `EngineAuth`). Checked, not assumed.

## Extraction is NOT here

The caller supplies the lines. Server-side extraction - accept the photo, run the
model, build the candidate rows - is the eventual shape and is deliberately not
this ticket: it waits on ticket 05 (media, still open and repointed at R2) and on
a ruling nobody has made about where a user-owned server-side LLM key lives.
Opening that fork inside the gate's own port would have made two decisions at
once. Ticket 03's status-detail says the same thing in the same words.

## Why a quarantine is a 200 and not a 4xx

A quarantine is a VERDICT, not a transport failure: the gate ran, the arithmetic
did not tie, and the file is recorded as refused with a reason. `PantryBackend`'s
own doc comment is explicit that it "must never be reported to the user as
'something went wrong' or retried blindly". A 4xx would invite exactly that, and
would put the refusal in the same bucket as a bad token. COMMITTED is 201
because a row was created; ALREADY_COMMITTED and QUARANTINED are 200 because
nothing was.

A payload the gate cannot even evaluate IS a 400 - see `gate.GateInputError` for
why those two are not the same thing.
"""
from __future__ import annotations

import uuid
from datetime import date
from typing import Any

from django.db import DatabaseError, transaction
from django.db.models import Max, Min
from django.db.models.functions import Now
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from ingest import gate
from ingest.dedup import ExistingRow, resolve_dedup
from legacy.enums import IngestState, Provenance
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import LedgerTransaction, Statement
from legacy.models.pantry import Receipt, ReceiptLineItem

# Section 4 rule 5, as a response the caller cannot miss. Named here so both the
# code and `tests/test_ingest_api.py` read the same sentence, and so it can be
# lifted verbatim onto a UI surface rather than paraphrased into something
# weaker.
ESTIMATE_FIELDS = (
    "estimated_calories_kcal",
    "estimated_protein_g",
    "estimated_carbs_g",
    "estimated_fat_g",
)
ESTIMATE_NOTE = (
    "The per-item macros are estimates. A receipt never prints calories, protein, carbs or "
    "fat - these are a model's guess from the product name, they were excluded from the "
    "reconciliation check, and any surface that shows one must say it is an estimate."
)

# The refusal below is CLAUDE.md section 4 rule 7 meeting the schema as it
# actually is, and it is deliberately a refusal rather than a branch.
#
# `commit_statement` carries `if v_provenance <> 'UNRECONCILED' then` around its
# supersession delete, which reads like a live branch and is not one: an
# UNRECONCILED payload that got as far as the header insert would trip
# `statements_not_provisional check (provenance <> 'UNRECONCILED')` and raise,
# rolling back its own quarantine row. `public.statements`' own table comment
# says why the constraint is right: "A provisional (rule 7) import has NO row
# here, which is why ledger_transactions.statement_id is nullable", and
# `ledger_txn_header_matches_provenance` enforces the other half.
#
# `commit_receipt` has the same hole in a different shape: `receipts_not_provisional`
# as narrowed by `20260826000500_receipts_allow_unreconciled.sql` permits
# UNRECONCILED only when `unaccounted_cents is not null`, and that RPC never
# writes that column, so it too cannot mint a provisional row.
#
# So this port refuses an UNRECONCILED payload up front, in words, instead of
# carrying a guard that pretends to be reachable. The provisional path is real
# work and is its own ticket.
PROVISIONAL_REFUSAL = (
    "This endpoint cannot store a provisional (UNRECONCILED) document, and never could: "
    "a provisional import has no header row at all, which is what "
    "statements_not_provisional and receipts_not_provisional enforce. Nothing was written. "
    "A document that states no anchor is quarantined here with a reason; rule 7 provisional "
    "ingestion is .scratch/django-engine/issues/13-provisional-ingestion-has-no-endpoint.md."
)


def _parse_date(value: object, field: str) -> date:
    """`(payload ->> 'x')::date`. A bad date is a `GateInputError`, matching the
    SQL, where a failed cast raises rather than quarantining."""
    if isinstance(value, date):
        return value
    if not isinstance(value, str) or not value.strip():
        raise gate.GateInputError(f"{field} is required and must be an ISO date (YYYY-MM-DD).")
    try:
        return date.fromisoformat(value.strip())
    except ValueError as exc:
        raise gate.GateInputError(
            f"{field} must be an ISO date (YYYY-MM-DD); got {value!r}."
        ) from exc


def _optional_date(value: object, field: str) -> date | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    return _parse_date(value, field)


def _require_sha(payload: dict[str, Any], rpc_name: str) -> str:
    """`if v_sha is null or length(v_sha) = 0 then raise exception '<rpc>:
    content_sha256 is required'`. Same sentence, so a caller that was reading
    the RPC's error text still recognises it."""
    sha = payload.get("content_sha256")
    if not isinstance(sha, str) or not sha:
        raise gate.GateInputError(f"{rpc_name}: content_sha256 is required")
    return sha


def _already_committed(sha: str) -> IngestedFile | None:
    """Step 1 of both RPCs. Idempotency keyed on the content hash.

    This is what makes a lost acknowledgement retryable instead of ambiguous.
    Ticket 03 ruling 8 removed the offline write queue, so a network death after
    the commit but before the ack leaves the phone unable to tell success from
    failure - and `AriaBrain.CANNOT_CLAUSE` is binary, with no vocabulary for
    "unknown". A repeat call being a successful no-op means the phone retries
    until it gets a definite answer rather than narrating a state it cannot
    determine.
    """
    return IngestedFile.objects.filter(content_sha256=sha, state=IngestState.INGESTED).first()


def _upsert_file(payload: dict[str, Any], sha: str, state: str, reason: str | None) -> IngestedFile:
    """`insert into public.ingested_files (...) values (...) on conflict
    (content_sha256) do update set ...`, both RPCs' version and
    `private.quarantine_file`'s, which differ only in the state and the reason.

    A real `ON CONFLICT DO UPDATE` (`bulk_create(update_conflicts=True)`) rather
    than a read-then-write, because a read-then-write has a race the unique index
    would surface as a 500 under two concurrent posts of the same file - and
    "the same file posted twice" is the exact case idempotency exists to make
    boring.

    `ingested_files` carries no `forbid_mutation` trigger (that loop covers only
    statements, ledger_transactions, receipts and receipt_line_items), so
    updating this row is allowed where updating a gated row is not.
    """
    size_bytes = payload.get("size_bytes")
    if isinstance(size_bytes, str):
        size_bytes = int(size_bytes) if size_bytes.strip() else None
    row = IngestedFile(
        id=uuid.uuid4(),
        content_sha256=sha,
        source_file_id=payload.get("source_file_id"),
        display_name=payload.get("display_name"),
        size_bytes=size_bytes,
        state=state,
        quarantine_reason=reason,
        # Postgres's clock for both, never this process's - `api/events.py`'s
        # `create` explains at length why one clock matters, and the same
        # argument applies to any timestamp a later query might order by.
        first_seen_at=Now(),
        last_attempt_at=Now(),
    )
    IngestedFile.objects.bulk_create(
        [row],
        update_conflicts=True,
        update_fields=["state", "quarantine_reason", "last_attempt_at", "source_file_id",
                       "display_name", "size_bytes"],
        unique_fields=["content_sha256"],
    )
    # bulk_create's returned instance holds the id it TRIED to insert, which is
    # not the stored id when the conflict path ran. Read the row back.
    return IngestedFile.objects.get(content_sha256=sha)


def _quarantine(payload: dict[str, Any], sha: str, reason: str) -> Response:
    """`private.quarantine_file`, and note what it writes: ONLY the file row.

    That is the whole point. A quarantined document leaves a reason and no data,
    which is what makes section 4 rule 2's "nothing partial is ever written"
    true and still lets the app explain itself. The prohibition is on partial
    DATA, never on recording that a document was rejected - which is also why a
    gate failure is a return value here and not an exception: an exception would
    roll back the quarantine record too, and the file would come back on the
    next scan with no memory of why it failed, forever.
    """
    _upsert_file(payload, sha, IngestState.QUARANTINED, reason)
    return Response(
        {"outcome": gate.QUARANTINED, "reason": reason, "inserted": 0},
        status=status.HTTP_200_OK,
    )


class _IngestView(APIView):
    """Shared plumbing: authentication is the project default
    (`DeviceTokenAuthentication` + `IsHouseholdMember`, `REST_FRAMEWORK` in
    settings), so the endpoint requires a household token.

    `security invoker` has no analogue here, which ticket 03 says in as many
    words. What makes a committed row immutable is the `forbid_mutation` trigger
    on the four gated tables, not this view - and that is deliberate: the trigger
    holds even when Django has a bug, which is exactly the test ADR 0044
    decision 1 uses to decide what stays in SQL.
    """

    def _payload(self, request) -> dict[str, Any]:
        payload = request.data
        if not isinstance(payload, dict):
            raise gate.GateInputError("The request body must be a JSON object.")
        return payload


class StatementIngestView(_IngestView):
    """`public.commit_statement(payload jsonb)`."""

    def post(self, request):
        try:
            payload = self._payload(request)
            with transaction.atomic():
                return self._commit(payload)
        except gate.GateInputError as exc:
            # Not a quarantine: the gate could not run at all. See
            # GateInputError's own docstring for why collapsing the two would
            # let a malformed payload pass for a document that failed its
            # arithmetic.
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)
        except DatabaseError as exc:
            # A CHECK constraint or trigger refusal that got past this view's own
            # checks - `statements_currency_check`, `statements_account_last4_check`,
            # a NOT NULL on a line the payload left out. Reported with the
            # database's own words rather than as a 500, the same posture
            # `api/sync.save_or_400` takes. The `atomic()` block above has
            # already rolled everything back, so nothing partial survives.
            return Response({"detail": str(exc).strip()}, status=status.HTTP_400_BAD_REQUEST)

    def _commit(self, payload: dict[str, Any]) -> Response:
        sha = _require_sha(payload, "commit_statement")

        existing = _already_committed(sha)
        if existing is not None:
            return Response(
                {
                    "outcome": gate.ALREADY_COMMITTED,
                    "content_sha256": sha,
                    "inserted": 0,
                    "note": "This file was already committed. Nothing was written again.",
                },
                status=status.HTTP_200_OK,
            )

        provenance = payload.get("provenance")
        if not isinstance(provenance, str) or provenance not in Provenance.values:
            raise gate.GateInputError(
                f"provenance must be one of: {', '.join(Provenance.values)}."
            )
        if provenance == Provenance.UNRECONCILED:
            raise gate.GateInputError(PROVISIONAL_REFUSAL)

        raw_lines = payload.get("lines") or []
        if not isinstance(raw_lines, list):
            raise gate.GateInputError("lines must be a JSON array.")

        stated_total = gate.optional_cents(payload.get("stated_total_cents"), "stated_total_cents")
        opening = gate.cents(payload.get("opening_balance_cents"), "opening_balance_cents")
        closing = gate.cents(payload.get("closing_balance_cents"), "closing_balance_cents")

        verdict = gate.check_statement(
            lines=raw_lines,
            stated_total_cents=stated_total,
            opening_balance_cents=opening,
            closing_balance_cents=closing,
            provenance=provenance,
        )
        if not verdict.committed:
            return _quarantine(payload, sha, verdict.reason or "")

        # Gate passed. Only now does anything but the file row get written.
        lines = [
            {
                "txn_date": _parse_date(line.get("txn_date"), "txn_date"),
                "description": line.get("description"),
                "amount_cents": gate.cents(line.get("amount_cents"), "amount_cents"),
                "balance_cents": gate.optional_cents(line.get("balance_cents"), "balance_cents"),
                "line_ref": line.get("line_ref"),
                "category": line.get("category"),
            }
            for line in raw_lines
        ]
        min_date = min(line["txn_date"] for line in lines)
        max_date = max(line["txn_date"] for line in lines)

        last4 = payload.get("account_last4")
        nickname = payload.get("account_nickname")
        currency = payload.get("currency")

        file_row = _upsert_file(payload, sha, IngestState.INGESTED, None)

        statement = Statement.objects.create(
            id=uuid.uuid4(),
            ingested_file=file_row,
            account_last4=last4,
            account_nickname=nickname,
            currency=currency,
            # `coalesce((payload ->> 'period_start')::date, v_min_date)`
            period_start=_optional_date(payload.get("period_start"), "period_start") or min_date,
            period_end=_optional_date(payload.get("period_end"), "period_end") or max_date,
            # RULE 8. The three anchors are stored in their own columns beside
            # the rows they gated, and `stated_total_cents` stays NULL when the
            # bank printed none - never synthesised from sum(lines), which would
            # make the check an identity.
            stated_total_cents=stated_total,
            opening_balance_cents=opening,
            closing_balance_cents=closing,
            provenance=provenance,
            created_at=Now(),
        )

        # RULE 7 SUPERSESSION, and it must happen BEFORE the dedup read.
        #
        # Three load-bearing properties carried over verbatim from the SQL and
        # from `IngestPipeline.commit` before it:
        #   (a) a provisional file never supersedes anything. The SQL spells this
        #       as a guard on the incoming provenance; here it is the up-front
        #       PROVISIONAL_REFUSAL above, which is stronger and honest about
        #       being unreachable.
        #   (b) BEFORE the dedup read. If dedup ran first, the incoming verified
        #       rows would be discarded as duplicates of the very provisional
        #       rows they are meant to replace, and the window would end up with
        #       neither.
        #   (c) inside this transaction, which it is by construction.
        #
        # The delete itself is live and stays: provisional rows CAN exist (the
        # phone's voice-logged pending charge writes them, headerless), and a
        # verified statement covering the same card and window must supersede
        # them. `sameCard` becomes the last-four match, whose known weakness
        # carries over unchanged - two accounts sharing a last four collide,
        # which is what the nickname is for elsewhere.
        superseded, _ = LedgerTransaction.objects.filter(
            provenance=Provenance.UNRECONCILED,
            account_last4=last4,
            txn_date__gte=min_date,
            txn_date__lte=max_date,
        ).delete()

        dedup = resolve_dedup(
            lines,
            self._credit_pool(last4, nickname, min_date, max_date),
            self._enumerated_windows(last4, nickname, statement.id, min_date, max_date),
        )

        LedgerTransaction.objects.bulk_create(
            [
                LedgerTransaction(
                    id=uuid.uuid4(),
                    statement=statement,
                    account_last4=last4,
                    account_nickname=nickname,
                    currency=currency,
                    txn_date=lines[ordinal]["txn_date"],
                    description=lines[ordinal]["description"],
                    amount_cents=lines[ordinal]["amount_cents"],
                    balance_cents=lines[ordinal]["balance_cents"],
                    line_ref=lines[ordinal]["line_ref"],
                    category=lines[ordinal]["category"],
                    category_pending=lines[ordinal]["category"] is None,
                    provenance=provenance,
                    created_at=Now(),
                )
                for ordinal in dedup.insert_ordinals
            ]
        )

        return Response(
            {
                "outcome": gate.COMMITTED,
                "statement_id": str(statement.id),
                "inserted": len(dedup.insert_ordinals),
                "duplicates_skipped": dedup.duplicates_skipped,
                "restatements_skipped": dedup.restatements_skipped,
                "provisional_superseded": superseded,
                # Rule 8, echoed. The authority is the stored row; this is so a
                # caller that has just been told COMMITTED can see the numbers
                # that earned it without a second round trip.
                "anchors": {
                    "stated_total_cents": stated_total,
                    "opening_balance_cents": opening,
                    "closing_balance_cents": closing,
                    "note": (
                        "These are the figures the gate checked against, stored on "
                        "public.statements beside the rows they gated. A null stated total "
                        "means the bank printed none; it is never derived from the lines."
                    ),
                },
            },
            status=status.HTTP_201_CREATED,
        )

    def _credit_pool(
        self, last4: object, nickname: object, from_date: date, to_date: date
    ) -> list[ExistingRow]:
        """`for rec in select ... from public.ledger_transactions where
        account_last4 = ... and account_nickname = ... and txn_date between ...
        and reversal_of is null`.

        Every filter is load-bearing and kept. Without the date scoping a
        far-future row with the same key would absorb an incoming one.
        """
        rows = LedgerTransaction.objects.filter(
            account_last4=last4,
            account_nickname=nickname,
            txn_date__gte=from_date,
            txn_date__lte=to_date,
            reversal_of__isnull=True,
        ).values_list("txn_date", "amount_cents", "description")
        return [ExistingRow(txn_date=d, amount_cents=a, description=desc) for d, a, desc in rows]

    def _enumerated_windows(
        self, last4: object, nickname: object, exclude_id, from_date: date, to_date: date
    ) -> list[tuple[date, date]]:
        """`private.ledger_enumerated_windows`: spans another committed statement
        already listed completely.

        **Bounds are the statement's ACTUAL first and last transaction dates, not
        its printed period.** The Kotlin is explicit about this: a printed period
        can run past the last transaction, and claiming completeness over that
        tail would be unsupported. Derived rather than stored, so it cannot drift
        from the rows themselves.

        Excluding this statement matters so a re-import cannot treat its own
        prior window as someone else's testimony.
        """
        rows = (
            LedgerTransaction.objects.filter(
                statement__account_last4=last4,
                statement__account_nickname=nickname,
            )
            .exclude(statement_id=exclude_id)
            .values("statement_id")
            .annotate(win_from=Min("txn_date"), win_to=Max("txn_date"))
            .filter(win_from__lte=to_date, win_to__gte=from_date)
        )
        return [(row["win_from"], row["win_to"]) for row in rows]


class ReceiptIngestView(_IngestView):
    """`public.commit_receipt(payload jsonb)`."""

    def post(self, request):
        try:
            payload = self._payload(request)
            with transaction.atomic():
                return self._commit(payload)
        except gate.GateInputError as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)
        except DatabaseError as exc:
            return Response({"detail": str(exc).strip()}, status=status.HTTP_400_BAD_REQUEST)

    def _commit(self, payload: dict[str, Any]) -> Response:
        sha = _require_sha(payload, "commit_receipt")

        existing = _already_committed(sha)
        if existing is not None:
            return Response(
                {
                    "outcome": gate.ALREADY_COMMITTED,
                    "content_sha256": sha,
                    "inserted": 0,
                    "note": "This receipt was already committed. Nothing was written again.",
                },
                status=status.HTTP_200_OK,
            )

        # `coalesce((nullif(payload ->> 'provenance', ''))::public.provenance,
        # 'LLM_RECONCILED')`. Pantry is LLM_RECONCILED by construction, never
        # DETERMINISTIC: a receipt is photographed rather than born-digital, so
        # there is no deterministic extraction path to prefer (section 4 rule 1).
        # That is a necessity, not a preference.
        provenance = payload.get("provenance") or Provenance.LLM_RECONCILED
        if provenance not in Provenance.values:
            raise gate.GateInputError(
                f"provenance must be one of: {', '.join(Provenance.values)}."
            )
        if provenance == Provenance.UNRECONCILED:
            raise gate.GateInputError(PROVISIONAL_REFUSAL)

        raw_items = payload.get("items") or []
        if not isinstance(raw_items, list):
            raise gate.GateInputError("items must be a JSON array.")

        total = gate.cents(payload.get("total_cents"), "total_cents")
        subtotal = gate.optional_cents(payload.get("subtotal_cents"), "subtotal_cents")
        tax = gate.optional_cents(payload.get("tax_cents"), "tax_cents")
        other = gate.optional_cents(payload.get("other_charges_cents"), "other_charges_cents")

        verdict = gate.check_receipt(
            items=raw_items,
            total_cents=total,
            subtotal_cents=subtotal,
            tax_cents=tax,
            other_charges_cents=other,
        )
        if not verdict.committed:
            return _quarantine(payload, sha, verdict.reason or "")

        file_row = _upsert_file(payload, sha, IngestState.INGESTED, None)

        receipt = Receipt.objects.create(
            id=uuid.uuid4(),
            ingested_file=file_row,
            store=payload.get("store"),
            purchase_date=_parse_date(payload.get("purchase_date"), "purchase_date"),
            currency=payload.get("currency"),
            # RULE 8 again, and note what is NOT done here: an absent tax is
            # stored NULL, not the 0 the arithmetic used to add it up. The gate
            # coalesces to add; the storage keeps "the receipt printed none".
            total_cents=total,
            subtotal_cents=subtotal,
            tax_cents=tax,
            other_charges_cents=other,
            photo_object_path=payload.get("photo_object_path"),
            provenance=provenance,
            created_at=Now(),
        )

        # No dedup pass here, and that is a real difference from the ledger
        # rather than an omission. A receipt is one physical document
        # photographed once; there is no equivalent of a bank restating a
        # period, and two identical line items on one receipt (two of the same
        # tin) are genuinely two rows. Idempotency on content_sha256 already
        # stops the same photo committing twice, which is the only duplication
        # that can actually occur here.
        ReceiptLineItem.objects.bulk_create(
            [
                ReceiptLineItem(
                    id=uuid.uuid4(),
                    receipt=receipt,
                    name=item.get("name"),
                    quantity=item.get("quantity"),
                    unit_price_cents=gate.optional_cents(
                        item.get("unit_price_cents"), "unit_price_cents"
                    ),
                    total_price_cents=gate.cents(
                        item.get("total_price_cents"), "total_price_cents"
                    ),
                    # The four estimates are carried through untouched and were
                    # never part of the arithmetic above - section 4 rule 5.
                    estimated_calories_kcal=item.get("estimated_calories_kcal"),
                    estimated_protein_g=item.get("estimated_protein_g"),
                    estimated_carbs_g=item.get("estimated_carbs_g"),
                    estimated_fat_g=item.get("estimated_fat_g"),
                    provenance=provenance,
                    created_at=Now(),
                )
                for item in raw_items
            ]
        )

        estimated_items = sum(
            1 for item in raw_items if any(item.get(f) is not None for f in ESTIMATE_FIELDS)
        )
        return Response(
            {
                "outcome": gate.COMMITTED,
                "receipt_id": str(receipt.id),
                "inserted": len(raw_items),
                "anchors": {
                    "total_cents": total,
                    "subtotal_cents": subtotal,
                    "tax_cents": tax,
                    "other_charges_cents": other,
                    "note": (
                        "These are the figures the gate checked against, stored on "
                        "public.receipts beside the lines they gated. A null subtotal, tax or "
                        "other charge means the receipt printed none; none of them is derived "
                        "from the lines."
                    ),
                },
                "estimates": {
                    "fields": list(ESTIMATE_FIELDS),
                    "items_carrying_an_estimate": estimated_items,
                    "note": ESTIMATE_NOTE,
                },
            },
            status=status.HTTP_201_CREATED,
        )
