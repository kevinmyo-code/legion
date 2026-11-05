---
map: alfred-ota
ticket: "08"
title: "Later: shrink the APK, head unit, worker publish"
type: build
status: kiv
status-detail: "parked 2026-11-05; after 07"
blockers: ["07"]
blocked-by: ["[[07-acceptance-run]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Later: shrink the APK, head unit, worker publish

- **Shrink the APK.**
  - Move the voice and wake models out of the APK into a one-time download to the app's files, hash-checked, from the same bucket.
  - Updates would drop from about 200 MB to the size of the code.
  - Needs a ruling: CLAUDE.md §7 says assets are bundled and never fetched at runtime. ADR 0058 is the only exception today.
- **Head unit.** The same updater in `headunit/`, which is a separate project with its own signer. Least important (Kevin, 2026-11-05).
- **Worker publish.**
  - The worker's `legion-chores` skill builds the APK, asks Kevin, then runs the publisher.
  - It needs the signing key on the Alienware. It has it today, because the worker runs on this same laptop.
- **Web desk.** A Releases page listing each version and its notes.
