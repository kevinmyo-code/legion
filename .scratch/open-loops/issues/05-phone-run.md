---
map: open-loops
ticket: "05"
title: "Run open loops on the phone"
type: test
status: open
status-detail: "owed by Kevin after the engine deploy, the worker update and an install"
blockers: ["04"]
blocked-by: ["[[04-worker-update]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Run open loops on the phone

## Steps

1. Say to Alfred, in an ordinary turn: "I need to call the dentist." (No "remind me".)
2. After the next night's review: Brief me. The brief asks once whether to track "call the dentist",
   names no time, says nothing about it being undone.
3. "Yes" in the next turn: tracked; it is on the Todo list. A dated one ("renew the registration by
   November 20") becomes a reminder on that day and fires like any reminder.
4. Another one: "No": it never comes back when said later in the same words.
5. Right after the wake-up brief, "yes" is refused once (that turn read web news); saying it again
   works; Settings, Proactive speech shows Track and Dismiss for the same rows.
6. "Stop proposing tasks": the switch under Digest is off; the next night proposes nothing.
