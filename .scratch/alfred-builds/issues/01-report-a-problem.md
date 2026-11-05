---
map: alfred-builds
ticket: "01"
title: "Report a problem: voice and button"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Report a problem: voice and button

## What
- **Voice tool `report_problem`** (phone; the web assistant too):
  - Kevin says "that was wrong", "fix that", or "that's not right, it should have said X".
  - The tool takes his words plus the current conversation record id and the last reply's index.
  - It creates a Claude task (`claude_session` engine API) with kind `fix`, the record id, and his words verbatim.
  - Alfred says "Sent to Claude. I'll tell you when a fix is ready."
- **Button:** a long-press on a reply in the conversation screen opens "Report", with an optional note. It calls the same controller.
- **Feature and content requests** need no new tool. They go through the existing `claude_session` tool with a `kind` hint (`feature`, `content`), and the worker picks the skill.
## Verification
- JVM tests: tool args, refusal wording when the worker is offline.
- Voice guide copy.
