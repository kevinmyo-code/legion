---
map: command-center
ticket: "02"
title: "Settings stops being one long wall"
type: build
status: built
status-detail: "Built: five subscreens, 27 rows reconciled 27, write paths untouched. Finding: quiet hours and the daily cap have no setting anywhere - they are constants; a lever would be a new ticket. Owes the two-tap walk on the phone."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Settings stops being one long wall

Kevin: *"in setup screen especially the proactive levers can be put in a submenu etc."*

`SettingsScreen.kt` (718 lines) + `SettingsRows.kt` (17 row composables) render as one scroll.

## The grouping

Top level becomes a short list of subscreens, each its own route:

1. **Assistant** - persona/companion picker, voice, wake word toggle + phrase, conversation
   behaviour.
2. **Proactive speech** - ALL the category levers (safety/timing/fleet/digest/wellbeing), quiet
   hours, the daily cap, sitrep schedule + modules, wellbeing digest time. One screen owns "when
   may it speak".
3. **Connections** - Google access, Spotify, OBD/vehicle pairing, API keys (Gemini/TomTom/AirNow
   status rows).
4. **Data and privacy** - memory screens, conversation audit export, what is stored and what never
   is (state the read-through rule in user words).
5. **Permissions and diagnostics** - the permission grant rows, probes, logs.

## Rules

- Single-writer patterns survive intact - move rows, do not rewrite their write paths.
- Every existing row lands in exactly one subscreen; none dropped. Diff the row inventory before
  and after and reconcile the count in the report.
- Routes via `LegionRoute` additively.

## Verification

- Suite green both ways. On the phone: every setting reachable in at most two taps, nothing lost.
