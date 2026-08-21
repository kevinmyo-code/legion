---
map: proactive-mode
ticket: 02
title: What decides there is something worth saying
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls, plus the advisor zoom the ticket asked for"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What decides there is something worth saying

## Question

The architectural fork of this whole map. Nothing evaluates goals, time of day, or location today
and decides a line is worth speaking - the 19 existing raises are each hard-coded at their own call
site.

Three shapes:

- **(a) A deterministic rule engine.** Cheap, predictable, zero tokens, inspectable, dumb.
- **(b) A periodic LLM pass over current state.** Smart, varied, costs money every tick,
  nondeterministic - **and it can invent a reason to speak**, which is the failure mode that makes
  proactivity intolerable.
- **(c) Hybrid: deterministic rules decide WHETHER to speak, an LLM only phrases the line.**

**(c) is the recommendation to argue against rather than a foregone conclusion.** It is the same
split as the reconciliation gate (CLAUDE.md §4) - determinism owns the decision, the model owns the
prose - and this codebase already has that instinct everywhere else.

Decide:

1. **Which shape**, and what the rules are made of if (a) or (c).
2. **Where the rules live.** `advisor/` already has playbooks, digest builders and `AdvisorAgent`
   with a writable-op allowlist. **Zoom `AdvisorAgent` before proposing anything new** - the parent
   ticket flags that whether any of it is wired to a proactive raise is unverified, and this map
   should reuse rather than build a second decision layer beside it.
3. **What a trigger can read.** `goals/GoalController` + `GoalProgress`, `sleep/SleepTarget`, the
   body aspect, `maintenance_items`, `code_events`, calendar. **Every one must be a falsifiable
   fact Kevin could check himself** - that is compulsion test item (a) and it constrains the engine's
   inputs, not just its output.
4. **Does a raise carry its reason?** If Alfred says "it's past 10pm", can he answer "why did you say
   that?" with the actual trigger. An inspectable reason is the cheapest trust mechanism available
   and it is nearly free under (a) or (c), nearly impossible under (b).
5. **Cost.** Under (b) or (c), what does a tick cost on Kevin's own key, and how often does it tick?
   The map's standing preference makes every new domain argue its token cost.

## Resolution - 2026-08-21 (Kevin, 4 calls)

### The zoom this ticket demanded, done first

**`advisor/` is pull-only and has no path to unprompted speech.** One caller in the whole repo -
`service/LiveToolbox.kt:3488`, the handler for the `ask_advisor` voice tool. Zero hits for
`ProactiveBus`/`ProactiveGate`/`speakProactive` anywhere in the package, and no file in it imports
`service/`. Its own map lists proactive delivery as out of scope (`.scratch/aspect-advisors/map.md`).
So there is no second decision layer to collide with - but there is plenty to reuse. [traced]

| Reuse | Do not reuse |
|---|---|
| The five `DigestBuilder`s - deterministic, read-only, no network, already the audited single place per aspect for "what does this domain look like right now" | **`AdvisorAgent` itself.** `ask(context, brief, question)` requires a human question, always spends a Gemini call, and always writes an `advisor_advice` row whose vocabulary (pending/accepted/rejected) is about a human accepting a proposal |
| `DigestText`'s vocabulary - `[proven]`/`[reported]`, `(estimate)`, `(unverified)`, and **absent data reads "not logged", never 0** | |
| `AdvisorProposalExecutor` + the writable-op allowlist, if a trigger ever proposes a write | |

Running `AdvisorAgent` on a timer would pay for an LLM call **to decide whether anything is worth
saying** - the exact inversion of what this ticket is choosing.

### 1. Shape: (c) HYBRID. Deterministic decides, the model only words it.

Not a new pattern here - **`AriaForegroundService.startHealthMonitor` already is one.** A rule fires
(a DTC appeared that was not in the baseline; coolant crossed `OVERHEAT_C`, edge-triggered with a
re-arm below the threshold), and the LLM is spent only on the sentence. Same split as the
reconciliation gate (CLAUDE.md §4): determinism owns the decision, the model owns the prose. It also
costs nothing on a tick that decides nothing, which matters because most ticks decide nothing.

(a) was rejected because canned strings mean the persona stops existing the moment it initiates, and
*"it's past 10pm, perhaps rest is in order"* - the line this whole map came from - is a register, not
a string. (b) was rejected on invention; see call 2.

### 2. `AmbientListener` - the live (b) - is RETIRED, not blessed.

It could never satisfy [ticket 10](10-what-a-raise-may-say.md)'s contract: the sub-agent authored the
spoken line, so there were no facts for the prompt to state. Keeping it would have put an exception
into the architecture on the day it was decided. It also turned out to be **dead code** -
`AmbientListenPreferences.setEnabled` had zero callers. Done: [ticket 12](12-retire-ambient-listening.md).

### 3. A raise's "already said this" memory goes in Room.

**The finding that forced this, verified 2026-08-21:** every raise hand-rolls its own dedup state and
**all of it is in memory**. `AriaForegroundService.kt:97` says so in its own words - *"Process-life."*
`recallChecked`, `overheatAnnounced`, `lastMilestoneAnnounced`, `lastWeatherAlertAt`: all local, all
lost on restart. The service is `START_STICKY`, and [ticket 07](07-scheduling-research.md) already
established Samsung restarts things.

So **"never nag twice" is not weakly enforced today - it is impossible**, and would have stayed
impossible under any engine built on the current state. One `proactive_raise` table - what fired,
when, on what reason, and whether it was brushed off - is the single store that makes never-nag-twice,
[ticket 05](05-quiet-hours-and-budget.md)'s budget, and call 4's affordance all possible at once.

### 4. Every raise carries its reason.

Nearly free under (c): the rule that fired IS the reason, so it is a field on the raise rather than a
reconstruction. The map already calls an inspectable reason the cheapest trust mechanism available.
Wording of "why did you say that?" belongs to [ticket 08](08-proactive-register.md); this ticket only
settles that the reason exists and is stored.

## What this leaves for the build

Not specified here, on purpose - this ticket was a decision, and the build wants its own:

- The **rule vocabulary**. A rule needs at minimum: an id, a category ([decision 1](../map.md)'s
  five), the falsifiable reads it evaluates, an edge-trigger versus level-trigger posture (the health
  monitor already needed both), and a re-arm condition.
- **Where the loop lives.** Eight `while (isActive)` loops already run in `AriaForegroundService`
  (health 5m, arrival 20s, drive 60s, recap 1h, memory 5m, drive-sync 5m, weather 30m). A ninth, or a
  consolidation, is a real design question and `AlarmManager` is the only thing that survives the
  process (`notes/AlarmScheduler`'s "re-arm on fire, never setRepeating").
- The **`proactive_raise` schema and its migration**, plus whether a brush-off is inferred or asked
  for.

## Built - 2026-08-21

**Status stays `resolved`, not `built`.** This was a DECISION ticket and the decision is what it
owed; the code is recorded here so the two are not confused. `built` on this map means a ticket
whose own deliverable was code and which is waiting on hardware.

Landed in `f9201c7` (Room v28 storage), `2243b85` (typed raise, gate, register clause, settings
rows) and `f1eff72` (delivery). Suite green at 1794 tests.

**Nothing in this effort has run on the phone**, and these are the parts a suite cannot reach:

- whether the assistant actually obeys `PROACTIVE_CLAUSE` - nothing inspects the spoken audio, the
  same limit `CANNOT_CLAUSE` documents about itself;
- whether screen-on plus the live-calendar check picks the right moments in real use;
- whether the Room-backed switches and the raise history survive the `START_STICKY` restart they
  exist to survive;
- whether a fired reminder now delivers exactly once - a change to behaviour Kevin already had,
  rather than a pure addition.
