---
map: worker-workflows
ticket: "15"
title: "ADR 0067 amendment and decisions entry"
type: task
status: open
status-detail: ""
size: S
blockers: ["01"]
blocked-by: ["[[01-rulings]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# ADR 0067 amendment and decisions entry

Once the rulings land:

- `docs/adr/0067-claude-session-on-the-laptops.md`: an amendment section in words. Workflows are
  named worker skills with a `workflow.json` contract (R1); Alfred starts one with `action:
  "workflow"` (R2); ruling 15 resolved: recurring runs are catch-up, minted by the engine at most
  one per workflow, and a scheduled run stops at every ask-list and ship step, with one standing
  yes, `email_self` to Kevin (R3). The memory review is the first such workflow.
- Cross-link ADR 0070 (procedures): engine DAGs and laptop workflows are different things.
- `memory/library/decisions.md`: "2026-11-xx - Worker workflows (Kevin)", with his words.
- `python tools/docs_check.py`.
