---
map: body-coach-2
ticket: "01"
title: "Decisions: Kevin's rulings and the calls taken while building"
type: decision
status: resolved
status-detail: "Kevin's go and his Body interview, 2026-11-06 (decisions.md, ADR 0035/0077/0078 amended); build calls below are open to Kevin"
blockers: []
blocked-by: []
tags: [ticket]
---
# Decisions: Kevin's rulings and the calls taken while building

**Kevin's rulings** are in `memory/library/decisions.md`, 2026-11-06 ("Body coach 2" and "The Body
interview"), and the ADR 0035, 0077 and 0078 amendments. The calls below were taken while building
and are **open to Kevin**; each is a line in code with a test.

## Changing the plan

1. **Plan-level changes are proposals; an in-workout swap stays immediate.** `swap_exercise` swaps
   one slot at once (now honouring the programme's equipment and joints). Sessions a week, split,
   equipment, a sore joint and the walking finisher are proposals he accepts or dismisses.
2. **The proposal takes structured choices only**: sessions_per_week (2 to 4), split (full body,
   upper / lower, or "other"), without/with equipment, avoid/clear joints, cardio, and a swap to a
   choice the slot lists. The engine builds every exercise and number.
3. **Templates.** Full body (A, B) and upper / lower (Upper 1, Lower 1, Upper 2, Lower 2). Full body
   is the default for 2 or 3 sessions a week, upper / lower for 4.
4. **Sessions a week, not weekdays** (Kevin). The programme's stored weekdays are inert; a session
   is "due" while the week's count is short. *Superseded: the charting call on default weekdays.*
5. **The knee-friendly default** (Kevin): the squat slot leads with the Bulgarian split squat, the
   second leg slot with a wall sit; back squat, goblet squat, leg press, lunge and leg extension
   carry the knee tag and stay as alternatives. The split squat and wall sit are untagged.
6. **Joint tags** (shoulder, knee, lower back) are conservative catalog calls, open exercise by
   exercise. A slot with nothing allowed is paused and named. The words add "if it keeps hurting,
   see a physio".
7. **Gym or home is per session** (Kevin). Home is a kettlebell and bodyweight. A running session
   with nothing ticked is rebuilt when he says he is at home. Home kettlebell work progresses by
   reps (one bell). A weighted exercise done at bodyweight at home keeps asking for calibration:
   a known gap.
8. **A slot's range can differ per exercise** (a wall sit is 30 to 60 seconds in a rep slot).
9. **Progression carries** wherever an exercise survives (history is per exercise across all
   finished sessions); a past session's range is read from its own rows.
10. **Accept changes the programme in place**, so the deload count carries on; the proposal row
    keeps the before and after. A running workout is left alone. After a split change the next
    session is the new template's first day. Accept refuses if the programme moved since.
11. **One pending proposal per household**; a new one expires the old.
12. **"Alternatives to bench" is a read**, listing the slot's choices with why one is ruled out.

## What to eat

13. **kcal and protein are the targets** (Kevin). Left = today's meal target minus today's logged
    meals; with no meal target, the energy estimate's suggested target, said as such. Carbs and fat
    eaten are shown when a meal states them.
14. **Options** are saved meals, groceries bought in the last 14 days **as bought** (never divided
    into a portion), and pairs of two groceries whose sum fits. A grocery too big as bought is
    named only. "On hand" means "bought in the last 14 days"; nothing tracks what was eaten.
15. **Ranking**: only what fits; a meal share by the hour (morning a third of what is left, midday
    and afternoon a half, evening and late all of it); protein per kcal while protein is short,
    else the closest fit; five at most.
16. **Edges**: late (21:00 on) with under 300 kcal left says so; over the target is one neutral
    line plus the last 7 logged days' average, then the three lightest options "if you're
    hungry"; under 800 logged in the evening suggests a proper meal first. Never a push to skip.
17. **"Log that" logs by option id**; the engine recomputes the figures. A body carrying a figure
    is refused.

## The adaptation

18. **Four weekly points** (Kevin): a 28-day window, a weigh-in in each of its four weeks, and 22
    days of logged intake (the old 11 of 14, scaled). Cooldown stays 14 days.
