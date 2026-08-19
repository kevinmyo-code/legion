---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2"
tags: [adr]
---

# 21. The city-pop design language and everything attached to it is retired

## Standing

LOCKED. The retirement is what is standing; the replacement is [[0023-design-language-mission-control]].

## Context

City-pop was Midnight AI's aesthetic and it was load-bearing for a car launcher with a mascot. The pivot killed the premise underneath it.

## Decision

City-pop is dead, and with it the mascot Zero, all generated art, `AvatarStudio`, `OccasionStylist`, `WallpaperPresets`, and the two-identities decision.

## Consequences

- `ui/` was a deliberate clean slate, not a gap. That is no longer true as of 2026-08: it now holds 87 Kotlin files. CLAUDE.md §10 has not caught up.
- Per-driver generated wallpaper is specifically dead. It meant a screen had no stable identity and fought its own content for contrast.
