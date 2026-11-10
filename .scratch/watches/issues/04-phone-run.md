---
map: watches
ticket: "04"
title: "Run watches on the phone"
type: test
status: open
status-detail: "owed by Kevin after the engine deploy and an install"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Run watches on the phone

## Steps

1. Deploy the engine (watches 0001, `evaluate_watches` schedule line). Install `fast`.
2. Open Settings, Proactive speech: the Watches switch is on; "See and change your watches" lists
   the three built-ins.
3. "Alfred, tell me when a charge over $5 lands." Then buy something small. Within about 3 h (bank
   feed) + 15 min (evaluator) + 3 min (phone poll), Alfred says it once.
4. "Why did you say that?" names the watch and the charge.
5. "Stop watching charges over $5": gone from the screen; nothing further said.
6. Add a mail_reply watch with a made-up company you can email yourself as; it is said within ~15 min.
7. Turn Gmail off in Setup: the Watches screen says it cannot see mail.
