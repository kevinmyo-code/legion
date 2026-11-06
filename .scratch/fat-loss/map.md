---
map: fat-loss
title: "The fat-loss loop: Body as the seventh aspect"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/fat-loss/`"
tags: [map]
---

# The fat-loss loop: Body as the seventh aspect

**Kevin, 2026-11-05:** the goal is *"get lean, keep muscle"*. An earlier research pass inventoried
what the Body side has (bodyweight, meals, sleep, workouts, goals, the BIO advisor, the plan
generator, the wellbeing digest) and proposed a loop: a small profile, a deterministic energy
estimate, a smoothed weight trend, a two-weekly adjustment Kevin accepts or dismisses, a Monday
trend line, and saved meals so logging food by voice is one sentence. Kevin answered its questions
the same day. Then: *"revamp body screens too then, today it has a bunch of checklists and the
visuals are a bit hard to understand"*.

## Destination

Kevin weighs in when he likes, says what he ate (often "log my usual breakfast"), and LEGION can
answer "what should I eat to lose fat without losing muscle" from **his own numbers, labelled as
estimates**: maintenance from a formula until two weeks of his own trend say better, a target for
the rate he chose, protein for the muscle. Every two weeks, if the trend is off his band, it
proposes a small change and he says yes or no. On Monday at 8 am one line says where the trend is
against his goal, and one sentence stops it forever. The Body tab shows this at a glance.

## Kevin's rulings (2026-11-05, verbatim where quoted)

1. **Body is the seventh section 1 aspect** (*"10 ok"*). CLAUDE.md section 1, ADR 0077,
   `decisions.md`, the glossary and the cutover doc say so (ticket 02).
2. **No syncing scale, no watch** (*"4 no"*). No Health Connect in this map: weigh-ins are manual,
   by voice or by hand. Health Connect is parked (ticket 18, `kiv`).
3. **Food logging is "mostly voice + saved meals".** Track **kcal and protein only**; carbs and fat
   are optional everywhere.
4. **Gym access: yes.** The default programme is 3 full-body strength days.
5. **Diet:** *"i can follow any kind of diet i dont have food preferences"*. No diet style is
   assumed or pushed.
6. **His wife won't use it** (*"8 no"*). Body data stays household-shared; no per-member owner
   migration now. **The shared body tables rest on this ruling**: if a second member starts
   logging, bodyweight, meals, waist and proposals need an owner column first. The profile alone
   is per member from day one.
7. **Weekly Monday trend line: on, 8 am, silenceable with one instruction.**
8. **Health data may go to the engine and Gemini:** weight, steps, sleep, daily totals. **No
   heart rate.**
9. **No medical condition or medication** (*"12 no"*). The BIO playbook's referral boundaries
   stand unchanged.
10. **Goal: "get lean, keep muscle".** A starting target, protein figure, rate, first milestone and
    a likely lean range were advised in chat. **Kevin's personal stats (height, weight, age,
    targets, milestone) are private and never enter this public repo**: he enters them on the
    phone or the web, or the orchestrator writes them through the API after deploy. The rate band
    is generic and may be written down: **0.5 to 1.0 % of bodyweight per week**. A **weekly waist
    measurement** is a second falsifiable anchor beside the scale. Tests use synthetic people.

## Decisions taken in charting (Opus; open to Kevin)

1. **One new engine app, `server/body_coach/`, Django-managed.** The eight body tables are
   `managed = False` legacy; the four new tables (`body_profiles`, `waist_logs`, `saved_meals`,
   `energy_proposals`) get a real Django migration, `TENANT_TABLES` and
   `DJANGO_MANAGED_TENANT_TABLES` entries, and the tenancy test.
2. **Engine first.** The calculator, trend, adaptation and weekly-line facts are pure Python in
   `body_coach/`, once. The phone reads them through one controller with a saved copy (the
   `FinanceController` shape): fresh, saved with its age in words, or unavailable in words.
3. **Voice writes ride `ask_engine`, not `ask_body`** (deviation from the brief, reported).
   `DISPATCHED`'s own comment records Kevin's 2026-08-18 ruling that writes came OUT of the
   dispatchers, and ADR 0065 pins `ask_body` as unable to reach a write. Profile, waist, saved
   meals, the proposal accept/dismiss and "stop the weekly weight line" are engine MCP tools
   (`server/engine_mcp/tools.py`), which `ask_engine` already reaches with `intent: record` at zero
   setup tokens, and which the web assistant gets for free. The reads (`get_energy_estimate`,
   `get_weight_trend`, `list_saved_meals`) are phone tools behind `ask_body`, so offline he still
   hears the saved copy.
4. **"Log my usual breakfast" needs no new tool.** `log_meal` checks the saved meals first; a name
   match logs the saved estimate through the same `BodyWriteThrough` path (queued offline).
5. **The plan generator does not invent a calorie number.** `GoalPlanAgent` reads the engine
   estimate; with no profile it returns the missing inputs and proposes no meal target. With one,
   the code sets the plan's kcal and protein from the estimate (LLM advises, app computes) and the
   model writes the rest. Protein is 2.0 g/kg of goal weight or current weight, whichever is lower
   (range 1.6 to 2.2); this supersedes the generator's old "always against total bodyweight".
6. **The weekly line is its own raise** (`ruleId` `weekly_weight_line`, WELLBEING, own Monday
   08:00 alarm), with its own off switch on the profile (`weekly_line_on`), so "stop the weekly
   weight line" silences it without muting the daily digest.
7. **Body screen UI waits for Kevin's pick** (tickets 19 to 21). Until then the web `/body` page is
   the hands path for every new capability (ADR 0035), and the phone's Energy card, trend pane and
   Accept/Dismiss buttons are built once, in the chosen design.

## Engine contract (binding on tickets 03 to 13)

All under `/api/body/coach/`, household-scoped, device token or session. Weights on the wire are
**lbs** (kg readings converted, never averaged raw); lengths on the wire are cm and inches, stored
as integer millimetres; kcal and grams are integers, weights and lengths JSON numbers to 1 dp,
rates percent of bodyweight per week to 2 dp. Every
estimate carries `"estimate": true` and a `basis` word; every number a figure is built from is
returned beside it.

| Route | Does |
|---|---|
| `GET profile/` | the caller's own profile, or `{"exists": false, "missing": [...]}` |
| `PUT profile/` | partial update of the caller's own profile (height_cm, sex, birth_year, activity_level, target_rate_pct, protein_g_per_kg, weekly_line_on) |
| `GET energy/` | BMR, maintenance (formula or trend), suggested target, protein, current target, pending proposal, `missing` inputs, `words` |
| `GET trend/?days=90` | daily raw + EWMA points, rate (14 days), early-water flag, milestone progress from the `bodyweight_lbs` goal, waist series, `words` |
| `GET/POST waist/`, `DELETE waist/<id>/` | waist log, shared |
| `GET/POST saved-meals/`, `DELETE saved-meals/<id>/`, `POST saved-meals/<id>/log/` | saved meals, shared; `log` writes a `meal_logs` row |
| `POST proposals/<id>/accept/`, `POST proposals/<id>/dismiss/` | accept writes a copy-forward `meal_targets` row for today |
| `GET weekly-line/` | `{"say": bool, "facts": "...", "reason": "..."}` for the Monday raise |

The formulas, gates and words are in tickets 04 to 07.

## Not yet specified

- **Which Body screen direction** (ticket 20, Kevin's pick).
- **Whether the phone replicates the four new tables into Room.** Today it reads them through the
  engine with a saved copy; waist and saved-meal writes need the engine. Revisit if offline logging
  of a waist measurement turns out to matter.
- **Steps.** Ruling 8 allows them, but with no watch and no Health Connect there is no source.

## Out of scope

- Health Connect, a syncing scale, a watch (ruling 2).
- Heart rate anywhere (ruling 8).
- Per-member ownership of the shared body tables (ruling 6).
- Streaks, daily nudges, anything about missed weigh-ins or logs (CLAUDE.md section 7).
- Medical advice (ruling 9; the BIO playbook's boundaries).
