---
map: alfred-ota
ticket: "02"
title: "Phone reads the pack"
type: build
status: open
status-detail: "charted 2026-11-05; after 01"
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
