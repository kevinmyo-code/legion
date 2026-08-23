---
map: aspect-engine
ticket: "15"
title: "Freeze the superseded tickets across the other maps"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
