---
map: goal-keeping
title: "Map: Goal keeping"
charted: 2026-08-18
charted-by: ""
effort: "`.scratch/goal-keeping/`"
tickets: 8
open: 7
status: open
tags: [map]
---
# Map: Goal keeping

## Destination

**Kevin holds goals stated in a form the app can either measure or check in on, always knows where
he stands on each without asking, and gets interrupted only at moments that actually help.**

Reaching it means a goal is captured well when it is set, tracked honestly - measured where the app
can compute it, asked where it cannot - reviewed and retired before it goes stale, and spoken about
unprompted only when the moment earns it.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room). Read `CLAUDE.md` for rules and
`memory/MEMORY.md` for state before deciding anything. Screen aesthetics belong to
`.scratch/mission-control/`; any surface coordinates with that map rather than inventing a look.

**Where this came from.** Kevin, 2026-08-18: *"we are extending unprompted. the ai keeps me on track
for my goals."* Charted as its own map rather than as `.scratch/proactive-mode/`'s trigger-engine
ticket because the destination is the goal-keeping PRODUCT; proactive speech is one delivery channel
of several, alongside the advisor, the GOALS panel and the ALERTS pane.

### Settled at charting - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **The advisor is the brain.** Reuse `AdvisorAgent`, its five briefs, its digests and its propose-accept protocol. (Kevin, 2026-08-18.) | Nobody builds a second thing that reasons about goals. `.scratch/proactive-mode/issues/02-trigger-engine.md` already instructs the same reuse; this map inherits that instruction rather than restating it. |
| 2 | **A goal the app cannot measure is ASKED about, never judged.** The check-in answer Kevin speaks becomes the record. (Kevin, 2026-08-18.) | No ticket may infer progress on a prose goal from adjacent records. That is the assistant forming beliefs about Kevin's life it cannot check, which CLAUDE.md sec 7 rules out by name. |
| 3 | **Setting goals well is IN scope.** (Kevin, 2026-08-18, over a recommendation to exclude it.) | The map owns the whole loop: stated, kept, checked in on, revised, retired. The known cost, accepted at charting: goal-setting coaching overlaps what the advisor already does on request, and it pushes the destination further out. |
| 4 | **Delivery rides `.scratch/proactive-mode/`'s one gate.** | This map never opens a second speech path. `ProactiveBus.speakIfAllowed` is the only unsolicited door and the master switch silences it. |
| 5 | **No Kevin-hosted anything.** | CLAUDE.md sec 7, unchanged. |
| 6 | **Install and look.** | Every UI claim wants a screenshot, not a passing suite. This repo has shipped a screen drawing body text in quarantine red past a green build. |

### What exists today - verified 2026-08-18, not remembered

| Claim | Reality |
|---|---|
| Goals are stored | **Yes.** `data/local/Goal.kt`: prose `statement` always required, `targetValue`/`unit`/`metricKey`/`deadlineEpoch` all nullable and all-null is a valid row. Full revision lineage (`lineageId`/`supersedesId`), so a goal that quietly got easier is a fact in the record. |
| Progress can be computed | **Only sometimes.** `goals/GoalProgress.kt` does accumulation maths; `metricKey` has three known values (`bodyweight_kg`, `savings_balance_cents`, `odometer_miles`). Direction-ambiguous metrics deliberately never call it. |
| Anything notices a goal is overdue | **Yes, silently.** `ui/TodayGapResolvers.kt`'s `buildAlertRows` emits an ADVISORY row for a goal past its deadline. It renders on a screen and is never spoken. |
| Anything speaks about a goal unprompted | **No.** All 11 unprompted paths are car-shaped (ignition, OBD, recalls, weather, location, reminders, calls). None is goal-aware or time-aware. |
| The advisor can act alone | **No, by design.** `AdvisorProposalExecutor` requires an explicit accepted proposal id; `AdvisorBriefs` allowlists which ops each aspect may PROPOSE, and HOME may propose none. |
| Goals can be set by voice | **Yes.** `set_goal`/`close_goal`/`list_goals` exist; `list_goals` sits behind `ask_goals`. |

## Decisions so far

<!-- one line per closed ticket -->

- [What else in the app's own records can be computed deterministically](issues/08-what-else-is-computable.md)
  - **Ten metrics are already computed by the digest builders and merely unreachable from
  `metricKey`**; of the three documented keys, `odometer_miles` resolves nowhere and
  `bodyweight_kg` returns the logged unit unconverted. `metric_key` is unvalidated free text and
  `GoalController` uses it for revise-versus-create, so two spellings mint two competing goals.
  **Direction is a property of the goal, not the metric, and is not stored.** Full report in
  [research/08-computable-metrics.md](research/08-computable-metrics.md).

## Not yet specified

- **Escalation.** What happens when Kevin misses the same goal repeatedly. Anything shaped like a
  streak, a guilt mechanic or a manufactured return is banned by CLAUDE.md sec 7, so the question is
  what is LEFT that still helps.
- **Prioritisation.** Several goals competing for one moment of attention. The HOME advisor already
  half-does this ("name the goal most at risk") and may be the answer rather than new machinery.
- **Cross-aspect interaction.** A training goal and a spend goal that fight each other.
- **A goal that was never going to happen.** Whether the app should ever say so, and on what
  evidence, without becoming a thing that judges Kevin.
- **Where goal history is reviewed.** The revision lineage is captured and nothing renders it.
- **Whether the ten already-computed digest metrics need a goal-facing resolver at all**, or whether
  a goal should point at a digest line rather than at a `metricKey`. Surfaced by ticket 08; too
  coarse to ticket until ticket 01 defines what a metric owes a goal.

## Out of scope

- **The master switch, the five categories, quiet hours and the nudge budget** -
  `.scratch/proactive-mode/` owns these; this map consumes them.
- **Crisis handling.** `ai/CrisisDetector.kt` responds to something Kevin said and sits outside
  every proactive scheme by settled decision.
- **Goal sync across devices.** Drive has no compare-and-swap (CLAUDE.md sec 2); goal sync waits for
  append-only sync like every other table.
