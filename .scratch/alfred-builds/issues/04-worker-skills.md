---
map: alfred-builds
ticket: "04"
title: "Worker skills: fix-alfred, build-feature, ship"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["02", "03"]
blocked-by: ["[[02-records-for-the-worker]]", "[[03-ship-step]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Worker skills: fix-alfred, build-feature, ship

## What
These live in the worker's private skills folder (`work/.claude/skills`).
- **`fix-alfred`:**
  1. Read the record (ticket 02) and Kevin's words.
  2. Find the root cause in the repo; read CLAUDE.md first.
  3. Write a failing test, then fix it in a worktree inside the task folder.
  4. Classify the fix as wording (pack) or code.
  5. Run the targeted tests, then ask the ship question.
- **`build-feature`:**
  1. Turn the request into a half-page spec in the task folder.
  2. If a real choice is open, ask Kevin at most 2 questions (`needs_confirmation`, one each).
  3. Build following CLAUDE.md (Hilt/ViewModel, hands path, voice guide copy), with tests, then ask the ship question.
  4. Snake goes in Games beside Tetris.
- **`ship`:** wraps ticket 03 for both skills.
- Update `legion-chores` to point at these.
## Verification
- A dry run on a fake report against a scratch branch.
