---
map: dumb-phone
ticket: "03"
title: "Build: call screening"
type: build
status: built
status-detail: "Suite green 2026-10-22. Owed on the A25: setSilenceCall on the Samsung dialer, the role displacing Smart Call, a contact calling while dumb mode is on"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: call screening

`CallScreeningService` under `ROLE_CALL_SCREENING`. Off: allow everything untouched. On: chosen
number rings; a number seen within 15 min rings; anyone else `setSilenceCall(true)` (never reject,
never disconnect). Numbers seen kept with timestamps only, pruned at 15 min.

Verify: repeat-window and number-normalisation tests. Owed on the phone: setSilenceCall on Samsung
dialer; the role displaces Samsung Smart Call.
