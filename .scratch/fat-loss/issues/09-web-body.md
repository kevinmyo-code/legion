---
map: fat-loss
ticket: "09"
title: "Web /body: Energy card, profile, waist, saved meals, proposals"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss-engine (merged into feat/fat-loss); server suite 2917 run, 2 failures pre-existing on base (persona copy test); vitest coach 12/12, tsc clean; owed: a look at /body Coach tab after deploy"
blockers: ["03", "04", "05", "06", "07"]
blocked-by: ["[[03-body-profile]]", "[[04-energy-calculator]]", "[[05-trend-and-waist]]", "[[06-adaptation-proposals]]", "[[07-saved-meals]]"]
tags: [ticket]
---
# Web /body: Energy card, profile, waist, saved meals, proposals

The hands path for every new capability (ADR 0035) until the phone's Body screen lands.

- Energy card: maintenance and its basis in words, target, protein, every figure labelled an
  estimate; a pending proposal with Accept and Dismiss.
- Profile form (height, sex, birth year, activity, rate, protein per kg, weekly line on/off).
- Trend: EWMA line with raw dots, the rate, milestone progress.
- Waist log form and list; saved meals list, add, delete, log.
- `write_openapi`, regenerated `schema.d.ts`, `tsc -b`, vitest.
