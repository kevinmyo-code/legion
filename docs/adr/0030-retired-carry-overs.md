---
status: locked
decided: 2026-07-31
decided-by: Kevin
source: "decisions.md 2026-07-31"
tags: [adr]
---

# 30. What did not survive the port, and why

## Standing

LOCKED. Five calls made in one sitting over roughly 190 ported files.

## Context

The port from Midnight AI carried code whose reason for existing had died with the car launcher. Each needed an explicit keep-or-kill rather than drifting along unexamined.

## Decision

Music: keep Spotify App Remote for voice playback, retire the mixtape stack. Garage: keep, voice-only, no bespoke screen. Spend gate: retire, with no ledger replacement. Fleet build and mod photos: retire. Tagged places: keep as-is.

## Consequences

- Photo storage is now pantry-ingestion-only. `data/PantryPhotoStore.kt` replaced the browsable album store, and `BuildEntry` lost `photoPath` as a real schema change.
- Whether `media/MusicController.kt` is still wanted alongside the Spotify path was left open and is still open.
- Retiring the spend gate here is unrelated to the LLM spend gate in [[0016-llm-spend-gate-after-deterministic]], which is a different thing that reuses the phrase.
