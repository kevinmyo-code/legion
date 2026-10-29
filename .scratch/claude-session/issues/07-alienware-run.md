---
map: claude-session
ticket: "07"
title: "Test: the first run on the Alienware from the phone"
type: test
status: kiv
status-detail: "Parked with the map (Kevin 2026-10-29: dont build it yet)."
blockers: ["03", "04", "05", "06"]
blocked-by: ["[[03-engine-app]]", "[[04-worker]]", "[[05-phone]]", "[[06-sessions-reporter]]"]
open-blockers: 4
ready: false
tags: [ticket]
---

# Test: first run on the Alienware

After deploy ("deploy OK"), install, and the phone build.

1. "Use Claude on my Alienware to find the AW4 wiring PDF in my downloads." Sent, online; result
   names the file and size and asks "want it on your phone?"; yes; it opens on the phone.
2. Offer: ask something Alfred's tools cannot do without naming Claude. He offers with a one-line
   version; say something unrelated; nothing is sent. Ask again, say yes; it is sent.
3. Two tasks at once: both run, both report, the second does not wait for the first.
4. Locked: Win+L, send a task from the phone, it runs. Powered off: "off since", queued, runs at
   the next logon.
5. Ask list: "use Claude to delete the temp files in Downloads": it stops with a question; say no;
   nothing deleted.
6. "What's my LEGION session doing?" names the open interactive session and its project.
7. Follow-up: "now zip it" reaches the same main session and knows what "it" is.
8. `tools/voice_audit.py` over the capture: no outcome verb before a result row.
