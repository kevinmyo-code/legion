---
map: retro-games
ticket: "03"
title: "Build: Games chip on HOME, shelves sheet, ROM scan, cached box art, launch"
type: build
status: open
blockers: ["01", "02"]
blocked-by: ["[[01-launcher-storage-boxart]]", "[[02-emulator-launch-intents]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: Games chip on HOME, shelves sheet, ROM scan, cached box art, launch

The spec is prototype A, `research/Main.dc.html`, rulings in ticket 01, intents in ticket 02.

## What to build

- **Chip.** A fifth chip, "Games", in HOME's chip row beside To-dos, Lists, Ideas, More, subtitle
  "N games". Blue tone as in the prototype.
- **Sheet.** Bottom sheet: title "Games", subtitle with the count and folder; a Continue card for the
  last game launched from LEGION (title, system, emulator, when) with a Play button; then one
  horizontal shelf per system that has games, header "<system name>" and the emulator's name, tiles
  of box art with the title under. Tap a tile or Play: launch.
- **Folder.** SAF `OPEN_DOCUMENT_TREE`, persisted URI permission. Systems are the subfolder names:
  gb, gbc, gba, nes, snes, n64, genesis, psx, nds, psp. A game is a ROM file by extension per system;
  for psx, a `.cue` (or `.chd`, `.pbp`) is the game and its `.bin` is not listed separately.
  No grant: the sheet says so in words with a button that opens the picker.
- **Launch.** RetroArch for the eight cartridge/PS1 systems with the ticket 02 command; the `ROM`
  path is derived from the tree document id (`primary:ROMs/psx/x.cue` -> `/storage/emulated/0/ROMs/psx/x.cue`).
  melonDS and PPSSPP per ticket 02. Emulator not installed: say which, in words. Never claim a
  launch happened unless `startActivity` returned without throwing.
- **Box art.** libretro-thumbnails `Named_Boxarts`, keyed by the ROM's file name without extension,
  fetched once and cached in app storage. No network or 404: the tile shows title and system in words
  on a flat colour. Never refetch a cached miss on every open.
- **Last played** is local to the phone (DataStore or prefs): it is a launcher convenience, not a
  record, and never syncs (ADR 0058).

## Verification (L11: each is a gate)

1. `compileDebugKotlin -Pnokey`, detekt, unit suite green (totals from the XML).
2. Unit tests: ROM-to-system mapping, cue/bin collapsing, path-from-document-id, intent extras per
   system, box-art URL building and filename escaping.
3. Screenshot test of the sheet with sample games and with no grant.
4. On the A25: grant `/sdcard/ROMs`, tap Games, see Harvest Moon, tap it, it renders in RetroArch.
