---
map: fat-loss
ticket: "08"
title: "Engine: body coach tools for ask_engine and the web assistant"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss-engine (merged into feat/fat-loss); server suite 2917 run, 2 failures pre-existing on base (persona copy test); test_engine_mcp + test_recruiter 139 passed after the goal-weight change; owed: a voice write through ask_engine on the phone"
blockers: ["03", "04", "05", "06", "07"]
blocked-by: ["[[03-body-profile]]", "[[04-energy-calculator]]", "[[05-trend-and-waist]]", "[[06-adaptation-proposals]]", "[[07-saved-meals]]"]
tags: [ticket]
---
# Engine: body coach tools for ask_engine and the web assistant

Map decision 3. Registered in `server/engine_mcp/tools.py`, writes through `call_route` (the REST
view, never a second write path), each write's result saying in words what did and did not happen.

- Reads: `get_energy_estimate`, `get_weight_trend`, `list_saved_meals`.
- Writes: `set_body_profile` (includes `weekly_line_on`, so "stop the weekly weight line" is one
  call), `log_waist`, `save_meal`, `delete_saved_meal`, `log_saved_meal`,
  `accept_energy_proposal`, `dismiss_energy_proposal`.
- Recruiter allowlist: none of these (default deny), tested.
