---
map: worker-workflows
ticket: "10"
title: "Workflow: fix-finding"
type: build
status: open
status-detail: ""
size: S
blockers: ["09"]
blocked-by: ["[[09-wf-log-audit]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Workflow: fix-finding

"Have Claude fix finding three" / "fix the double greeting from the audit".

`name: fix-finding`, `says: "Fixes one finding from the latest log audit, then asks to ship."`,
param `finding` (number or words, required), asks `[{at: "ship", says: "Ship the fix?"}]`,
outputs `summary`, `report`.

## SKILL.md

- Read the newest `reports\log-audit\` file; resolve the finding by number, else by words; none or
  two match: `needs_confirmation` asking which, with the candidates in one line.
- Then exactly `fix-alfred` from its step 2 (root cause in a worktree, failing test, fix, targeted
  tests, `ship.py ask`). The finding's evidence stands in for Kevin's words. Its own report file
  says root cause, the test, the branch.
- Ends `needs_confirmation` with ship.py's question word for word. Kevin's yes is the ordinary
  follow-up and token, unchanged.

Mostly reuse: a thin skill that calls into fix-alfred's steps, plus the lookup.

## Verification

- [ ] Against a synthetic audit report with three findings: "three", "the greeting one", an
      ambiguous phrase, a missing number.
