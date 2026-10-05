---
map: web-assistant
title: "The assistant on the web, and typed chat on the phone"
charted: 2026-10-04
charted-by: "Kevin + Opus"
effort: "`.scratch/web-assistant/`"
tickets: 10
open: 5
status: open
tags: [map]
---

# The assistant on the web, and typed chat on the phone

**Kevin, 2026-10-04:** *"give the web/phone app a chatbox + push to talk same like my android app"*.

## Destination

**Shipped and used.** Mia's iPhone PWA and Kevin's web workbench each have a chatbox and a live
voice conversation with the household assistant, reaching everything the engine holds; Kevin's
Android app gets a typed chatbox beside push-to-talk. Execution is in scope.

## Rulings at charting (Kevin, 2026-10-04)

- **Where:** Mia's PWA (iPhone), Kevin's web desktop, and a typed box on Kevin's Android app.
- **Web voice is a live conversation**, like the Android app (open mic, back and forth,
  interrupting), not walkie-talkie. Feasibility on an iPhone PWA is ticket 01.
- **Scope: everything the engine has.** Private rows stay private to their owner (ADR 0052): Mia's
  assistant never sees Kevin's private rows, and the reverse.
- **Key: the household key on the server.** The engine already holds `LEGION_GEMINI_KEY`; it mints
  short-lived Live tokens per browser session and the real key never reaches a browser. ADR 0056.
- **Android typed chat answers in text only**; push-to-talk stays spoken.
- **History: this session only.** Closing the chat clears it; nothing new is stored.

## What exists (traced 2026-10-04 on origin/dev 59fec9b6)

- Web: Vite + React + TanStack, shadcn, PWA service worker, web push (`server/frontend/`). Family
  view under 1024 px, workbench above (ADR 0053). **No assistant, chat, mic or speech code at all.**
- Engine `/mcp` (`server/engine_mcp/`): 11 tools through `visible()`, device-token only by design
  ("the browser has no business here"), off unless `LEGION_MCP`, throttled, audited (`McpCall`).
- Django calls Gemini only for statement PDFs (`server/ingest/statements.py`, `LEGION_GEMINI_KEY`).
- Android: push-to-talk is the whole `AssistantStrip`; Gemini Live over a raw WebSocket
  (`service/GeminiLiveSession.kt`), BYO key per phone; tools run on the phone (`LiveToolbox`) plus
  the `ask_engine` bridge to `/mcp`. `GeminiLiveSession.sendText()` exists; no user can type.
- The prompt layer (`ai/AriaBrain.kt`: `ASSISTANT_FRAME`, `CANNOT_CLAUSE`, personas in
  `ai/Personas.kt`) lives only in Kotlin.
- Mia is not yet invited to the web, and nobody has used the PWA on a real device (MEMORY.md
  2026-10-03). Web push is paused until VAPID keys exist.

## Notes

- **The honesty rules travel with the assistant.** `CANNOT_CLAUSE` (§7 outcome verbs), the
  concierge frame, "never hardcode an assistant name", unreadable vs empty: a web assistant without
  them is a second, less honest assistant. Ticket 03 decides how one prompt source serves both.
- **Third-party content stays read-through**: mail never reaches the server, so it cannot reach the
  web assistant. Nothing a chat says is stored (session-only ruling).
- **§7 "no proxy, no hosted key"** is about Kevin hosting for strangers; a household engine using
  the household's own key for its own members is the household hosting its own (ADR 0045). ADR 0056
  records that reading.
- Skills: `/research` for 01, `/grilling` for decisions, `/prototype` for 05.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | research | Gemini Live from an iPhone PWA: ephemeral tokens, mic, playback, lock screen, cost | - |
| 02 | decision | Who runs the tools for a browser session, and how the engine authenticates it | 01 |
| 03 | decision | One prompt for every client: the persona, the frame, the honesty clause | - |
| 04 | decision | The tool surface on the web: engine tools, plus which phone-only ones move | 02 |
| 05 | prototype | The chat and voice UI: family view, workbench, and the Android chatbox | - |
| 06 | decision | Android typed chat: a text turn into the Live session, or a separate text model | - |
| 07 | build | Engine: the token-minting endpoint and the session-authenticated tool path | 02, 04 |
| 08 | build | Web: chatbox and live voice | 03, 05, 07 |
| 09 | build | Android: the typed chatbox | 05, 06 |
| 10 | test | Mia talks to it on her iPhone; Kevin on the desktop and the phone | 08, 09 |

## Decisions so far

- [Gemini Live from an iPhone PWA](issues/01-live-from-an-iphone-pwa.md) - viable with caveats: tokens and locking are solid,
  iOS audio (mic re-prompt, WebKit playback bugs, screen lock) is the risk.
- [Who runs the tools](issues/02-who-runs-the-tools.md) - the browser calls a session-authenticated engine endpoint
  (never `/mcp`); same registry, `visible()`. Mia on iOS 27; build first, test after; screen lock ends it.
- [One prompt](issues/03-one-prompt.md) - shared clauses generated from one file for Kotlin and TS; the engine
  assembles the web prompt; companions per member on the engine; Mia gets Dorothy.
- [Web tool surface](issues/04-web-tool-surface.md) - engine tools + bought log + tick history; phone-only things said so.
- [Android typed chat](issues/06-android-typed-chat.md) - typed turns into the same Live session, reply shown as text.

## Not yet specified

- Wake word on the web (probably never on an iPhone).
- Proactive raises on the web (§7 compulsion test applies).
- Persona per member: does Mia get her own companion name and voice?

## Out of scope

- A chat history store (ruled: session only).
- Any key held in a browser.
- Mail on the web assistant (mail never reaches the server).
