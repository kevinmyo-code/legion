---
map: body-coach-2
ticket: "10"
title: "Nudges: evening protein shortfall, the Monday check-in, plan adjustment proposals"
type: build
status: open
status-detail: "ready on the engine (facts routes); the phone raises follow ticket 07; the Monday check-in's meal plan part waits for ticket 09"
blockers: ["05", "08"]
blocked-by: ["[[05-engine-meal-suggestions]]", "[[08-engine-weekly-adaptation]]"]
tags: [ticket]
---
# Nudges: evening protein shortfall, the Monday check-in, plan adjustment proposals

Kevin's ruling 5: b, c and d only. Each passes the CLAUDE.md section 7 compulsion test: (a) a fact
he can check, (b) actionable now, (c) never his absence or engagement, (d) silenced by one
sentence. **No workout-due or "you haven't trained" nudge, ever.**

| Raise | Anchor (a) | Says | Silence (d) |
|---|---|---|---|
| Evening protein | today's logged protein against the target, at a fixed evening hour | "About 50 g of protein left today; a shake or the chicken bowl covers it." (options from `meal-options/`) | "stop the protein reminder" |
| Monday check-in | Monday morning, his weekly weigh-in | asks for the weigh-in first; then trend, sessions done of the target, next week's meal plan and grocery list | "stop the Monday check-in" (today's `weekly_line_on`) |
| Plan proposal | a pending energy or programme proposal the engine made (a kcal change, a stall reset or deload ahead) | the proposal's words, Accept or Dismiss | "stop plan suggestions" |

## Engine

- `GET /api/body/coach/raises/`: `{say, facts, reason}` per raise, like `weekly-line/`; the
  phone decides only when to ask.
- Profile flags for each silence (`protein_nudge_on`, `plan_nudge_on`; `weekly_line_on` exists).
- A stall reset or a deload is computed by the rules already; the raise only says so ahead.

## Phone

- Three raises in the proactive registry, each declaring its anchor and silence sentence.
- The Monday line becomes the check-in (weigh-in first).

## Open

- The trend's 14-day rate with weekly weigh-ins is noisy; the check-in may want the 28-day rate.
