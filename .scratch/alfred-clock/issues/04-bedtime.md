---
map: alfred-clock
ticket: "04"
title: "Bedtime"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Bedtime

## What

- Bedtime time and days. A wind-down notification 30 min before.
- At bedtime: `QuietMode.enable` and the ADR 0066 feed-block switch on, recording which of the two
  BEDTIME turned on. An ongoing "Bedtime" notification with End bedtime.
- At the next alarm's dismiss (or End bedtime, or voice "bedtime off"): turn off only what
  bedtime turned on. Something Kevin had on before bedtime stays on.
- **The one scheduled exception to ADR 0066's off-gate:** the feed block bedtime itself switched
  on goes off without the 60 s wait and sentence. Flagged for Kevin in ADR 0079.
- Quiet needs Do Not Disturb access; the Bedtime tab checks it before saving, never at 10 pm.
- Before first unlock the restore waits for unlock (Quiet's prefs are credential-encrypted).
