---
map: alfred-clock
ticket: "07"
title: "Brief me on the dismiss screen"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Brief me on the dismiss screen

## What

- The alarm ring screen has Dismiss, Snooze and **Brief me**. Brief me dismisses and asks Alfred
  for the sitrep, user-initiated, in one tap. Nothing speaks unless tapped (ticket 32).
- Hidden before the first unlock (the assistant is not direct-boot aware). Offline or no key: the
  assistant's own refusal, plus the screen says the brief did not start.
