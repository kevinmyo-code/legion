---
map: retro-games
ticket: "01"
title: "Games: launcher or aspect, how to read the ROM folder, and box art"
type: decision
status: resolved
status-detail: "Kevin ruled 2026-10-11"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Games: launcher or aspect, how to read the ROM folder, and box art

## Resolution (Kevin, 2026-10-11)

1. **Launcher, not an aspect.** No records, no engine. Picked prototype A (Games chip + shelves
   sheet) over B (nav tab + full-screen grid) and C (resume strip + list); all three in `research/`.
2. **Folder grant (SAF tree)**, not `MANAGE_EXTERNAL_STORAGE`.
3. **Box art fetched once per game from libretro-thumbnails and cached on the phone.** Scoped
   exception to CLAUDE.md section 7, recorded in ADR 0058.

Build: ticket 03.
