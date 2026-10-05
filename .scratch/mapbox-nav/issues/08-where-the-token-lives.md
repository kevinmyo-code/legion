---
map: mapbox-nav
ticket: "08"
title: "Where the Mapbox token lives"
type: decision
status: resolved
status-detail: "Delegated to Opus; per phone, BYO in Setup, dev key from Gradle"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Where the Mapbox token lives

## Question

The public token is per household (ADR 0054). Where is it stored and entered?

- On each phone, in `KeyVault`, pasted in Setup (the Gemini key's shape).
- On the engine as a household setting, synced to every member's phone (Mia never pastes it).
- Which Setup screen, what it says before a token exists, and how a bad token is reported (said in
  words, not a blank map).

## Answer (2026-10-03)

Delegated by Kevin (*"where the token lives is up to u"*).

- **Product path: per phone, `KeyVault`, pasted in Setup**, the Gemini key's shape. One rule for
  every BYO key in the app, and nothing new on the engine.
- **Dev path: a baked convenience token**, read from the Gradle property `MAPBOX_ACCESS_TOKEN` (user
  `~/.gradle/gradle.properties`, so every worktree sees it) and falling back to `local.properties`.
  `-Pnokey` bakes an empty one. A pasted token wins over the baked one. Same posture as
  `TOMTOM_API_KEY` in `app/build.gradle.kts`.
- **No token:** navigation surfaces say "navigation isn't set up, add a Mapbox token in Setup" in
  words. A token Mapbox rejects (HTTP 200 + `TokenInvalid`, research 01) is said as rejected, never
  rendered as a blank map.
- **Not now:** syncing the token from the engine so Mia never pastes it. Fog, revisit if she
  navigates with LEGION.
