---
map: worker-workflows
ticket: "14"
title: "Workflow miner: the repeated-task loop"
type: build
status: open
status-detail: "Deferred 2026-11-10: the miner is a private-folder workflow; its input, the local task log, is built on feat/worker-workflows (ticket 05)."
size: M
blockers: ["04", "05"]
blocked-by: ["[[04-catch-up-runs]]", "[[05-worker]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Workflow miner

Part B. The queued idea "Claude worker skills + repeated-task audit loop". The Alienware already
has `skill-miner` (manual, weekly by intent, proposals into `%APPDATA%\legion-claude\
skill-proposals\`, never installs without a yes). This ticket turns it into a scheduled workflow
that proposes workflows, not a second detector.

## workflow.json

`name: workflow-miner`, `says: "Looks at what you keep asking Claude for and proposes new
workflows."`, aliases "what do I keep asking Claude", "mine my tasks", "suggest workflows";
asks `[{at: "install", says: "Make these workflows?"}]`; outputs `summary`, `report`;
`every: {hours: 168, idle_minutes: 10, force_after_hours: 240, retry_after_hours: 24}`.

## Inputs (R4)

- Engine: `GET /api/claude/tasks/?limit=200` (30 days), all machines.
- Local: `%USERPROFILE%\.legion\task-log.jsonl` (ticket 05; 180 days, request text).
- Current skills and `workflow.json` sidecars; earlier proposals; `dismissed.json`.
- Not read: interactive Claude Code sessions (R4), mail, member memories.

## Steps

1. Code first: drop tasks that already ran as a workflow; normalize requests (lowercase, strip
   "have Claude", machine names, dates, numbers); group by skill used (from the helper prompt's
   "Use the X skill") and by token overlap. The model only merges groups the code left close and
   names them; it does not invent members of a group (procedures ruling 8's posture).
2. Qualifies: 3+ tasks over 2+ separate days, mostly `done`, and the shape is not in
   `dismissed.json`. Also flag: a workflow whose runs keep failing or asking the same question;
   free-form tasks that matched an existing workflow's skill but not its aliases (propose the
   aliases).
3. Draft into `%USERPROFILE%\.legion\workflow-proposals\<date>\<name>\`: `SKILL.md` plus
   `workflow.json` (lint must pass), and `REVIEW.md` with one line each: what, why, task ids as
   evidence, counts and dates. Never edit an earlier proposal.
4. Finish `needs_confirmation` with ONE question naming the proposals, short and generic ("Make
   two workflows: rates sheet and tidy downloads?"). No proposal: `done`, "Nothing new this week."
5. Kevin's yes (follow-up and token): copy exactly the named proposals into the skills folder.
   A no, or no answer within 7 days: each named shape goes into `dismissed.json` and is never
   proposed again unless Kevin asks ("show me dismissed workflows").

## Privacy

- Analysis stays on the Alienware. The engine sees only the task's summary and question (names
  and counts). Proposals carry no secrets, mail content, money figures or profile details; they
  point at paths instead (skill-miner's existing rule).
- `skill-miner` retires into this; its folder becomes `workflow-proposals`.

## Verification

- [ ] Code-step tests over a synthetic task log: a 3-over-2-days cluster proposes; 3 on one day
      does not; a dismissed shape does not; an existing workflow is not re-proposed.
- [ ] One scheduled run on the Alienware ending in a single question (ticket 16).
