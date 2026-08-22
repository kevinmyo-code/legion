---
map: wake-word
ticket: "06"
title: "Prove hey-name fires on the A25, screen off, on battery"
type: task
status: open
status-detail: "Step 1 CONFIRMED on the A25 2026-08-20 by Kevin: he said hey alfred, it went to listening. Steps 2-4 (screen off on battery, survives a call/Spotify/live turn, name follows the profile) are untouched."
blockers: ["01", "02", "05"]
blocked-by: ["[[01-mic-under-doze]]", "[[02-the-settings-toggle]]", "[[05-mic-ownership]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Prove hey-name fires on the A25, screen off, on battery

## Question

Nothing to decide. The wake word has **never run on this phone**. Its only on-hardware validation was
2026-07-19 on a head unit, against premises that phone-only retired.

Establish, on the A25, with the APK hash-verified per the standing rule:

1. **It fires at all.** Say "hey <name>" with the app foregrounded. Confirm `ACTION_TALK` and confirm
   a live turn actually opens - the broadcast firing is not the same as the assistant answering.
2. **It fires with the screen off**, phone on battery, after it has been idle long enough to be a
   real test rather than a demo. How long is set by whatever ticket 01 found about Doze.
3. **It survives a phone call**, a Spotify playback session, and a live turn - then still fires
   afterwards. Ticket 05's rules are the spec for what SHOULD happen; this checks what does.
4. **The name follows the profile.** The grammar is built from `CompanionProfile.name`. Rename the
   companion, confirm the new name triggers and the old one stops.

Report failures with the logcat that shows them. `WakeWordEngine` keeps a 20-event debug ring
(`EVENT_LIMIT`) for exactly this - use it rather than guessing from behaviour, and remember the app's
tags do reach logcat on this device, so an absence of log lines is evidence of a silent code path,
not of a broken channel.
