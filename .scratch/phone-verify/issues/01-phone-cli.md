---
map: phone-verify
ticket: "01"
title: "tools/phone: drive LEGION over adb"
type: build
status: built
status-detail: "built 2026-11-05 on feat/phone-verify; pytest green with fake adb"
blockers: []
blocked-by: []
tags: [ticket]
---
# tools/phone: drive LEGION over adb

`python tools/phone <command>`, Python standard library plus adb (decision 1).

| Command | Does |
|---|---|
| `devices`, `connect [host:port]` | attached devices by model; reconnect wireless debugging over mDNS |
| `preflight` (`status`) | device, build, versionCode, battery, awake, locked, what is in front; exit 0 = ready |
| `version [--min N]` | versionName/versionCode, `fast` or `debug`; exit 2 if older than N |
| `wake` | lights the screen; never unlocks |
| `screenshot [out] [--secure-check]` | PNG; `--secure-check` reads a raw frame and reports the near-black share |
| `dump [--out] [--all]` | fresh `uiautomator dump`, compact nodes: text, desc, tag, bounds, flags |
| `tap --text/--desc/--tag/--xy` | centre of the nearest clickable ancestor; `--contains`, `--index`, `^` prefix |
| `type`, `back`, `home`, `swipe`, `scroll-to` | input |
| `wait-for ... --timeout [--gone]` | polls fresh dumps |
| `screens`, `open <id> [--by-taps]` | the screen map (ticket 03): deep link or taps, then waits for the header |
| `logcat [--mark / --minutes N] [--grep] [--out]` | LEGION's pids, the LIVE_* tags, LEGION crashes, since the phone-clock mark |
| `say "<text>" [--typed]` | `DEBUG_SAY` on debug; the typed chat panel on `fast` |

Every selector command dumps fresh and deletes the dump file on the phone, because a leftover dump
reads exactly like the current screen (`device.md`'s "stale content").

## Verification

- [x] Parsers and commands against fixtures: `tools/tests/test_phone_cli.py`.
- [ ] On the phone: ticket 06.
