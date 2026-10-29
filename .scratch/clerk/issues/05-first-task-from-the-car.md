---
map: clerk
ticket: "05"
title: "Test: install the worker on Kevin's laptop and run the first task from the phone"
type: test
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Test: the first task

After tickets 02 (deployed, Kevin's "deploy OK"), 03 and 04 are on `dev` and the phone build is
installed.

1. Issue a device token for the laptop, run `tools/clerk/install_clerk.ps1`, see the heartbeat
   row in `GET /api/clerk/status/` and a "LEGION clerk" task in Task Scheduler.
2. From the phone: "have the clerk write me a Python script that renames photos by date and put it
   in the output". Expect "queued, the clerk on <machine> is online". Within a minute the raise:
   the script's name and a one-line summary. Clerk screen shows the file.
3. Offline case: stop the scheduled task, delegate another. Expect "off since <time>". Start it;
   the task runs.
4. Confirmation case: "have the clerk email the script to me". Expect `needs_confirmation` and
   Alfred asking; answer no; the task closes without sending.
5. Capture `LIVE_AUDIT` through `tools/voice_audit.py`; check the honesty clause held (no "done"
   at delegate time).
