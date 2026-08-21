---
map: proactive-mode
ticket: 12
title: "Retire ambient cabin listening"
type: bug
status: built
status-detail: "2026-08-21, Kevin - retired; owes a run on the phone to confirm the wake word still arms"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Retire ambient cabin listening

## What this was

`service/AmbientListener.kt` held the microphone open in 45-second windows, ran a `SubAgent` over
whatever it overheard, and asked it to answer `SILENT` or else return **the exact line to speak** -
which the raise then passed to the live model verbatim.

It is the only raise in LEGION where the model, not the app, decided both **that** there was
something worth saying and **what** it was.

## Why it went

**Kevin's call, 2026-08-21**, taking the third option of three ([ticket 10](10-what-a-raise-may-say.md)
point 4 asked whether to exempt it, constrain it, or retire it).

1. **It cannot satisfy [ticket 10](10-what-a-raise-may-say.md)'s contract, even in principle.** The
   rule is that an unsolicited prompt states the facts of any subject it invites the model to
   mention. Here there are no facts to state - the sub-agent authors the sentence. Constraining it
   (a structured claim the app checks before speaking) was the alternative and is real work for a
   feature nobody could turn on.
2. **It is the live instance of [ticket 02](02-trigger-engine.md)'s shape (b)**, the option whose
   named failure mode is "it can invent a reason to speak". With 02 settling on the hybrid, keeping
   a (b)-shaped raiser beside it would mean the map's own architecture had an exception in it on
   day one.
3. **It could never actually run.** `AmbientListenPreferences.setEnabled` had **zero callers
   anywhere** and the flag defaults to `false`. Same shape as the mute switch before
   [ticket 01](01-one-gate-not-three.md): a preference with a reader and no writer. So this is dead
   code, not a working feature being taken away.

## What was removed - 2026-08-21

- `service/AmbientListener.kt` and `service/AmbientListenPreferences.kt`, deleted.
- `AriaForegroundService`: the `start`/`stop` calls and **the real-time mute collector**. That
  collector existed so flipping mute stopped the microphone immediately; nothing else in the
  service holds the mic open, so there is no listening left for a mute flip to stop.
- `WakeWordEngine.kt:139`'s suppression check. **This was always a no-op**: ambient supposedly
  superseded the narrower wake word, but the flag it read was permanently false, so the wake word
  was never actually suppressed by it. Behaviour is unchanged - the comment claiming otherwise is
  what went.
- `ui/SettingsRows.kt`'s status line said the master switch **"stops ambient listening too."** It
  no longer does, because there is none. A switch that promises to silence something that does not
  exist is the same class of lie the row was written to avoid.

## Verification

- `compileDebugKotlin -Pnokey` green, `testDebugUnitTest` green. No test referenced it.
- **OWED, on the phone:** confirm the wake word still arms. It is the only behaviour that shared a
  guard with this feature, and the guard's removal is reasoned-to-be-a-no-op from the flag's
  default and its missing writer - not observed on the device.

## Left standing, deliberately

`ui/SettingsRows.kt` hardcodes **"Alfred"** in the proactive-speech copy, which CLAUDE.md §1
forbids - the companion is user-named per profile. The row takes no name parameter, so fixing it is
plumbing rather than a word change. Not this ticket's; filed here so it is not lost.
