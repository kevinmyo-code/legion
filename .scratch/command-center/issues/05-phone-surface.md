---
map: command-center
ticket: "05"
title: "The phone gets buttons for its own calls"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The phone gets buttons for its own calls

Survey: `answer_call`/`decline_call` have zero UI callers - the ADR 0035 founding case. `place_call`
by hand only exists as a debug screen with a hardcoded number.

## Build

1. **Ring-time actions**: the incoming-call announcement path posts/updates a notification - put
   ANSWER and DECLINE action buttons on it, wired to the same `CallActions.answer`/`reject`. If an
   in-app banner is simpler and honest, that too - but the notification actions are the part that
   works with the phone locked in a mount.
2. **A dial affordance**: contact search (same `PhoneLookup` query `PlaceCallAction` uses) + a
   number field, read-back/confirm step preserved ON SCREEN (show the resolved target, CALL is the
   second tap - the confirm gate survives translation to touch), emergency refusal preserved.

## Rules

- Same `CallActions`/`PlaceCallAction` functions. All four distinct failure sentences survive as
  screen states. Nothing claims a call happened unless the action reported it.

## Verification

- Suite green both ways. On the phone: real incoming call shows the buttons; dial a contact by
  hand with the confirm step.
