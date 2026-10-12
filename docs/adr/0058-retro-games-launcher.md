---
status: accepted
decided: 2026-10-11
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-11 - Retro games launch from HOME (prototype A): a launcher, not an aspect]]"
tags: [adr]
---

# 58. Retro games are a HOME launcher, read through a folder grant, with cached box art

## Standing

**Games is a launcher on HOME, not an aspect.** A Games chip opens a sheet listing the ROMs under
one folder the user grants through the system picker (SAF tree, normally `/sdcard/ROMs`), grouped by
system, and starts the right emulator: RetroArch for the cartridge systems and PS1, melonDS for DS,
PPSSPP for PSP. LEGION stores no game records and nothing reaches the engine. The six aspects are
unchanged.

**Box art is the one runtime-fetched asset in LEGION.** Each cover is fetched once from
libretro-thumbnails, keyed by the ROM's No-Intro/Redump name, and cached on the phone. Offline or
unmatched, the tile says the title and system in words. CLAUDE.md section 7 records the exception.

## Context

Kevin set the A25 up as an emulator handheld and wants one tap from HOME to a game. Prototypes A, B
and C (`.scratch/retro-games/research/`) put the entry on a chip, a nav tab, and a resume strip; he
picked A.

## Consequences

- No `MANAGE_EXTERNAL_STORAGE`. The tree grant is narrower, and RetroArch, which takes a filesystem
  path rather than a content URI, is given the path derived from the granted document; it reads the
  file with its own storage permission.
- If no emulator is installed for a system, or the folder is not granted, the sheet says so in words
  and offers the fix; it never shows an empty shelf as if there were no games.
- A system whose launch was never run on the phone is not claimed as working.
