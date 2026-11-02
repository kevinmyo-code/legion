---
map: recruiter-demo
ticket: "05"
title: "Guide persona: greet on connect, say what it can do, offer things to try"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-recruiter-role-and-allowlist]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Guide persona: greet on connect, say what it can do, offer things to try

## What (Kevin, 2026-11-02)

*"legion should know its a recruiter from the key, and so on connect it would tell users like >
welcome to kevin's voice app ... 'i can do xxx i can do yyy' what would u like to try today"*

- Companion is Alfred, same name and voice as the phone (Kevin, 2026-11-02). Register stays Alfred's
  but addressed to a stranger: no first-name teasing, no assumptions about who they are. The engine
  picks it from the role; the browser cannot pick another.
- Voice: on connect the assistant speaks first. Greeting names Kevin, says it is a demo on fake data,
  lists 3-4 things it can do, asks what to try.
- Text chat: same greeting as the first message, plus tappable suggestions, e.g.
  "What did I spend on groceries last month?", "When is the car due for service?",
  "Why was one receipt rejected?", "What's Kevin's experience with Power BI?"
- Honesty rules unchanged (CLAUDE.md section 7): verified / estimate / refused still show.

## Open

- Can a locked Live token make the model speak first? Trace `live.py` and live-client; fallback is
  the browser sending one opening client turn.
