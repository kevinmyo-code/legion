---
map: retro-games
title: "Retro games, one tap from HOME"
charted: 2026-10-11
charted-by: "Kevin + Opus"
effort: "`.scratch/retro-games/`"
tickets: 3
open: 1
status: open
tags: [map]
---

# Retro games, one tap from HOME

**Kevin, 2026-10-11:** *"revamp the home screen, to quick launch games. like open a modal, list the
games i can launch"*. Picked prototype **A**. ADR 0058.

## Destination

**Shipped, and a game launched from HOME on the A25.** A Games chip on HOME opens a sheet: Continue
(last game played, one tap), then a shelf per system with cached box art. A tap starts the game in
its emulator. No folder granted or no emulator installed is said in words with the fix.

## Tickets

- 01 decision (resolved): launcher not aspect, SAF folder grant, cached box art
- 02 research (resolved): the emulator launch intents, verified on the A25
- 03 build: the Games chip, sheet, ROM scan, box-art cache and launch
