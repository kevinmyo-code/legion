---
map: android-auto
ticket: 14
title: "Does gearhead actually draw the call?"
type: task
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-get-a-sideloaded-build-visible-in-android-auto]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Does gearhead actually draw the call?

## Question

Ticket 01 got as far as primary sources and the device can: Android Auto's own
`CarProjectionInCallServiceImpl` declares both flags AOSP requires before a self-managed call is
handed to a non-dialer surface, and gearhead really calls `setAutomotiveProjection` on Kevin's phone.
**What gearhead then chooses to draw is closed-source and cannot be established by reading
anything.** Ticket 07 cannot be resolved without it, and ticket 07 is where settled decision 1 gets
re-taken.

One 30-minute head-unit session settles all three remaining unknowns at once.

Build, on a **throwaway branch**, the smallest thing that can answer it:

- A bare self-managed `ConnectionService` (`PhoneAccount.CAPABILITY_SELF_MANAGED`, permission
  `MANAGE_OWN_CALLS`), `setAudioModeIsVoip(true)`, no Gemini, no audio, no Car App Library.
- One button that places the call, and one that ends it.
- A **UI debug surface** for every observation - the A17k filters app logs, so `adb logcat` is not a
  reporting channel (`memory/MEMORY.md`).

Then, plugged into the head unit:

1. **E1 rendering.** Place the call. Does anything appear on the head unit? Full in-call view with
   mute and end, a reduced card, a notification, or nothing at all?
2. **The distinguishing measurement.** Run `dumpsys telecom` while the call is up and read the bound
   in-call services. **"gearhead was never bound" and "gearhead was bound and drew nothing" are
   different findings and produce different rulings in ticket 07.** Do not skip this step; it is the
   entire reason the experiment is worth running rather than just looking at the screen.
3. **E2 audio route.** Confirm ticket 01's surviving claim: the call is displayed on connected
   Bluetooth devices, so the uplink should be the **car's** microphone over SCO. Record which mic is
   actually live.
4. **E3 Assistant preemption.** Invoke "Hey Google" mid-call. Does the call survive, and does capture
   survive? Ticket 04 warns the Assistant can silence a non-privacy-sensitive capture with **zeroes
   and no callback**, so verify by what is heard, not by absence of an exception.
5. Record the Android Auto version and the head unit's make and model alongside the results.

Also test, cheaply, while the rig is set up: whether the unknown-sources switch from ticket 06 has
survived since it was set (ticket 12 needs this and no document answers it).

**Do not build any of the real design on this branch.** It is a probe; it gets thrown away. Its
output is a set of facts for ticket 07, tagged `on-device`.
