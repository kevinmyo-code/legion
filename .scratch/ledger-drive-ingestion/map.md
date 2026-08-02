# Map: Ledger Drive-folder ingestion + basic UI

Label: `wayfinder:map`
Effort: `.scratch/ledger-drive-ingestion/`
Charted: 2026-08-01

## Destination

A **build-ready implementation spec** covering two things, detailed enough that one execution pass
can build both without further taste calls:

1. **Batch ledger ingestion from a Drive folder.** Point the app at a folder of bank statements,
   ingest them all in one go, and never re-ingest a file it has already seen. New statements
   uploaded to that folder later get picked up without re-processing the old ones.
2. **A basic UI across all three aspects**, on top of an app shell that actually starts the
   foreground service and takes a Gemini key. The app currently launches to a placeholder Text and
   never cold-starts `AriaForegroundService`, so nothing else is reachable.

The map is done when nothing is left to decide. Implementation is a separate pass after that.

## Notes

**Domain:** Android phone app (Kotlin, Compose, Room v3), `com.kevin.legion`. Repo
`C:\Users\Kwin\StudioProjects\legion`, branch `dev`.

**Read before deciding anything:** `CLAUDE.md` (rules, especially §4 the reconciliation gate and §7
guardrails) and `memory/MEMORY.md` (state). Most of `memory/library/` is FROZEN Midnight AI history
and carries a status banner; do not act on frozen shelves.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for the HITL tickets,
`/prototype` for the prototype tickets, `/research` for research tickets.

**Standing preferences for this effort (Kevin, 2026-08-01):**
- Drive access goes through Android's SAF folder picker against the Google Drive app's
  DocumentsProvider. No new OAuth scope if it can be avoided.
- UI scope is the full app shell plus all three aspects, not ledger alone.
- Dedup gets fixed properly: file-level tracking AND stop collapsing legitimate twin transactions.
- LLM spend is gated per batch: show the count and rough cost, wait for a go-ahead.

**HARD PROCESS RULE.** `.scratch/` is gitignored. **A previous wayfinder map for this repo was
permanently lost in a machine port because none of it had been filed to `memory/library/` yet.**
Every resolved ticket's decision gets filed to `memory/library/decisions.md` via the librarian
BEFORE this effort ends. Do not let this directory be the only copy of anything.

## Decisions so far

- Charting session (2026-08-01) settled four framing calls up front, recorded here because they
  shaped the ticket set rather than resulting from it: SAF folder picker over OAuth scopes; full
  app shell plus all three aspects; fix dedup properly at both layers; gate LLM spend per batch.
- [Can SAF actually read a Google Drive folder?](issues/01-saf-drive-folder-feasibility.md) -
  PARTIAL, leaning YES. Build on SAF, **gate at API 30** (Drive's provider only advertises tree
  support there), keep a per-file `ACTION_OPEN_DOCUMENT` fallback that `minSdk = 24` makes
  mandatory, add no new OAuth scope. The crux - do later-added files appear - traces to YES through
  four layers but **is not device-verified**; probe raised as its own ticket. SAF exposes **no
  content hash**, which constrains the file ledger's identity choice.
- [What does this app look like?](issues/02-design-language.md) - **"Instrument" on Material 3's
  machinery.** The assistant as a readout: mono numerals (the mechanism for tabular figures, since
  Compose has no `tabular-nums`), shape scale flattened to near-zero, hairlines instead of cards,
  one accent, no dynamic colour. M3 keeps its components and accessibility; only the token layer is
  retuned. Money and provenance roles live in `LegionSemantics` outside `ColorScheme`, with `debit`
  deliberately uncoloured. **Built and compiling** in `ui/theme/`; no preview rendered, nothing on
  device. Dark-vs-light default, icons, and motion left open.

- [What identifies a statement file as already ingested?](issues/03-ingested-file-ledger.md) -
  **the record's job is WORK AVOIDANCE, not correctness.** Correctness stays at the transaction
  layer (ticket 04). Skip filter is `driveFileId` + `sizeBytes` + `lastModified`, metadata only, so
  a known unchanged file costs zero bytes; `acc=N;` stripped because the probe showed it is a
  positional account index. `contentSha256` is recorded (free, bytes already in hand) and stops a
  duplicate **before** parsing, keeping its LLM call out of ticket 06's estimate. Four states,
  quarantine retryable only by explicit user action. Records are **never pruned** - the probe
  proved a listing can be stale-empty with no signal, so absence is not deletion.
  `ledger_transactions` gains a nullable `sourceFileId`, which is what makes replaced statements
  solvable at all. Multi-folder carried now via `treeUri`. **`syncId` deferred to ticket 10.**

- [How does a folder of statements actually get ingested?](issues/05-batch-ingestion-mechanics.md) -
  **runs in the existing `AriaForegroundService`**, which already declares `dataSync`, so no new
  dependency and no manifest change; `androidx.work` deliberately not added. **Two phases split by
  what each is bound on:** fetch+hash **parallel (4)**, parse+gate **strictly serial** (bounds
  PdfBox memory to one document, keeps the spend count exact, never fires concurrent Gemini calls).
  Phase 1 completes for all files, spilling to `cacheDir`, which is what gives ticket 06 an exact
  count to gate on. A killed scan is **re-run, not resumed** - known files cost zero bytes.
  **A batch is NOT atomic as a whole.** Rescan is a **listing-only diff on app open** (one query,
  zero bytes, zero spend) surfacing a quiet inline count; never auto-ingest, never a notification.
  UI observes one `StateFlow<ScanState>`. Single-file import is **unified** into the same pipeline,
  which amended ticket 03's `treeUri` to nullable.

- [What makes two identical transaction lines distinct?](issues/04-twin-transactions.md) -
  **`lineRef` cannot carry dedup weight**, established by reading all three producers: BofA's is
  stable but not unique, DBS's is stable and unique, the LLM's is neither. **Dedup counts per tuple
  instead of testing existence:** group both sides on account+date+amount+normalized description,
  insert `max(0, N - M)`. Twins in one statement both survive, overlapping statements still
  collapse, no new column and no `lineRef` dependency. Normalization is comparison-time only and
  runs in Kotlin. **It errs toward DROPPING** on genuine ambiguity, recorded per file via
  `duplicatesSkipped` rather than left silent. Grilling exposed a **replace hole** - counting means
  an overlapping file can contribute zero rows, so deleting one file's rows can destroy
  transactions another file also attested to; fixed by resetting overlapping files to `NEW`, which
  amended ticket 03 a second time. Installed base is zero, so no backfill.

- [How is LLM spend estimated and approved before a batch?](issues/06-llm-spend-gate.md) -
  **no recognize-only pass exists and adding one buys nothing** (extraction dominates; DBS only
  fails after iterating pages). Instead **split the dispatcher**: deterministic parse runs over all
  staged files and commits, then the gate asks with an **exact** fallthrough count that cost zero
  API spend to obtain. Amended ticket 05's gate placement. **Ask every time, never remember** -
  defensible only because recognised banks never reach the gate. Count leads, cost is a labelled
  secondary estimate. **No price constant adopted**: the `analyst`'s dollar figures are `reasoned`
  and explicitly unverified, and implicit thinking-token billing is an open unknown. `SubAgent`
  discards `usageMetadata` today; parse it and record measured tokens per file so the estimate stops
  being a guess derived from a guess. Declining needs a fifth state `NEEDS_LLM` exempt from 03's
  skip rule, else declining is permanent - amendment 3 to 03.

## Not yet specified

- **Quarantine review UX.** What the user does with a statement that failed reconciliation:
  inspect it, retry it against the LLM, correct it by hand, or dismiss it. Depends on the ingested
  file ledger's state model and on the ledger UI shape.
- **Mixed-institution folders.** A folder holding both Bank of America and DBS statements, and how
  account identity is derived and displayed when several accounts coexist. `accountId` is a bare
  String today with no registry behind it.
- **Corrected or replaced statements upstream.** A bank reissues a statement, or the user replaces
  a file in the folder with a corrected version. The file hash changes, so it reads as new, and its
  transactions collide with rows already committed.
- **Whether ledger data should sync at all.** `LedgerTransaction` already carries a `syncId`, so
  someone intended it to, but nothing registers it with `SyncEngine`. Interacts with the open
  append-only problem.

## Out of scope

- **Ledger categorization, FX conversion, and insight layers.** Deliberately deferred at ledger
  scoping time (CLAUDE.md §10). Nothing to port from Andromeda; this is new design work and a
  separate effort.
- **OAuth verification, CASA assessment, Play Store publishing.** There is no commercial model
  (CLAUDE.md §2). Only relevant if a restricted Drive scope is ever adopted, which the standing
  preference above avoids.
- **A replacement for the retired spend gate.** Ruled out at pivot time with no ledger replacement
  chosen; reopening it is its own effort.
