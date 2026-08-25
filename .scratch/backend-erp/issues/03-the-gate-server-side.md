---
map: backend-erp
ticket: "03"
title: "The reconciliation gate when the truth lives remote"
type: grilling
status: resolved
status-detail: "One atomic idempotent RPC; gate server-side; statement parsers retired for an LLM-produced CSV with three anchors"
blockers: ["01"]
blocked-by: ["[[01-what-the-backend-owns]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The reconciliation gate when the truth lives remote

## Question

Ingestion (statements, receipts) runs on the phone today - parsers, vision, the gate, then an
atomic multi-row commit. Decide: does the atomic file-commit become a Postgres transaction via a
single RPC (recommend: a Supabase RPC per file commit, preserving nothing-partial exactly), or
does the phone commit locally and sync? Provenance column survives verbatim. Rule-7 supersession
runs inside the same transaction. What a mid-commit network failure reports (worded, per the
outcome-verb rule). Whether UNRECONCILED transience needs server-side enforcement.

## Resolution (2026-08-25) - eight rulings

1. **The file commit becomes ONE atomic Supabase RPC (Kevin, 2026-08-25).** Ruling 8 (direct write,
   no offline queue) had already killed "commit locally and sync". PostgREST runs every request in
   exactly one transaction and cannot hold one open across HTTP requests
   (`research/06-supabase-feasibility.md` §3), so a `commit_statement(jsonb)` function that inserts
   every row, checks the anchors in SQL and `RAISE`s on mismatch preserves "nothing partial"
   exactly as `db.withTransaction` does today.
2. **The gate arithmetic runs SERVER-SIDE in the RPC; the phone pre-checks (Kevin, 2026-08-25).**
   Server-side is authoritative and unbypassable by any consumer, which is ruling 6's argument
   applied to arithmetic rather than to schema. The phone keeps its own check as a fast local fail
   so a bad extraction costs no round trip. **Accepted cost: two implementations of the same
   arithmetic that must not drift** - they need a shared test corpus, and that corpus is the
   deliverable that makes this ruling safe rather than the SQL itself.
3. **The deterministic statement parsers are RETIRED. Statements arrive as a CSV in a format LEGION
   defines, produced by the user's OWN LLM (Kevin, 2026-08-25).** Kevin's own proposal, and it
   dissolves three problems at once: PdfBox cannot run in Deno, per-bank parsers only ever covered
   DBS and BofA (every other bank already fell through to the LLM path), and **the user's LLM masks
   sensitive data before anything reaches a cloud Postgres** - which matters far more now that the
   truth is remote than it did when everything was on-device. A stranger cloning the repo tells
   their own LLM to follow the same format. **This AMENDS CLAUDE.md §4 rule 1** ("deterministic
   first where a deterministic path exists") for the ledger statement path: there is no longer a
   deterministic extraction path for statements, by choice. Rules 2-7 are untouched and still bind.
4. **The format requires THREE independent anchors (Kevin, 2026-08-25).** The risk ruling 3 creates
   is precise: today's gate reconciles parser-extracted lines against a total read by that same
   deterministic parser, so an error is mechanical. An LLM-produced CSV has lines AND total from one
   nondeterministic process, so a self-consistent hallucination could satisfy a single-anchor check
   - the §4 rule 6 failure shape, in a new place. The format therefore demands the **printed total,
   the opening balance and the closing balance**, and the RPC checks `sum(lines) == stated total`
   AND `closing - opening == sum(lines)`. An LLM must now be consistently wrong across three
   separately printed numbers. **A statement that does not print all three cannot use the format**
   and falls to rule 7 provisional instead.
5. **Account identity is LAST FOUR DIGITS PLUS A NICKNAME, both declared in the CSV (Kevin,
   2026-08-25).** Masking would otherwise destroy `accountId`, which rule-7 supersession windows and
   per-account balances key on. Last-4 is stable across statements and not sensitive alone; the
   nickname makes every surface readable. **This fits what already exists**: `sameCard`
   (`ledger/LedgerAccountIdentity.kt:32-33`) already matches on exact equality OR last-4 suffix,
   precisely because the card CSV parser stores `"7823"` while the PDF parser stores the full PAN.
   Its known weakness carries over unchanged and must be said out loud: **two accounts sharing a
   last-4 collide.** The nickname is what disambiguates them, so it is load-bearing, not cosmetic.
6. **Rows from the CSV are tagged `LLM_RECONCILED` (Kevin, 2026-08-25).** Provenance describes the
   data's origin, not the last step that touched it. LEGION's CSV parse is deterministic but an LLM
   sat in between, so `DETERMINISTIC` would overclaim. Reuses an existing enum value, so no schema
   change and every rendering surface and trust tier already handles it.
7. **Rule 7 supersession runs INSIDE the same RPC (Kevin, 2026-08-25).** Same Postgres transaction
   as the inserts, exactly as it sits inside `db.withTransaction` today
   (`ledger/IngestPipeline.kt:368-384`). The three load-bearing properties written at `:353-367`
   carry over verbatim and must be preserved in SQL: the UNRECONCILED guard so a provisional file
   never supersedes anything including its own re-import; **before** the dedup read, or the verified
   rows get dropped as duplicates of the provisional ones they replace; and inside the transaction.
   `sameCard`'s suffix relation becomes a SQL predicate - a third definition of the same relation,
   and `LedgerAccountIdentity.kt:15-24` is explicit that it must never be folded into `dedupKey`.
8. **The RPC is IDEMPOTENT, keyed on the file's content hash (Kevin, 2026-08-25).** This closes the
   gap ruling 8 opened: with no local queue, a network death after the Postgres commit but before
   the ack leaves the phone unable to distinguish success from failure. A server-side
   `ingested_files` row keyed on `contentSha256` makes a repeat call a **successful no-op** rather
   than a second import, so the phone simply retries until it gets a definitive answer.

   **This is what keeps the outcome-verb rule satisfiable at all.** `ai/AriaBrain.kt`'s
   `CANNOT_CLAUSE` is binary - "a tool that comes back unsuccessful is the same as no tool at all"
   (`:918`) - and has no vocabulary for *unknown*. Without idempotency the assistant would face a
   state it is not equipped to describe honestly. With it, ambiguity is resolved by retrying rather
   than by narrating, and the tool result is always a real success or a real failure. **The tool's
   failure result must still say in words what did not happen** (§7's feature-add checklist), and
   "not imported" is the only honest wording for an unresolved commit.

## Consequences for whoever builds this

**The atomic unit is far bigger than it looks.** `IngestPipeline.commit`'s single
`db.withTransaction` (`ledger/IngestPipeline.kt:328-430`) holds, in binding order: replace-flow
deletes, `resetOverlapping`, rule-7 supersession, the dedup read, `enumeratedWindows`, N x
`RecordStore.create`, and the file upsert. Each `RecordStore.create`/`delete` fans out into many
statements of its own. **Order of magnitude: 200+ statements for a 40-row statement** (reasoned
from the traced fan-out, not measured). Every one of those must land inside the single RPC.

**Ruling 7 of ticket 01 makes this dramatically easier, and that is worth knowing before anyone
despairs at the paragraph above.** Retiring the generic engine deletes `RecordStore`'s per-row
fan-out entirely, and the three full-table `activeByRecordType` reads at `:334`, `:369` and `:388` -
which today pull the entire active Transaction set over and filter it in Kotlin by parsing each
row's JSON payload - become ordinary SQL predicates against typed columns (`sourceFileId = ?`,
`provenance = 'UNRECONCILED' AND txn_date BETWEEN ? AND ?`). **The generic shape is the reason the
commit is expensive to move; ruling 7 removes it.** Sequencing follows: the engine retirement
should land before or with the RPC, not after. Ticket 05 owns that order.

**`resolveDedup` (`ledger/LedgerDedup.kt`) must move into SQL too**, or the full in-window row set
crosses the wire on every commit. Same for the `sameCard` suffix relation (ruling 5).

**There is no single gate object to move.** `engine/ReconciliationGate.kt` has **zero production
implementations** - its own KDoc (`:12-16`) says it rehomed the contract only and that ledger and
pantry must not be rewired onto it yet. The real arithmetic is duplicated across each parser and
agent. The one enforcement it does provide is type-level and genuinely valuable: `GateResult`'s
`Quarantined` branch has no `rows` field, so a quarantined result cannot carry rows by construction
(`ReconciliationGateTest.kt:65-69` asserts the compiler is the guard). **Preserve that property in
whatever replaces it** - in SQL the equivalent is that the failure path is `RAISE`, never a partial
insert.

**Quarantine stays a status plus nothing written.** There is no quarantine table today: a failure
upserts `IngestState.QUARANTINED` and a `quarantineReason` onto `ingested_files` and writes zero
rows (`IngestPipeline.kt:301-312`). `IngestedFileDao:44-47` states it outright - "QUARANTINED means
nothing was ever written for this file, so there is nothing to join". Keep that shape server-side.

**Pantry is unchanged by ruling 3** - receipts have no deterministic path by necessity (a receipt is
photographed, not born-digital), so vision stays primary there. Its two anchors
(`pantry/PantryReceiptAgent.kt:242-276`) move into the RPC like the ledger ones. Ticket 01 ruling 10
was amended this session so photo bytes may reach Supabase Storage and the vision pass can run in an
Edge Function; see that ticket.

## Three defects found while grounding this ticket

None of these were introduced here; all three predate the session and are filed so they are not
lost. Tags are the scout's own.

1. **`IngestPipelineProvisionalSupersedeTest` has been testing DEAD CODE since cutover 3**
   (`traced`). Its four androidTest cases - including the sharp one asserting reconciled rows are
   not dropped as duplicates of the provisional rows they replace - do not call
   `IngestPipeline.commit`. They re-implement the sequence in `commitLikeIngestPipeline:102-142`
   against the legacy `LedgerTransactionDao.deleteSupersededProvisional`/`insertAll`, which
   `IngestPipeline.kt:35-37` itself declares dead. Live engine-path coverage of rule 7 is a single
   test, `IngestPipelineEngineCommitTest.kt:136`. **Rule 7 is the least-covered load-bearing
   behaviour in the ledger, and it looked like the best-covered.**
2. **A schema mismatch strands a file silently** (`reasoned`, worth confirming).
   `LedgerAspectSeeder.ensureSeeded` and the schema read happen OUTSIDE the transaction
   (`IngestPipeline.kt:319-321`), and `fieldIds.getValue(...)` inside it (`:335`, `:373-374`) throws
   `NoSuchElementException` - not the `EngineWriteFailedException` the `catch` at `:431` is narrowed
   to. So it escapes uncaught and the compensating write back to `IngestState.NEW` never runs. A
   remote schema widens that window from microseconds to a network round trip.
3. **`BofaStatementParser` has no explicit non-empty guard** (`reasoned`). A genuinely zero-movement
   statement could pass with zero rows - the same vacuous-pass class §4 rule 6 was written for and
   that `BofaCardStatementParser.parseSectionBody:331-346` already closes for the card parser.
   `LedgerStatementAgent.kt:208-210` and the pantry agent both guard explicitly; this one relies on
   its balance-continuity check. **Ruling 3 retires this parser, so the fix may simply be its
   deletion** - but if the retirement slips, the gap is real.
