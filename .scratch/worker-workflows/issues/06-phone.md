---
map: worker-workflows
ticket: "06"
title: "Phone: action workflow, workflows in check, Workflows on the Claude session screen"
type: build
status: open
status-detail: "Deferred 2026-11-10 by Kevin's brief: three unmerged branches edit ClaudeResultWatcher, ClaudeResultPolling and LiveToolbox, and the voice payload has no room to spare. Build off the merged tree against the contract in the map (Phone contract)."
size: M
blockers: ["03"]
blocked-by: ["[[03-engine-catalog-and-trigger]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Phone

## Voice (`service/ClaudeSessionToolbox.kt`)

- `action` enum gains `"workflow"` (R2). Dispatch: `c.runWorkflow(phrase = request, machine)`,
  `POST tasks/ {workflow: <phrase>, request: <phrase>, machine, origin}`. Result wording as `send`
  ("Sent to the Alienware: log audit. It stops for your yes before deploying." when the catalog
  has asks). A 400 reads its `detail` (the known names) back in words. Same task dialog over the
  app as `send`.
- **Measure first.** Run `LiveSetupPayloadSizeTest` with the enum value added and no other change.
  Budget: the enum value only (~3 estimated tokens). The description sentence stays as is; if a
  word is needed, trade one ("send queues a task" covers it). If it does not fit, fall back to R2
  option B (engine-side match on `send`, zero setup tokens) and say so in the ticket.
- `check` appends one line per online machine: "Workflows on the Alienware: log audit, ship
  round, memory review, Claude memory review, fix finding." Names only (`says` comes from the
  screen or "what does log audit do?", which `check` answers from the same list).
- No change to `CLAUDE_SESSION_CLAUSE` (R2).

## Hands path (ADR 0035)

- Claude session screen gains a Workflows section: per machine, each workflow's name, `says`,
  last run (status in words, when), "stops for your yes at ..." from `asks`, and a Run button with
  an optional params text field. Both paths call `ClaudeSessionController.runWorkflow`.

## Scheduled runs

- Tasks with `origin = schedule` show on the screen like any task, labelled "Scheduled".
- The result watcher also polls for `origin = schedule` tasks that end `needs_confirmation` and
  posts a notification in the Claude tasks category (R3). A scheduled `done` is not announced; it
  is read on `check` or the screen. Silenceable with the existing `quiet`.
- Voice guide copy (`tools/voice_guide_copy.py`) for the new action.

## Verification

- [ ] `LiveSetupPayloadSizeTest` and `MarcusPayloadTest` green with the change.
- [ ] Unit tests over a fake backend: workflow sent; unknown name reads the names; check lists
      workflows; screen Run calls the same controller; scheduled needs_confirmation notifies once.
- [ ] `VoiceGuideDataTest`, `python tools/voice_guide.py`.
- [ ] On the A25 (ticket 16).
