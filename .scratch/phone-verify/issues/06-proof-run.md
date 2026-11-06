---
map: phone-verify
ticket: "06"
title: "Proof run on the phone, no install"
type: test
status: open
status-detail: "not run 2026-11-05: phone last seen 19:38 CDT on wireless debugging, screen off and keyguard up, 263092342 fast installed (not older than the threshold, read-only checks only); it dropped off adb and mDNS minutes later and stayed off through 20:20. Owed: wake it, unlock, run the steps below"
blockers: ["01", "02", "03", "05"]
blocked-by: ["[[01-phone-cli]]", "[[02-safety-rails]]", "[[03-screen-map]]", "[[05-skills-and-agents]]"]
tags: [ticket]
---
# Proof run on the phone, no install

The `verify-on-phone` skill end to end, on whatever build is installed, installing nothing:

1. `preflight`; `version --min 263092342` (older: report it and stop).
2. `logcat --mark`.
3. `open settings/memory`, screenshot, read the PNG.
4. `open vault` (taps: Data and privacy > ID vault), `screenshot --secure-check`: expect
   `blank: true` (FLAG_SECURE). `dump` shows the labels with no value; report a count only.
5. `back`, `open home`, `logcat`: no LEGION crash since the mark.
6. One `--by-taps` walk (`settings/memory`) to prove a code-derived reach path.

Needs the phone awake, unlocked and on wireless debugging. A locked phone is Kevin's to unlock.

## 2026-11-05 attempt

`adb devices -l` at 19:38 CDT: SM_A256U on wireless debugging. Read-only: versionCode 263092342,
versionName 2026-11-05-c353bcf, `fast` (no DEBUGGABLE), battery 52% not charging, screen off,
keyguard showing, LEGION's MainActivity resumed. Nothing was tapped or captured while it was
locked. It then left adb and mDNS (wireless debugging drops when the phone sleeps) and did not come
back by 20:20; `tools/phone connect` to its last address timed out. No step above ran.
