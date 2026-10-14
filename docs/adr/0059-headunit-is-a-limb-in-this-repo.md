---
status: accepted
decided: 2026-10-14
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-14 - Midnight moves into LEGION as headunit/, a head-unit-only limb]]"
tags: [adr]
---

# 59. The head unit is a limb of the engine, and it lives in this repo

## Standing

**`headunit/` is LEGION's car head-unit client:** the app formerly in the private MIDNIGHT_AI repo,
package `com.kevin.midnightai`, unchanged so an installed head unit upgrades in place. It is
head-unit only; the phone is `app/`. It signs in to the Django engine and reaches it through the
engine's API and MCP endpoint, never through `app/`'s code.

It is a **separate Gradle project** (`headunit/settings.gradle.kts`, its own wrapper). Building one
never configures the other. Its product rules are `headunit/CLAUDE.md`; the root `CLAUDE.md` wins
for everything repo-wide (git, branching, the public-repo privacy rules, clone-and-run).

## Context

Kevin, 2026-10-14: *"midnight is a part of legion, its a limb of the django engine"*, then *"move
it, one repo. its a head unit only limb. phone we alr have the android app"*. On `dev` it already
authenticated against the engine (`backend/engine/EngineAuth.kt`) and called it over MCP
(`EngineMcpClient.kt`). The old "MIDNIGHT_AI is a frozen archive" rule had stopped being true:
the repo had commits up to 2026-10-13.

## Decision

- Copy, not history: one commit adds MIDNIGHT_AI `dev` at `68b9883`. This repo is public and that
  one private; its history stays there. Before the copy every file was scanned for VINs, MAC
  addresses, coordinates, keys, emails and phone numbers. One work email was redacted.
- The Firebase Crashlytics config (google-services.json) and local.properties stay out of git and are
  copied in by hand. The hardcoded `org.gradle.java.home` was removed (clone-and-run).
- All unmerged MIDNIGHT_AI work was merged to its `dev` first (`integrate/car-fixes`, 39 commits,
  and `fix/now-playing-reconnect`), so nothing was left behind.

## Consequences

- ADR 0001 (phone-only) is narrowed to `app/`, not reversed.
- `headunit/CLAUDE.md` still carries Midnight-era rulings that contradict the root (a paid tier, the
  Zero identity). They bind nothing until Kevin rules on them; the banner at its top lists them.
- The MIDNIGHT_AI GitHub repo is to be archived (read-only, private), not deleted.
