---
map: worker-workflows
ticket: "17"
title: "Rulings: a recurring workflow set up by voice (proposal, approval, install)"
type: grilling
status: open
status-detail: "Charted 2026-11-10 on feat/workflows-phone. Not built: the install path is new (the worker process writing Kevin's private skills folder) and what his yes covers is his to rule. Ticket 18 is the build, blocked on this."
size: S
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Rulings: a recurring workflow set up by voice

Kevin, 2026-10-29: recurring tasks are "set by voice". He was told he could say "every morning,
check X". The accepted plan (ruling R1) puts a schedule in a `workflow.json` on the laptop, so after
tickets 02 to 06 he can only RUN a named workflow by voice. This ticket closes the gap inside the
existing rulings: named-or-asked only, no auto-install, his yes for each one, private skill content
never in the public repo or on the engine, scheduled runs stop at every ask-list step (R3), the
Alienware only by default.

The smallest honest path (ticket 18 builds it once these are ruled):

1. He asks Claude for a recurring task ("have Claude check the rates sheet every morning").
2. The worker drafts a workflow (a `SKILL.md` plus `workflow.json`) as a PROPOSAL in his own folder
   on the Alienware, never in the skills folder, and lints it.
3. The phone shows the proposal: name, what it does, schedule, where it stops for his yes, and the
   standing mail if any. Voice reads the same.
4. Only his yes on the phone installs it on the laptop. A read-through turn can neither request nor
   approve one.

Five questions. Each has a recommendation.

## V1. What does his yes approve?

| Option | For | Against |
|---|---|---|
| **A. The card: name, one line, schedule, stops, standing mail, machine** | Same fields the engine already holds for every workflow (R1's derived cache). Nothing private leaves the laptop | He approves a summary; the `SKILL.md` body (the how) is Claude's draft, read only on the Alienware |
| B. The card plus the `SKILL.md` body on the phone | He sees everything | The body names private paths and steps; R1 keeps bodies off the engine and the phone reads only the engine |

**Recommend A.** The body is bounded by R3 anyway: a scheduled run gets no token, stops at every
ask-list step, and its one standing yes (`email_self`) is on the card. The draft stays readable on
the Alienware, and Kevin can edit it there before saying yes (an edit changes its hash, so the yes
then fails in words and he asks again).

## V2. Who installs it?

| Option | For | Against |
|---|---|---|
| **A. The worker process, deterministically**: on a `confirms` follow-up to a proposal task it re-hashes the draft, compares it with the hash the engine stored at the question, and copies exactly those files into `skills\<name>\` (never over an existing one). No helper, no yes token | What is installed is byte for byte what the card described; testable with temp folders | A new write path into his private skills folder that does not go through the ask-list hook (the hook guards helpers, and this is not one) |
| B. Today's generic path: a helper with a `kind: yes` token copies it (ticket 14's step 5) | No new code path | The token opens the whole ask list for that helper; what lands in `skills\` is whatever it writes; no test can pin it to the card |

**Recommend A.** It is a change to the live worker's install path, so it needs his explicit yes.

## V3. How is it asked for by voice? (setup payload)

| Option | Setup cost (est.) | Notes |
|---|---|---|
| **A. `action: "propose"` on `claude_session`, plus ~8 words of steer** | ~12 tokens | Explicit; the phone can refuse it in a read-through turn. Paid for by a trim (ceiling 24,650 stays) |
| B. The engine turns an unmatched `workflow` phrase with a schedule word into a proposal | 0 | Magic; a near miss on a name becomes a proposal |

**Recommend A.**

## V4. Defaults

- Proposals only on a `full` machine (the Alienware); the work laptop refuses in words, matching the
  strict schedule default (`LEGION_WORKFLOW_SCHEDULES_LISTED` off).
- A proposal never declares `deliver: email_self` unless his request asked for mail ("and email it
  to me"); the card says so when it does, because installing is the standing yes (R3).
- A schedule within the lint's bounds (at most 30 days); one proposal per request.

## V5. Read-through

- `propose` is refused in a read-through turn (built the same way as `workflow` on
  `feat/workflows-phone`).
- A yes to a proposal (`answer`, `yes: true`) is refused in a read-through turn. **Open choice:**
  extend that to every `answer` with `yes: true` (any yes unlocks an ask-list step: a delete, a
  send, an install). Today no `answer` is guarded. Recommend: proposals only now, the wider guard as
  its own ruling.

## Answer

Not yet asked.
