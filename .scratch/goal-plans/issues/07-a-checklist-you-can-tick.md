---
map: goal-plans
ticket: 07
title: "A checklist you can actually tick, and one fewer workout section"
type: build
status: built
status-detail: "All four parts in, no migration needed - the day is derived, never stored. Owes the on-phone run: tick by hand, restart, tick by voice, generate a plan from the button."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# A checklist you can actually tick, and one fewer workout section

Kevin, 2026-08-22: *"bio page > workouts this week and the checklist section, retire workouts this
week. redundant. checklist needs to be an actual checklist that i can tick off."*

## 1. Retire "Workouts this week" from the Body screen

It is redundant with the daily checklist, which already states today's session. Two sections
answering "what training am I doing" is how they end up disagreeing - one derived from the plan, the
other from logged sets.

Sites: `ui/BodyScreen.kt:454`, `ui/common/GapRow.kt:182`, `ui/TodayGapResolvers.kt:88`. Check
whether the gap-resolver entry is referenced anywhere else before deleting it; a resolver removed
from one screen but still registered elsewhere is the kind of orphan this codebase has collected
before.

## 2. The checklist gets a real tick box

`ui/goals/GoalChecklistPanel.kt`'s own doc says there is **"deliberately no on-screen tick
affordance here"**, citing ticket 04's "do not build a second ticking path" rule.

**That reading is wrong and this ticket corrects it.** The rule forbids a second *mechanism* for
ticking - a parallel store, a different notion of done. A checkbox that calls
`NotesController.tick`, the exact function `manage_item` calls, is not a second path. It is the same
path with a finger on it instead of a voice.

**And ADR 0035 now makes it mandatory** (Kevin, 2026-08-22): every voice capability has a non-voice
path, and both paths call the same controller. A checklist tickable only by voice is precisely the
shape that ADR forbids - and it fails in exactly the moments a checklist is used, at the gym, in a
kitchen, next to someone asleep.

Requirements:
- Tapping an item ticks it; tapping again unticks it. Both through `NotesController`, never a new
  write path.
- The tick must **survive a restart**, because it is a real row and not view state.
- **No score, no streak, no percentage.** Unchanged and permanent (CLAUDE.md §7). A tick box shows
  what is done; it must not start grading him.
- An empty or unaccepted plan still reads "No plan yet", never zero progress.

## Verification

- Suite green **both** ways: `./gradlew testDebugUnitTest` and `testDebugUnitTest -Pnokey`.
- A test that the UI tick path and the voice tick path reach the same function - not two functions
  that happen to agree today.
- `python tools/docs_check.py` no drift.
- On the phone: tick by hand, confirm it survives a restart; tick by voice, confirm the screen
  agrees; and confirm "Workouts this week" is gone from Body.

## 3. Daily, not weekly - and this is the substantive one

Kevin, same conversation: *"checklist has to be a daily checklist instead of a weekly one."*

**This is a data gap, not a rendering choice.** Ticket 06's own assumptions ledger recorded it:
`WorkoutPlanItem` carries no day-of-week slotting, so a workout checklist line is *"a standing weekly
reminder rather than a specific day's workout"*. It says "push session" every day of the week
because nothing anywhere knows which day the push session belongs to.

A checklist that shows the same session every day is not a daily checklist. It is a weekly plan
printed seven times, and ticking it means nothing.

**Assign days at acceptance.** When a plan is accepted, its sessions get placed on specific days of
the week, and the day's checklist shows only that day's session.

Decide while building, and write the choice into this ticket:

- **Who picks the days?** The recommender already returns a structured plan and could name them, or
  they can be spread deterministically from the session count (three sessions becomes Mon/Wed/Fri).
  Deterministic is simpler and cannot hallucinate a seventh day; the recommender knows more about
  what the sessions are. **Deterministic is the recommendation** - a plan Kevin is told to "loosely
  follow" does not need the model to pick weekdays, and the map's accuracy bar is explicit.
- **A rest day shows no workout line at all**, not a line saying "rest". The nutrition and sleep
  lines still stand on their own.
- **What happens when the plan changes mid-week?** Days get reassigned from the new plan. Already
  ticked days stay ticked - a completed session is a fact about the past and a new plan does not
  un-happen it.

**Do not add a day column to `WorkoutPlanItem` without checking what already exists first.** If a
day can be derived from the plan's own shape at materialisation time, no schema change is needed,
and a migration added out of habit is worse than one avoided by reading.

**Built as: deterministic, no schema change.** `advisor/GoalChecklist.kt`'s `dayForIndex(index,
total)` spreads exercises evenly across Monday-Sunday from plain integer division
(`floor(index * 7 / total)`) over the SAME sorted-by-name exercise list `forToday` already builds -
three sessions land on Monday/Wednesday/Friday exactly as the "loosely follow" example above
describes. Nothing is stored: the day a session falls on is recomputed fresh from
`WorkoutPlanItemDao.currentItems` on every call, so `WorkoutPlanItem` and Room's schema are
untouched (still v31) and a plan revision reassigns days automatically, for free, the next time
`forToday` runs - no migration of old assignments needed. A rest day (no exercise assigned today)
produces zero workout lines, never a "rest" line; meal/sleep lines are unaffected since they are
never day-gated. Already-ticked history is untouched by construction, not by extra logic:
`GoalChecklistSync.materializeToday` only ever reads/writes items whose `createdAt` falls in
TODAY's window, so a plan change made today has no path back to a `ListItem` created (and possibly
ticked) on an earlier day, regardless of what today's derivation now says. Tests: `GoalChecklistTest`
(pure, three-session spread, rest day, mid-week reassignment) and
`GoalChecklistSyncTest` (Room-level proof that a past day's ticked item survives a same-day plan
change).

## 4. A button that generates the plan from a goal

Kevin, same conversation: *"goals > generate checklist etc. from a goal, need a button for that."*

`GoalPlanAgent` and its two voice tools (`generate_goal_plan`, `accept_goal_plan`) shipped with **no
screen at all** - tickets 02 and 03 built the capability, ticket 04 built somewhere to see the
result, and nothing anywhere lets Kevin start one by hand.

**This is ADR 0035 again, and it is the clearest case on the board:** generating a plan is a
capability reachable only by voice. It fails in exactly the place a plan gets made, which is sitting
down with a moment to think, not talking to a phone.

- A button on the goals surface: pick or state a goal, generate, see the proposed plan, accept it.
- **Acceptance stays ONE consent over the whole plan** (settled decision 14, goal-plans 02). The
  button does not become four separate writes.
- **Both paths call the same functions** - `GoalPlanAgent.generate` and `GoalPlanAgent.accept`. Not
  a UI copy of the flow. ADR 0035 is explicit that two implementations of one capability drift into
  disagreeing.
- Generation is a network call that can fail. **Every failure the voice path words honestly, the
  screen must word honestly too** - no spinner that ends in silence, no empty state that reads like
  a plan with nothing in it.
- The honesty line stays: it says once, at generation, that this is a starting point.
