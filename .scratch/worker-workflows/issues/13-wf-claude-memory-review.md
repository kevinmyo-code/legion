---
map: worker-workflows
ticket: "13"
title: "Workflow: claude-memory-review"
type: build
status: open
status-detail: ""
size: S
blockers: ["05"]
blocked-by: ["[[05-worker]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Workflow: claude-memory-review

Not LEGION's member memories (that is [memory-review](12-wf-memory-review.md)). This reviews
**Claude Code's own memory on the Alienware**: the auto-memory index and topic files under
`%USERPROFILE%\.claude\projects\<project>\memory\`, the repo's `memory/MEMORY.md` at
`origin/dev`, and `%USERPROFILE%\.legion\AGENT-RULES.md`.

`name: claude-memory-review`, `says: "Checks Claude's own notes for stale, duplicate or
conflicting entries and proposes edits."`, aliases "review Claude's memory", "check Claude's
notes"; no params; asks `[{at: "edit", says: "Apply the proposed memory edits?"}]`; outputs
`summary`, `report`; `every: {hours: 168, idle_minutes: 10, force_after_hours: 216,
retry_after_hours: 24}` (weekly catch-up).

## SKILL.md

1. Read each file. Flag: an entry that points at a branch, path or worktree that no longer exists
   (check with git and the filesystem); two entries that say different things about one topic;
   duplicates; an index line over its length rule; a date older than 30 days on something
   described as "now" or "in flight".
2. Write the report: each flag with the evidence, and the proposed edit as a unified diff.
3. `needs_confirmation`: "Apply N memory edits? Details in the report." On the yes, apply exactly
   the proposed diffs (these are Kevin's files, so the hook asks and the token covers it). The
   repo's `memory/MEMORY.md` is never edited here: it is listed as "for the next session" instead,
   because it is committed and shared.

## Rules

- Personal content in those files never goes into the summary or the engine; the summary is counts
  and topics ("three stale branch pointers, one conflict about the deploy rule").

## Verification

- [ ] Against a synthetic memory folder with one stale branch, one duplicate, one conflict.
