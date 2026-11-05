---
map: alfred-ota
ticket: "02"
title: "Phone reads the pack"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-ota-phone, JVM suite green. Owed: Kevin's run (ticket 07) after the engine deploy"
blockers: ["01"]
blocked-by: ["[[01-alfred-pack-format-and-endpoint]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Phone reads the pack

## What

- **`ai/AlfredPack.kt`:**
  - The bundled default is generated at build time from the same `alfred.yaml`, so each APK carries the pack it was built with.
  - It fetches `GET /api/assistant/pack` with the ETag and a 2 s timeout:
    - at app start
    - whenever `AriaBrain.invalidateBase()` runs
  - It caches the last good pack on disk.
- **Merge rules.** The remote pack goes over the bundled one by name, except:
  - Unknown names are ignored.
  - **Locked names always come from the bundle.**
  - A remote pack with a LOWER version than the bundle is ignored, so an old engine never reverts a newer app.
- **Prompt assembly.** `AriaBrain.assembleBase` and `composeIdentityAndRules` read clause text from `AlfredPack.current()` instead of constants. The layer order stays in code.
- **Audit line.** The LIVE_AUDIT `session_start` line gains `pack=<version>/<remote|cache|bundled>`, so every conversation record shows which wording it ran.
- **Engine down, or any 4xx/5xx:**
  - It uses the cached pack, or the bundled one if there's no cache.
  - Nothing is spoken about it; only the audit line records it. This is §7's graceful degradation.

## Verification

- JVM tests:
  - the merge rules: locked names ignored, a lower version ignored, unknown names ignored
  - the cache fallback
  - the audit line
- These existing tests stay green: `AriaBrainHonestyClauseTest`, `PromptRoleNamingTest`, and the payload-size test.
- On the engine, the twins check becomes "the generated Kotlin matches the yaml".

## Built (2026-11-05, branch `feat/alfred-ota-phone`)

- `ai/AlfredPack.kt`: `AlfredPackRules` (the engine's exact locked set, `BUNDLED`, `merge`,
  `parse`), `renderSharedInstructions`, `AlfredPackStore` (ETag fetch inside 2 s, on-disk cache
  `files/alfred_pack.json`, silent fallback), `AlfredPack` (the process's one store). Fetched at app
  start and on every `AriaBrain.invalidateBase()`; a changed pack drops the cached prompt.
- **The bundled pack is today's constants, not a build-time copy of the yaml** (deviation, on
  purpose): `SHARED_INSTRUCTIONS` was split into fixed segments plus the four slot constants
  (`ASSISTANT_FRAME`, `PROACTIVE_CLAUSE`, `RECORD_FACTS`, `CURRENCY_CLAUSE`), so the engine's twins
  test still reads it as text. `AlfredPackTest` pins the bundled rendering byte-identical to
  `SHARED_INSTRUCTIONS`, the bundled version to `packs/alfred.lock`, and each bundled clause to
  `packs/alfred.yaml`'s text. Payload unchanged (~24,498 est. tokens).
- The phone has no slot for `UNREADABLE_IS_NOT_EMPTY` (web only), so it is an unknown name and
  ignored; adopting it costs ~70 tokens the payload ceiling does not have.
- `EngineHttp.getConditional` (If-None-Match, 304 is not a failure). LIVE_AUDIT `session_start ...
  pack=<version>/<remote|cache|bundled>`, the pack the cached prompt was built from;
  `tools/voice_audit.py` shows it in the session header.
- Fixed in passing: the CRISIS twin directive named `safetyInstructions`, which no longer exists,
  so `test_assistant_prompt.py` was red on dev; it now names `SAFETY_INSTRUCTIONS`.
- Verification: merge rules, cache fallback, timeout, audit line: JVM-tested.
  `AriaBrainHonestyClauseTest`, `PromptRoleNamingTest`, `LiveSetupPayloadSizeTest`: green. The
  engine twins check: run without Django (the venv here lacks `cryptography`), all five twins
  match. On-device: owed, ticket 07.
