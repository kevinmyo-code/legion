---
map: retro-games
ticket: "02"
title: "Emulator launch intents, verified on the A25"
type: research
status: resolved
status-detail: "Device run 2026-10-11"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Emulator launch intents, verified on the A25

Device agent run, 2026-10-11, A25 (SM-A256U). Tags per claim.

## RetroArch (`com.retroarch.aarch64`, 1.22.2): gb, gbc, gba, nes, snes, genesis, n64, psx

```
am start --user 0 -S -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM '<absolute filesystem path>' \
  -e LIBRETRO <core> \
  -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
```

- `ROM` is a filesystem path, not a content URI (on-device).
- `LIBRETRO` accepts a short name (`pcsx_rearmed`) or the full
  `/data/data/com.retroarch.aarch64/cores/pcsx_rearmed_libretro_android.so` (on-device, both launched).
- PS1, `pcsx_rearmed`: **renders** (on-device, `research/psx-renders-on-a25.png`), with an empty
  `/sdcard/BIOS` (HLE BIOS, reasoned).
- Cores installed (on-device): `gambatte` (gb, gbc), `mgba` (gba), `fceumm` (nes), `snes9x` (snes),
  `genesis_plus_gx` (genesis; which of two near-identical variants is unconfirmed),
  Mupen64Plus-Next GLES3 (n64; stem probably `mupen64plus_next_gles3`, reasoned from the label).
  None of these were launched: no ROMs on the phone.
- RetroArch holds READ/WRITE_EXTERNAL_STORAGE (targets sdk 28, legacy storage), so it can read a
  path under `/sdcard/ROMs` (on-device). The `MANAGE_EXTERNAL_STORAGE` appop is `default`.

## melonDS (`me.magnum.melonds`, 2.0.1): nds

`me.magnum.melonds/.ui.emulator.EmulatorActivity` handles action `me.magnum.melonds.LAUNCH_ROM`
(category DEFAULT) (on-device). The ROM extra is `uri` per Daijisho's template (reasoned, unverified).

## PPSSPP (`org.ppsspp.ppsspp`, 1.19.3): psp

`org.ppsspp.ppsspp/.PpssppActivity` has a VIEW filter for file and content schemes, `.iso .cso .chd
.elf .pbp` (on-device, dumpsys). Resolution of a VIEW with a real file is not proven.

## Dropped

Lemuroid: only `lemuroid://` with an internal library id. No ROM-path launch.
