---
map: recruiter-demo
ticket: "04"
title: "Global daily demo budget ($5), reserve before mint, budget-used message"
type: build
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-invite-code-to-visitor-token]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Global daily demo budget ($5), reserve before mint, budget-used message

## What

One budget across ALL recruiter traffic (ruling 4: $5/day).

- Counted in units the engine controls: voice mints x max session minutes, text calls x max tokens.
  Converted to dollars by ONE configurable rate per unit (settings), not a hard-coded Gemini price.
- Reserve before acting: a mint reserves its full max cost first; if that would cross the cap, no
  token is minted. The cap cannot be overshot.
- Max session (Kevin, 2026-11-02): 5 minutes (`LEGION_DEMO_MAX_SESSION_MINUTES`), and a mint reserves
  exactly that. The recruiter token's `expireTime` is mint + 5 min, after which Google rejects the
  session's messages but only "may" close it, so `/try` also ends the conversation at that moment:
  "Demo sessions last 5 minutes. Start another any time." Members unchanged (30-minute token).
- Cap hit: `/try` and the assistant say "Today's demo budget is used up. Try again tomorrow, or watch
  the videos below." Text outlives voice (Kevin, 2026-11-02): voice stops at $5 minus a text reserve
  (setting `LEGION_DEMO_TEXT_RESERVE_USD`, default $0, Kevin 2026-11-02: typed chat shares the Live
  session, so one cap applies); text runs until the full $5.
- Optional: a separate Gemini key for demo traffic, so Google's side has a ceiling too.

## Verify

- Test: concurrent mints at the edge of the cap never sum past it.
