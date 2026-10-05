---
status: accepted
decided: 2026-10-04
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-04 - The household bought log, and the assistant on the web]]"
tags: [adr]
---

# 56. The household's key serves the web assistant, and never reaches a browser

## Standing

**The web assistant runs on the household's own Gemini key, held by the engine.** The engine mints a
short-lived token for each signed-in member's browser session. The key itself is never sent to a
browser, stored in one, or typed into one. Each phone still uses its own key.

## Context

Kevin, 2026-10-04, asked for a chatbox and live voice on the web for Mia (iPhone, web app only) and
himself. Offered a household key on the server or a key each person pastes into their browser, he
picked the household key. The engine already holds one (`LEGION_GEMINI_KEY`, used for statement
PDFs).

## Why this is not the "hosted key" CLAUDE.md §7 forbids

§7's ban is on Kevin hosting a key or a proxy for other people. The engine is the household's own
server (ADR 0044), holding the household's own key, serving the household's own members (ADR 0045).
That is the household hosting its own, which §7 permits. A stranger who clones LEGION runs their own
engine with their own key.

## Consequences

- No key in a browser, ever: not in localStorage, not in a bundle, not in a URL.
- Token minting is session-authenticated, rate-limited and audited, and answers in words when the
  key is absent ("The assistant isn't set up on this server").
- Usage bills to one key. A member can exhaust it; throttling is the engine's job.
- Details: `.scratch/web-assistant/` tickets 01, 02 and 07.
