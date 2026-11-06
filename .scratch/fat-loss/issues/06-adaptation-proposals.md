---
map: fat-loss
ticket: "06"
title: "Engine: the two-weekly adjustment, proposed never applied"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss-engine (merged into feat/fat-loss); server suite 2917 run, 2 failures pre-existing on base (persona copy test); owed: a first proposal after 14 days of logs, accepted or dismissed by Kevin"
blockers: ["04", "05"]
blocked-by: ["[[04-energy-calculator]]", "[[05-trend-and-waist]]"]
tags: [ticket]
---
# Engine: the two-weekly adjustment, proposed never applied

Build item D. `energy_proposals` table; never auto-applied.

- **Window**: the 14 full local days ending yesterday. **Gate**: at least 11 days with a meal kcal
  logged, at least 8 days with a weigh-in, the window starting at least 7 days after the first
  weigh-in (the water week), a current meal target, and no proposal made in the last 14 days.
  Below the gate there is **no proposal**, and nothing says why to him unprompted.
- **Observed maintenance** = mean logged intake + trend loss (kg) x 7700 / 14.
- **Observed rate** %BW/week from the trend. Inside the 0.5 to 1.0 band: no proposal.
- **Off band**: desired = observed maintenance - the profile rate's deficit; proposed = desired
  clamped to the current target +/- 150 kcal, never at or below 800. Equal to current: none.
- Stored with every input it was computed from (CLAUDE.md section 4 rule 8: keep the evidence).
- Made lazily on `GET energy/` (idempotent); one pending at a time, an older pending one expires.
- `POST proposals/<id>/accept/` writes a copy-forward `meal_targets` row effective today (same-day
  row updated, protein/carbs/fat carried); `dismiss/` closes it. Both by tap (web now, phone ticket
  21) and voice (`accept_energy_proposal` / `dismiss_energy_proposal` engine tools).

## Verification

- Tests: under-gate gives none, in-band gives none, off-band both ways clamps to 150, accept writes
  the target, dismiss writes nothing, the evidence columns are stored.
