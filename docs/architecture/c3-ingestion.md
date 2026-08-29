---
title: C3 Ingestion
level: c3
tags: [architecture]
verified: 2026-08-29
---

# C3: Ingestion and the gate

**AMENDED 2026-08-29, backend-erp ticket 25 ("statement ingestion leaves the phone entirely").**
This doc used to cover two aspects ingesting documents on the phone - ledger (bank statements) and
pantry (photographed grocery receipts). Kevin ruled the phone never ingests a bank statement any
more: *"kill ledger ingestion from phone. just keep pantry."* A statement PDF is already on the
laptop; a receipt needs a camera the laptop does not have. Only **pantry** ingests on the phone now.

The rule is still [[0006-reconciliation-gate]]. Everything below is that rule made concrete for the
one aspect left that runs it on-device.

## What used to be here, and where it went

the old folder scanner, the five statement parsers plus `StatementDispatcher`,
the LLM statement fallback, `LedgerFolderPreferences`, `LedgerIngestService`, and
`ledger/IngestPipeline.kt`'s `stage`/`commit` machinery are all deleted. Bank statements are
ingested by the web app now, against `public.commit_statement` - a Postgres RPC implementing the
SAME rules 1-7 below in SQL, run against a CSV the user's own LLM produces (masked, then uploaded).
CLAUDE.md §4's amendment on this is the source of truth for that side; this doc no longer tracks it
because there is nothing left on the phone to diagram.

`ledger/IngestPipeline.kt` still exists as a single shared utility (`sha256`), because pantry's own
receipt hashing happens to want the exact same primitive - see that file's own doc comment.

## The gate, stated exactly (still binding, pantry included)

1. **Deterministic first** where a deterministic path exists. Pantry has none - a receipt is
   photographed, not born-digital - so LLM vision is primary there by necessity.
2. **Rows must reconcile against the document's own stated total, exactly.** Not within a tolerance.
   Sum equals printed total, or the whole document quarantines. Nothing partial is ever written.
3. **Money is `Long` cents.** [[0007-money-as-long-cents]]. The equality in rule 2 is why.
4. **Every row is tagged** `DETERMINISTIC` or `LLM_RECONCILED`.
5. **Anything the document does not state is an estimate**, excluded from the check and labelled.
   [[0008-estimates-are-not-facts]].
6. **A check that passes when nothing parsed is not a gate.** Inside a recognised section, every
   line that is not the section's own total must parse, or the document quarantines.
7. **A source with no anchor may be stored provisionally, never as fact.**
   [[0009-provisional-unreconciled-tier]]. (Pantry has never needed this rule - a receipt not
   printing a subtotal still prints a total; ledger's mid-cycle card CSV was the only source that
   needed it, and it left with the rest of ledger ingestion.)

Rule 6 was learned the hard way, on the ledger side that is now gone: BofA's card statement printed
interest rows in a different shape, all four silently failed to match, and the section check
reconciled zero parsed rows against a printed $0.00 and passed. It held only because interest
happened to be zero that month. The rule survives here because pantry's own line-item sum is
exactly the same shape of check, and the same blind spot is possible in principle.

## Pantry

Pantry has **no deterministic path at all**. Receipts are photographed, not born-digital, so there
is no layout to recognise. `pantry/PantryReceiptAgent.kt` runs LLM vision as the primary and only
extractor, via `ai/SubAgent.kt`'s inline image part.

That is a necessity, not a preference, and the gate still applies unchanged: line items must sum to
the receipt's printed total or the receipt quarantines.

The macros are the interesting part. Calories, protein, carbs and fat are model guesses from a
product name. **A receipt has never printed any of them**, so they cannot be gated even in
principle. They are excluded from the check, labelled estimates in the tool description and in every
string, and since 2026-08-02 they are physically segregated into a block headed `ESTIMATED, NOT ON
THE RECEIPT`.

## Adding a new ingestion path

The feature checklist in CLAUDE.md §7 is binding. In short: wire the gate, quarantine on mismatch,
tag provenance, use `Long` cents, label anything the source did not state, and make sure your check
cannot pass on an empty extraction.

If your source states no anchor at all, you are in [[0009-provisional-unreconciled-tier]] territory
and all four of its conditions apply together.

**Remember the phone-only scope, though:** if the new source is a document rather than a photo -
anything that could equally well sit on a laptop - the phone is very likely the wrong place for it
per Kevin's ruling above. Ask before building a second phone-side document importer.

## Related

[[c2-containers]] for the container map (`LedgerIngestService` is gone from it). [[adr-index]] for
the full decision set.
