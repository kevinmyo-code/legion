---
map: phone-verify
ticket: "05"
title: "verify-on-phone and maintain-screen-map skills; agent defs use them"
type: build
status: built
status-detail: "built 2026-11-05 on feat/phone-verify"
blockers: []
blocked-by: []
tags: [ticket]
---
# verify-on-phone and maintain-screen-map skills; agent defs use them

- `.claude/skills/verify-on-phone/SKILL.md`: when to drive the phone; the rules (what the CLI
  enforces, and why); preflight, which build is installed and the `fast`/`debug` install rules from
  CLAUDE.md section 6 (put `fast` back, `cmd package compile -m speed -f`); navigate with the map;
  act and screenshot; read the PNG; read logcat; a typed turn with read-only questions; report
  tagged `on-device`.
- `.claude/skills/maintain-screen-map/SKILL.md`: what comes from code versus the copy, the loop, and
  the fix for each drift message.
- `.claude/agents/device.md`: drive with `tools/phone`; the stale-dump note now says why it
  happened and that the CLI re-dumps. `.claude/agents/builder.md`: a phone change is checked by the
  builder when preflight passes, not handed back as "owed on device". No device model named in
  either (TEAM.md).

## Verification

- [x] Read through against the CLI's actual commands and flags.
- [ ] Used end to end on the phone: ticket 06.
