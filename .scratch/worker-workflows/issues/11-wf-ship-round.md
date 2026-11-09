---
map: worker-workflows
ticket: "11"
title: "Workflow: ship-round"
type: build
status: open
status-detail: ""
size: L
blockers: ["05"]
blocked-by: ["[[05-worker]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Workflow: ship-round

"Have Claude prepare a ship round" / "ship round with the audit fixes".

Today a ship round is done by hand in an interactive session: an `integrate/<date>` branch off
`origin/dev`, merge the ready branches, run the gates, run the ship script, and deploy only on
Kevin's explicit yes (the auto-mode classifier blocks a deploy without one). On the worker the yes
is the existing `ship.py` token: the classifier problem does not arise, and the hook already denies
every other route to a push or deploy.

`name: ship-round`, `says: "Merges the ready branches, runs every gate, and asks before
deploying."`, param `branches` (words; default "every branch marked ready"), asks
`[{at: "deploy", says: "Merge, deploy the engine and publish the phone update?"}]`, outputs
`summary`, `report`.

## SKILL.md

1. Read `AGENT-RULES.md`. `git fetch`. Candidates: the branches Kevin named, else branches whose
   map ticket says `built` with green tests and no open blocker (read from ticket frontmatter on
   each branch), never a `kiv` one.
2. Worktree in the work folder on `integrate/<date>`; merge each in order. A conflict: stop that
   branch, keep the rest, say which in the report (never resolve a conflict silently).
3. Gates through `build_lock.py`: server pytest, phone unit tests, detekt, `obsidian_sync.py
   --check`, `docs_check.py`, the payload tests. Red: `failed` in words with the first failure.
4. `ship.py ask --kind both --branch integrate/<date> --worktree ... --notes "<branches in a
   line>"`. Report its question word for word as `needs_confirmation`.
5. Kevin's yes: `ship.py run --task N --token <t>`. Its last line is the summary.

## Needs in ship.py

`ship.py` ships one branch today. Check it takes an `integrate/*` branch whose merges are already
committed (likely yes); if not, a small `--round` mode that lists the merged branches in the
question ("This also ships: ..."). That change is part of this ticket and its size.

## Verification

- [ ] `test_ship.py` for an integrate branch with two merged fixes.
- [ ] A dry run against a scratch remote (ship.py's existing test seam), never prod.
- [ ] One real round on the Alienware with Kevin present (ticket 16).
