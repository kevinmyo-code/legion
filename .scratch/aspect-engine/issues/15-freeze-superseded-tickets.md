---
map: aspect-engine
ticket: "15"
title: "Freeze the superseded tickets across the other maps"
type: task
status: resolved
status-detail: "Resolved 2026-08-23: one ticket frozen (hands-and-senses 29), GAI map annotated for charter decision 6; everything else was already resolved, built, or terminal"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Freeze the superseded tickets across the other maps

## Question

Charter decision 13: shipped code migrates like code; unbuilt tickets the engine supersedes must
not be built in parallel. AFK task:

1. Sweep the open tickets of: `notes-lists-calendar` (its calendar is now the central date DB),
   `google-account-integration` (the "Google owns timed events" ruling is superseded - annotate,
   don't rewrite history), `fleet-maintenance`, `mission-control`, `hands-and-senses`, and
   `legion-shape`.
2. For each open ticket that this engine replaces or reshapes: set `status: kiv` with a
   `status-detail` pointing at `.scratch/aspect-engine/map.md` and the charter decision that
   supersedes it. For each that is untouched, leave it alone and say so in the answer.
3. Do NOT close anything as resolved - kiv is the honest state: parked on purpose, not done.
4. Re-run `python tools/obsidian_sync.py` and `python tools/pending_wiki.py`; commit.

The answer is the table of every ticket touched, with its old state, new state, and reason.

## Answer

Swept 2026-08-23. Every ticket under the six maps was read by status. The sweep found far less
open work than the charter expected: five of the six maps are fully resolved or terminal, so
there was almost nothing left to freeze.

| Ticket | Old state | New state | Reason |
|---|---|---|---|
| `hands-and-senses/issues/29-one-source-for-service-history.md` | open (blocked by 28) | **kiv** | Unbuilt Room work unifying `service_records` and the maintenance clock on the typed fleet tables. Charter decision 3 migrates all 48 entities into the engine's generic tables, which replaces that schema work; the one-fact shape (clock as a projection of events, asserted-vs-observed provenance) becomes a field-definition decision in the fleet migration wave (ticket 14: fleet last). Building an interim v33 unification would be discarded at cutover. |
| `google-account-integration/map.md` | charting decision 2: "Google owns a timed event" | annotated, not rewritten | Superseded by aspect-engine charter decision 6 (LEGION owns time). One-line annotation added under Decisions so far; the original row stands. |

Left untouched, and why:

| Group | Status | Why untouched |
|---|---|---|
| notes-lists-calendar 01-12 | all resolved/closed | Decision tickets, already resolved; their built code migrates like code (charter decision 13). Nothing open to freeze. |
| google-account-integration 01-22 | all resolved | Same. Map frontmatter said `open: 1`; that is stale generated data - zero non-resolved tickets exist. Script re-run fixes it. |
| fleet-maintenance 01-18 | all resolved | Same. |
| mission-control 01-16 | all resolved | Mission-control look survives as the default arrangement (charter decision 9); all tickets resolved anyway. |
| legion-shape 01-12 | all resolved | Charter-level decisions the engine inherits, not work it replaces. |
| hands-and-senses 03 | kiv | Already parked (home control). Not re-touched. |
| hands-and-senses 22-26, 28, 30-32 | built | Built code owing phone runs. The engine migrates shipped code like code; built is not unbuilt, so decision 13 does not freeze them. Their phone-verification debt stands. |
| hands-and-senses closed/killed/archived/graduated | terminal | Not open work. |

**Caveat flagged for Kevin:** kiv on ticket 29 means the live drift bug (screen and voice
disagreeing on the Jeep's oil change) persists until the fleet wave, which migration order puts
LAST. If that gap bites before then, pull 29 forward as an interim fix knowing it gets discarded
at cutover.
