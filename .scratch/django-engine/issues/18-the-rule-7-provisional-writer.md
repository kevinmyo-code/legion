---
map: django-engine
ticket: "18"
title: "The rule 7 provisional writer"
type: build
status: resolved
status-detail: "Recorded 2026-10-11. Built 2026-09-28 (b5b0049f, 8b976880) under backend-etl tickets 09 and 13; the live-file run is owed there."
blockers: ["13"]
blocked-by: ["[[13-provisional-ingestion-has-no-endpoint]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The rule 7 provisional writer

**A record, not a plan.** Ticket 13 resolved to option 2 and named its build in another map
(`.scratch/backend-etl/issues/09-bofa-statement-pull.md`). CLAUDE.md section 12's check only
reads blockers inside one map, so this ticket records the build here.

## What was built

- `b5b0049f` "The engine gets a rule 7 provisional write path": `server/ingest/provisional.py`.
  Every row `UNRECONCILED`, no statement header, "unverified" said in words; a re-pull replaces
  rather than duplicates; refuses any other provenance. `PROVISIONAL_REFUSAL` in
  `server/ingest/views.py` stopped pointing at ticket 13.
- `8b976880` "BofA activity CSVs are read deterministically, card provisional, checking gated":
  the deterministic reader that feeds it (rule 7 condition 1).

## Where the open work lives

- The real-file run: `.scratch/backend-etl/issues/13-bofa-activity-csv-server-side.md` (status
  built, owed one real card and one real checking CSV through the live watcher).
- ADR 0057 (2026-10-09) retires BofA files from the Drive watcher for Plaid-linked accounts. The
  writer stays for any other no-anchor source and as the way back.
