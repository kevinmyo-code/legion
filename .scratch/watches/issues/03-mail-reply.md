---
map: watches
ticket: "03"
title: "Phone-evaluated mail_reply: a company you applied to emails back"
type: build
status: built
status-detail: "built 2026-11-10 on feat/watches; MailReplyCheckTest green. Owed: Kevin's run (ticket 04)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone-evaluated mail_reply: a company you applied to emails back

Kevin, 2026-11-10: *"notify me whenever a company i applied to emails me back"*.

## What

- The engine stores only his list of companies (`mail_reply` params). The phone checks Gmail
  headers (`users.messages.list`, metadata) about every 15 min on the same tick, mail newer than the
  last check, promotions excluded.
- Match in code: sender domain, `From` display name, or subject contains a name from his list.
- The line says only the company name FROM HIS LIST and how long ago. `carriesReadThroughContent`
  set. Reason generic. Nothing from mail stored or sent; dedupe is a last-checked time.
- No Gmail grant is "cannot see mail", on the screen and at set time, never "no replies".

## Verification

- `MailReplyCheckTest`: matching, first check starts the clock, only his name in the line, not-now
  retry, cannot-see, no watch reads no mail, 15 min cadence.
