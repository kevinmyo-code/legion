---
map: worker-workflows
ticket: "18"
title: "Build: propose a recurring workflow by voice, approve on the phone, install on the Alienware"
type: build
status: open
status-detail: "Charted 2026-11-10 on feat/workflows-phone with the exact spec. Blocked on ticket 17's rulings (V2 is a new install path into Kevin's private skills folder)."
size: M
blockers: ["17"]
blocked-by: ["[[17-voice-set-rulings]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Propose, approve, install

Spec as recommended in [[17-voice-set-rulings]] (V1 A, V2 A, V3 A). Every example is invented.
Built in this order, each green before the next: engine, worker, phone.

## Engine (`server/claude_session/`, migration 0008, additive)

- `ClaudeTask.kind` gains `proposal` (widen the kind check); new columns `proposal` (JSON, null)
  and `proposal_hash` (char 64, blank). Check `claude_session` migration numbers on open branches
  first (0007 is `feat/worker-workflows`).
- `POST tasks/ {request, propose: true, machine?}`: kind `proposal`, origin `named`. Refused (400, in
  words) with a `parent`, a `workflow`, `deliver` of `phone` or `files`, or a named `listed` machine.
  Unnamed: the `full` machines online, else any `full` machine (it waits); none: 400.
- `POST tasks/{id}/finish/` takes `proposal: {entry, hash}` only for a `proposal` task finishing
  `needs_confirmation`. `entry` is checked by `workflows.clean_catalog([entry], ...)` (the same
  checks the heartbeat runs: no drive path, backslash, `@`, path variable or amount; scope; schedule
  bounds). A bad entry, or a name already in that machine's catalog: 400, nothing recorded.
- A follow-up to a `proposal` task stays `proposal` (as a workflow follow-up stays its workflow) and
  keeps `confirms`.
- `task_row` adds `proposal`: `{name, spoken, says, every, asks, deliver, params}` or null. Never
  the hash's input, never a path. `openapi.yaml` regenerated; recruiters denied as today.
- Tests: create refusals; finish refusals (bad entry, clash, wrong kind, wrong status); the row;
  the follow-up keeps kind and confirms; tenancy (another household cannot read it).

## Worker (`tools/claude_session/`)

- `task_message` for a `proposal` task with no parent: the helper drafts `SKILL.md` and
  `workflow.json` into `%USERPROFILE%\.legion\workflow-proposals\<task>\<name>\` (never
  `.claude\skills`), runs `workflows.py --lint` on that folder until it passes, and reports
  `needs_confirmation` with `--question "Install <spoken name>?"`. No token of any kind.
- Report drain for a `proposal` task: the WORKER PROCESS lints the draft folder itself. Exactly one
  workflow that lints clean: sha256 over `SKILL.md` + `workflow.json` bytes, and the finish carries
  `proposal: {entry: workflows.catalog_for_engine([parsed])[0], hash}`. Anything else: finish `failed` in words.
- A `confirms` follow-up to a `proposal` task never starts a helper and issues no token. The worker
  re-reads the draft, recomputes the hash and compares it with the engine's (`GET tasks/{parent}/`
  `proposal_hash`, served to the machine's own token only). Equal, and no `skills\<name>\` yet: copy
  the two files, re-scan the catalog (the next heartbeat publishes it), finish `done`: "Installed
  <spoken name>. It runs about every <span> when the Alienware is idle and stops at every question."
  Unequal, or the folder exists: finish `failed` in words, nothing written.
- A follow-up that is not a yes: finish `done` "Not installed.", draft folder removed (it is the
  worker's own).
- Proposal folders pruned at 30 days, matching the worker's own naming only.
- Tests (temp `Paths`, as `test_worker_workflows.py`): lint failure fails the task; the finish
  carries entry and hash; install copies exact bytes; edited draft (hash mismatch) refuses; existing
  name refuses; no token file is ever written for a proposal; a `listed` worker refuses; the catalog
  scan picks the new workflow up.

## Phone (`app/`)

- `claude_session` enum gains `propose`, with the steer "propose drafts a recurring one for his
  yes" (measure `LiveSetupPayloadSizeTest`; trim to pay, list each trim; ceiling stays).
- `ClaudeSessionController.proposeWorkflow(request, machine)`; both doors call it (ADR 0035). The
  screen: "Propose a recurring task" box. A proposal task card shows name, one line, schedule in
  words, stops, standing mail, and Install / Not now (Install is `answer` with `yes`).
- Guards (through `LiveToolbox.dispatch`, the `ClaudeWorkflowToolboxTest` pattern): `propose`
  refused in a read-through turn; `answer` with `yes` on a `proposal` task refused in a read-through
  turn (the toolbox reads the task's kind first). Nothing reaches the engine in either.
- The proposal's question is announced by the existing result watcher (the phone sent it).
- Voice guide copy for `propose`.

## Verification

- [ ] Engine tests above; full server suite.
- [ ] Worker tests above; worker tools suite.
- [ ] Phone: guards, card, controller over the fake backend; `LiveSetupPayloadSizeTest`,
      `VoiceGuideDataTest`, `python tools/voice_guide.py`; detekt no new findings.
- [ ] On the Alienware with Kevin (ticket 16): "have Claude check the rates sheet every morning",
      the card, Install, the workflow in the next catalog, its first scheduled run stops at its ask.
