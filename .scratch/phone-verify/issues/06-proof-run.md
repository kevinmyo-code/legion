---
map: phone-verify
ticket: "06"
title: "Proof run on the phone, no install"
type: test
status: open
status-detail: "charted 2026-11-05; phone dropped off wireless debugging during the build"
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
