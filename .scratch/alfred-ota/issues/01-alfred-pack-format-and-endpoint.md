---
map: alfred-ota
ticket: "01"
title: "Alfred pack: format and engine endpoint"
type: build
status: built
status-detail: "engine half built 2026-11-05 on feat/alfred-ota-engine (pack, generator, lock, GET /api/assistant/pack, tests). Owed: engine deploy (Kevin); the phone reading it is ticket 02; ADR 0072 not written"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Built (2026-11-05, branch `feat/alfred-ota-engine`)

- `server/assistant/pack.py` (loader, locked set, 64 KB cap), `gen_pack.py` (generate, `--check`,
  `--lock`), `packs/alfred.source.yaml` (hand-edited), `packs/alfred.yaml` (generated, version 1),
  `packs/alfred.lock`; `assistant/pack_views.py`; `tests/test_assistant_pack.py`.
- The two rules with no constant name were named `ASK_BEFORE` and `NO_PASSWORDS`. Locked names
  are refused as a clause, a tool override or a flag, in any letter case.
- `content_sha256` (wording, no version) is what the lock records; `sha256` (whole payload,
  version included) is the ETag.
- A pack the engine refuses is a 503 in words, not a crash at boot.
- Owed: an engine deploy (Kevin); ticket 02 on the phone; ADR 0072 (the map writes it with 01 and 05).
