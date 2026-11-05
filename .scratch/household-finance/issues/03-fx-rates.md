---
map: household-finance
ticket: "03"
title: "Daily FX rates"
type: build
status: built
status-detail: "built 2026-11-05. Owes: deploy, install_schedule for fx_rates, one live fetch confirmed from each source"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Daily FX rates

## What
- `FxRate(date, base, quote, rate_micros, source)`: an integer rate x 1e6, never a float in storage.
- Daily job (Cloud Run worker schedule, like the Plaid sync) fetches USD/SGD from a free public source, with ECB/MAS as fallback, and records the source.
- `convert(cents, from, to, on_date)` uses that day's rate or the nearest earlier one, and returns the rate used, so every converted figure can say its rate and date.
- The "SGD view" uses today's rate for all figures (ruling 3).
## Verification
- pytest: rounding (half-even, int cents), missing day falls back to earlier, source recorded.
