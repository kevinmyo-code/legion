---
map: proactive-mode
ticket: 03
title: "The compulsion line, written as a test rather than a vibe"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls; the hardest case ruled PERMITTED"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The compulsion line, written as a test rather than a vibe

## Question

CLAUDE.md §7 bans compulsion mechanics: streaks, re-engagement pings, manufactured return, guilt for
being away. **An Alfred rest-nudge is mechanically IDENTICAL to a re-engagement ping** - same
notification, same unprompted speech, same time of night. The only difference is content and intent.

So it has to be written as something a future ticket can be **checked against**, not felt about.
Otherwise the rule cannot be enforced and every new nudge re-argues it from scratch.

**Proposed, to accept or redraw.** A raise must:

- **(a)** be anchored to a fact Kevin could verify himself - the clock, a goal he set, a sleep
  target, an NWS alert;
- **(b)** be actionable right now;
- **(c)** **never reference his absence, his streak, or his engagement with the app**;
- **(d)** be silenceable forever in one instruction.

**(c) and (d) are the load-bearing halves.** Without them, "it's past 10pm" becomes "you haven't
talked to me in three days" by increments, and nobody notices the day it crosses over.

Decide:

1. **Accept, redraw, or extend the four clauses.**
2. **Where does the test live so it is actually applied?** A rule in a map nobody reads is not a
   rule. Options: CLAUDE.md §7 (permanent, heavy), this map's settled decisions (binding on raising
   tickets), or a checklist item in the feature-add checklist. **The §7 route is the strongest and
   the hardest to reverse.**
3. **Who checks it, and when?** At charting time for a new raise, at review, or as a test over the
   raise registry. **A rule with no checkpoint is a rule that decays.**
4. **The hardest case, decide it explicitly:** a goal Kevin set and then ignored for two weeks. A
   nudge about it is anchored to a fact he chose (passes (a)), is actionable (passes (b)), and does
   not mention his absence (passes (c)) - **and yet "you set this and did nothing" is exactly the
   guilt mechanic §7 bans.** Either the test is incomplete or that nudge is permitted. Say which.

## Resolution - 2026-08-21 (Kevin, 4 calls)

### 1. All four clauses accepted, as written

A raise must **(a)** be anchored to a fact Kevin could verify himself, **(b)** be actionable right
now, **(c)** never reference his absence, his streak, or his engagement with the app, and **(d)** be
silenceable forever in one instruction.

### 2. The hardest case: the ignored goal is PERMITTED. The test stands unchanged.

Kevin's call, against the recommendation, which proposed a fifth clause banning any reference to a
history of inaction.

**State the consequence plainly, because it is the whole reason this ticket exists.** A nudge about a
goal set and then ignored for two weeks passes all four clauses, and *"you set this and did nothing"*
is guilt. Ruling it permitted means **the line between a useful reminder and a scolding one is now
TONE, and tone is not checkable.** It moves to [ticket 08](08-proactive-register.md)'s register -
"always offer, never instruct", "never mention that it is the second time" - which is a prompt rule,
and prompt rules are the weakest lever this codebase has (CLAUDE.md §7 on the speech-honesty clause:
nothing inspects the spoken audio).

That is a real cost, accepted knowingly rather than missed. It is written here so a future session
finds the decision rather than re-deriving the same fifth clause and thinking it is new.

**What still constrains it:** clause (c) is untouched. A goal nudge may reference the goal, its
deadline, and its next action. It may **not** reference how long Kevin has been away from the app, a
streak, or a count of missed days - those are about his engagement, not about the goal.

### 3. The test lives in CLAUDE.md §7

The heaviest and hardest-to-reverse option, chosen deliberately: §7 already bans compulsion in the
abstract, and this makes the ban checkable. A rule protecting against slow erosion belongs in the
file that is read every session, not in a map that a future effort may never open.

### 4. Checked by a test over the raise registry

[Ticket 02](02-trigger-engine.md)'s `proactive_raise` table gives every raise an id, a category and a
reason, so a unit test can assert that every registered raise declares its anchor (a) and its silence
instruction (d). Machine-checked, the same posture as `PromptRoleNamingTest`.

**Honest limit, and it is the same shape as clause 2's:** a test can check that a raise DECLARES an
anchor. It cannot check that the anchor is real, that the raise is actionable (b), or that the
wording avoids guilt. **Clauses (a) and (d) become enforceable; (b) and (c) stay reviewed by a
human.** Do not let the existence of the test imply all four are covered.

**Ordering:** this test needs the registry, so it lands with [ticket 04](04-categories-storage-and-surface.md)'s
build, not before it.

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
