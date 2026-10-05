---
map: web-assistant
ticket: "07"
title: "Engine: the token-minting endpoint and the session-authenticated tool path"
type: build
status: built
status-detail: "Engine built, pytest 1504 passed / 44 skipped; owes the deploy, a real mint and a real browser connect"
blockers: ["02", "04"]
blocked-by: ["[[02-who-runs-the-tools]]", "[[04-web-tool-surface]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Engine: the token-minting endpoint and the session-authenticated tool path

## Build

Per 02 and 04: a session-authenticated endpoint that mints a Live ephemeral token from
`LEGION_GEMINI_KEY` (rate-limited, audited, refused in words when the key is absent), and the tool
path the browser calls, through `visible()`, leak-tested. pytest green.

## Built (2026-10-04, feat/web-assistant-engine)

- **`server/assistant/`**, a new app. `POST /api/assistant/session` mints a Gemini Live ephemeral
  token (`POST v1beta/auth_tokens`, key in `x-goog-api-key`) carrying the WHOLE setup with no
  `fieldMask`, so the browser's own `setup` is ignored: model `gemini-3.8-live`, the member's
  companion voice, the engine-assembled prompt, every tool `BLOCKING`, input and output
  transcription, the phone's VAD and compression. `uses` 1, connect within 60 s, expires in 30 min.
  No key: 503 "The assistant isn't set up on this server." Google failing: 502/503 in words.
- `POST /api/assistant/tool` runs `engine_mcp.tools.run_tool`, the function `/mcp` runs: one
  registry, two doors. Session auth + CSRF only (a device token is refused; `/mcp` still refuses a
  session). Unknown or phone-only names: 400 in words, with a `response` ready to forward.
- Both doors throttled per member (`LEGION_ASSISTANT_SESSION_RATE` 30/hour,
  `LEGION_ASSISTANT_TOOL_RATE` 60/min) and audited in `public.assistant_calls` (who, when, door,
  tool, outcome; never prompt, arguments, results, key or token).
- **One prompt source (ticket 03):** `server/assistant/shared_clauses.txt` ->
  `gen_shared_clauses.py` -> `shared_clauses.py`. `tests/test_assistant_prompt.py` fails on a stale
  copy, and reads `AriaBrain.kt` as text to fail on drift: `ASSISTANT_FRAME`, `CANNOT_CLAUSE`,
  `PROACTIVE_CLAUSE` whole; the record-facts, currency and crisis clauses verbatim inside
  `SHARED_INSTRUCTIONS` / `safetyInstructions`. `UNREADABLE_IS_NOT_EMPTY` is web-only for now.
  The Kotlin side is NOT rewired yet, and no TypeScript copy is emitted (the web gets the prompt
  inside the token, so it needs none).
- **Companions per member:** `public.assistant_companions` (name, persona key, optional custom
  register, voice). Personas copied from `Personas.kt` into `assistant/personas.py` under a drift
  test. Migration 0002 seeds every existing member: first name "Mia" gets Dorothy, everyone else
  Alfred; a member with no row gets the same rule computed on read. `GET /api/assistant/companion`
  reads it; `manage.py set_companion` changes it. No REST write path, and the phone does not push
  its roster yet.
- **`last_ticked`** joins the registry (both doors): ADR 0049 wording, `TickMatch`'s exact
  normalised match, read through item and checklist tombstones, unticked taps excluded.
- `manage.py mint_assistant_token --email X [--dry-run]` for the manual real mint.
- Leak tests: `tests/test_assistant.py` (Mia vs Kevin's private rows, another household, phone-only
  names) and `last_ticked` added to `test_tenancy.py`'s MCP sweep.

Owed: deploy (two migrations, no `private.*`), a real mint with the household key
(`mint_assistant_token`), and a real browser connect with the token (ticket 08/10). The full lock
means no session resumption: a conversation ends at Google's ~10-minute connection limit
(`goAway`) and the browser mints again.
