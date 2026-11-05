---
map: alfred-ota
ticket: "01"
title: "Alfred pack: format and engine endpoint"
type: build
status: open
status-detail: "charted 2026-11-05; waits on decisions 1 and 2"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Alfred pack: format and engine endpoint

## What

A versioned bundle of Alfred's wording. The engine serves it; the phone (later the head unit too) reads it at session start.

**File:** `server/assistant/packs/alfred.yaml` (decision 1: a repo file). Keys:
- `version`: an integer, bumped on every change. A test checks the content hash against the committed `packs/alfred.lock` and fails if the content changed without a bump.
- `clauses`: name to text, keyed by the SAME names as `shared_clauses.txt` (`ASSISTANT_FRAME`, `RECORD_FACTS`, `CURRENCY`, `PROACTIVE_CLAUSE`, `UNREADABLE_IS_NOT_EMPTY` and the rest).
  - `shared_clauses.txt` stays the source. The pack's clauses are generated from it, plus entries that exist only in the pack, so the web prompt and the phone prompt cannot drift.
- `tool_text`: tool name to `{description, result_prefix}` overrides. Ticket 03 fills this.
- `flags`: name to bool or int, e.g. `idle_reconnect_lazy`, `camera_mode`.
- `extra_rules`: short rules appended to the prompt, e.g. reply in English.

**Locked set (decision 2):**
- The locked names are `CRISIS`, `SAFETY_INSTRUCTIONS`, `CANNOT_CLAUSE`, the ask-before rule (pay, send, post, delete, book) and the no-passwords rule.
- The engine REFUSES to load a pack that names any of them; a test covers this.
- The phone ignores them anyway (ticket 02).

**Endpoint:** `GET /api/assistant/pack?client=phone`, with a household token (any member).
- Returns `{version, sha256, clauses, tool_text, flags, extra_rules}`.
- `ETag` is the sha256; a matching `If-None-Match` gets a 304.
- Recruiters get 403: their web prompt is already built on the server.

**Size cap:** 64 KB serialized, with a test.

**OpenAPI:** regenerate with `manage.py write_openapi`.

## Verification

- pytest covers:
  - the pack loads
  - a content change without a version bump fails
  - locked names are refused
  - a matching ETag gets a 304
  - a recruiter gets 403
  - the size cap holds
- `tests/test_assistant_prompt.py` stays green; the Kotlin twins don't change until ticket 03.
