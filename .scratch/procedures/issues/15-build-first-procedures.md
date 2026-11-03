---
map: procedures
ticket: "15"
title: "Build: car_check, money_check, morning_briefing as built-ins"
type: build
status: built
status-detail: "built 2026-11-03 on feat/procedures-builtins, targeted suites green; owes deploy and a real MCP client (Claude Code) running car_check against the deployed engine"
blockers: ["09", "12"]
blocked-by: ["[[09-first-procedures]]", "[[12-build-runner-and-tool]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: car_check, money_check, morning_briefing as built-ins

## What

- The YAML files for the procedures ticket "The first procedures" settles, plus any `compute`
  functions they need in `procedures/steps.py` (e.g. `brief_latest`).
- Each `say` template states every caveat its tools state.

## Verify

- Golden tests on the demo household seed: same input rows, same result text, every run.
- A failed `vehicle_recalls` produces "not known", never "no recalls".
- An UNVERIFIED balance is said UNVERIFIED; a bank-feed balance is not.

## Built (2026-11-03, branch `feat/procedures-builtins`)

- `procedures/builtin/car_check.yaml`: `tool next_service` (input `vehicle`, optional) ->
  `tool read_records code_events` -> `tool read_records code_clear_events` -> `compute car_lead`
  (names the car or cars, says what is past due) -> `compute trouble_codes` (codes since the last
  clear, the latest read's date in the household's clock, and whether a freeze frame was stored,
  came back empty, or never came back) -> `tool vehicle_recalls` LAST -> `say`.
- `procedures/builtin/money_check.yaml`: `tool monthly_spend` -> `compute month_window` (the
  spend's own month) -> `tool account_balances` -> `tool grocery_spend` (that month) -> `say`.
  Pure pass-through of the tools' words: spend vs budget, UNVERIFIED and the amount still to
  categorise, each account's Now figure, "as of" the bank's own time.
- `procedures/builtin/morning_briefing.yaml`: `tool brief_latest` -> `tool list_events` (the
  household's today) -> `tool list_checklists` (today) -> `compute day_events` -> `compute
  checklist_day` -> `say`.
- `procedures/steps.py`: `car_lead`, `trouble_codes`, `month_window`, `day_events`,
  `checklist_day`. Pure over earlier steps' values.
- New engine tool `brief_latest` (read-only): the member's latest stored brief, never rebuilt;
  News lines left out (web text, ruling 06); a News section that was not built says why. Gives
  the household's today and UTC offset (never a zone id). `RECRUITER_EXCLUDED` with the visit
  route fence's own reason. `next_service` structured gains `utc_offset_minutes`.
- Runner: an optional input not sent leaves its argument out; a partial answer says an ok
  compute's sentence instead of the raw rows it summarised. A recruiter refusal names why.

### Deviations from ticket 09's sketch, and why

- **`check any past due` is a `compute`** (`car_lead`): a false `check` ends the run with a fixed
  sentence and drops the service text.
- **`compute brief_latest` is a `tool`**: a compute step gets no request, so no tenancy and no
  recruiter fence; a tool step goes through both. This is what refuses it to a visit.
- **Recalls are one car, not "per vehicle, parallel"**: the v1 runner has no fan-out and
  `vehicle_recalls` takes one car. **RESOLVED (Kevin, 2026-11-03: "agreed on all 3", option a)**,
  built on `fix/procedures-privacy-multicar`: a `check` step `which_car` (function `one_car`:
  at most one car read by `next_service`) sits before recalls. With several cars and no `vehicle`
  input it fails, and the run ends **ok** (not partial) with the service and codes for every car
  and "Recalls are looked up for one car at a time: say which car."; recalls and answer are
  "not on this path". With a `vehicle` input `next_service` reads only that car, so recalls run
  for it. To carry the earlier reads, the runner now renders a check's `fail_sentence` like a
  `say` template (`definition.validate` already checked its `$steps` refs); no new step kind, no
  new field. The car_check and `run_procedure` descriptions tell the voice model to pass the car
  the person named and to ask which car when several are on file and none was named. Tests:
  `test_several_cars_and_none_named_skips_recalls_and_asks_which_car`,
  `test_several_cars_and_one_named_looks_up_that_cars_recalls`,
  `test_one_car_passes_with_one_or_none_and_fails_with_several`,
  `test_a_fail_sentence_says_what_the_steps_before_it_read`. On deploy the changed file loads as
  car_check v2. Fan-out stays a runner ruling.
- **A failed recall lookup ends the run** (partial): the step is last, so service and codes are
  already said, and the tool's own "NOT known whether it has recalls ... not a finding of none"
  leads the answer. No `on_fail` edge was added to the format.

### Verify, accounted for (CLAUDE.md L11)

- Golden tests on the demo seed, same text every run: **done**
  (`tests/test_procedures_builtins.py`; car_check and money_check compare against the tools'
  own words and a second run).
- A failed `vehicle_recalls` produces "not known", never "no recalls": **done**
  (`test_a_failed_recall_lookup_is_not_known_never_no_recalls`).
- An UNVERIFIED balance is said UNVERIFIED; a bank-feed balance is not: **done**
  (`test_money_check_on_a_kevin_like_household`: BANK_API checking and the bank's own card
  balance carry no "unverified"; the old card's provisional row does).
- Recruiters: car_check and money_check run on the visit's copy, morning_briefing refused with
  the reason: **done**.
- Engine time vs ticket 04's 1.5 s p95: **done in tests** (NHTSA faked): car_check p95 ~24 ms,
  money_check ~21 ms, morning_briefing ~46 ms (20 runs each, two suite runs) on a throwaway
  Postgres 17. The real NHTSA call is not in those numbers: `recalls.TIMEOUT_SECONDS` is 8, and
  the runner checks its 3 s stop before `say`, so a lookup slower than about 2.9 s ends partial
  ("stopped at its time limit") with the recall text among what the steps said.
- A real MCP client running car_check end to end (ticket 12's deferral): **owed**, needs the
  deploy.
