---
map: android-auto
ticket: 06
title: Get a sideloaded build visible in Android Auto
type: task
status: resolved
status-detail: "LEGION visible in Android Auto 2026-08-18, via the Desktop Head Unit over wireless adb - no car, no cable"
blockers: ["02"]
blocked-by: ["[[02-what-a-sideloaded-media-app-needs]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Get a sideloaded build visible in Android Auto

## Question

Nothing on this map can be verified on the head unit until a sideloaded LEGION build actually appears
in Android Auto. This ticket does that work, and it is HITL: the toggles are on Kevin's phone and the
head unit is in Kevin's car.

Not a decision. It unblocks every on-unit verification step the map later depends on, and CLAUDE.md
L11 binds those steps because the rig exists (settled decision 6).

Do, in order:

1. Take ticket 02's answer and add the **minimum manifest surface** to make LEGION visible as a media
   app: the `MediaBrowserService`/`MediaLibraryService` declaration, the
   `com.google.android.gms.car.application` metadata and the `automotive_app_desc` resource. A stub
   that serves an empty browse root is enough - **this is a visibility probe, not the real surface.**
   The browse tree's contents are ticket 08's decision and must not be pre-empted here.
2. On the OPPO A17k: enable Android Auto **developer mode** and the **unknown sources** toggle
   (exact path per ticket 02's findings, which may differ from the folklore).
3. Install the debug build. **Verify the install by sha256**, not by trusting "Success" - see
   `memory/MEMORY.md`, this cost a day's data once.
4. Plug into the head unit. Confirm LEGION appears in the media source list.
5. Record what actually happened for the tickets downstream:
   - Which toggles were needed, and their exact locations on this device
   - Whether the app appeared immediately or needed an Android Auto restart
   - Whether **wired**, **wireless**, or both were tested
   - The Android Auto app version and the head unit's make/model
   - Anything the head unit refused or rendered oddly

If the app does not appear, that is a finding, not a failure - capture the symptom precisely and
raise it, because ticket 02's answer is then wrong or incomplete and the media door (settled decision
2) is in doubt.

The answer records what was done and the facts later tickets depend on, per the wayfinder task-ticket
contract.

## RESOLVED 2026-08-18 - visible, on a rig the map said it would not depend on

**LEGION appears in Android Auto's media source list, and its browse tree renders.** Kevin, looking
at the head unit: "its listed, i see fleet, talk to legion etc. nothing works, as expected."
`on-device`.

**The rig is Google's Desktop Head Unit, not the car.** The map's charting note says "Google's
Desktop Head Unit emulator is worth adding later for fast 'does it render at all' loops; the map
does not depend on it", and [ticket 16](16-can-a-sideloaded-car-app-library-app-run.md) said in
writing that "the map declined to depend on the DHU while charting; that call should be revisited."
It has been. See the setup notes at the end.

### The five recording steps

| Asked | Answer |
|---|---|
| Which toggles were needed, exact locations | Android Auto developer mode (tap the version line ~10x), then **Start head unit server** from the overflow menu. **Unknown sources was never needed** - the app appeared without it. |
| Appeared immediately, or needed a restart? | Immediately, on the first session that projected. |
| Wired, wireless, or both | **Wireless only.** Kevin has no data-capable USB cable, so the wired path is untested and stays untested. |
| Android Auto version, head unit make/model | Android Auto **17.3.662854-release**. Head unit is the **Desktop Head Unit 2.0-windows, build 2022-03-30-438482292**, `config/default.ini`, 800x480 at 30fps, touch input. |
| Anything refused or rendered oddly | See the two failures below - both were the rig, not the app. |

### Item 1 was already built

No code was written for this ticket today. `car/LegionMediaLibraryService.kt`, the
`com.google.android.gms.car.application` meta-data and `res/xml/automotive_app_desc.xml`
(`<uses name="media"/>`) already shipped, from an earlier session's wave 1 and wave 3 work. The
service declares BOTH `androidx.media3.session.MediaSessionService` and
`android.media.browse.MediaBrowserService`, which is what lets Android Auto bind it as a legacy
MediaBrowser client. `traced`, and now `on-device` by the fact that it bound at all.

Install was hash-verified per step 3 - md5 rather than sha256, matching what `MEMORY.md`'s own note
prescribes.

### What the DHU cost to get running - two real failures, both worth knowing

1. **One "Start head unit server" tap arms exactly ONE session.** The first DHU launch connected and
   exited within a second, because DHU is an interactive shell and it was launched detached, so
   stdin hit EOF immediately. That consumed the armed session. The next launch then negotiated TLS
   fine and sat on "waiting for phone" forever, against a server that had already been spent. Hold
   stdin open (`tail -f /dev/null | desktop-head-unit.exe ...`) or it looks exactly like a hang.
2. **1080p over wireless adb does not work; 800x480 does.** With `config/default_1080p.ini` the
   phone logged `CAR.SERVICE.FCD.LITE: timed out at stage FIRST_ACTIVITY_LAUNCHED after 5000
   milliseconds, publishing PROJECTION_NOT_STARTED` and `USB_ISSUE_PROJECTION_NOT_STARTED`, while
   DHU logged `Failed to read from transport - disconnect`. Identical setup on `config/default.ini`
   projected first try. `tested`. The causal claim - that this is bandwidth rather than the
   four-year-old DHU build - is `reasoned` from the two runs differing only in resolution.

### The finding this hands downstream

**The tree renders and nothing responds to a tap.** That is expected for a probe, and it is now a
measured starting point rather than an assumption: [ticket 08](08-what-the-browse-tree-holds.md)
owns what the tree should hold, and [ticket 15](15-the-live-session-can-be-silenced.md)'s live
session has still never run in the car. Whether TALK TO LEGION does anything is untested.

**What this rig still cannot prove**, unchanged from the map's own charting note: car mic, HFP
routing, the real in-call UI, and OBD Bluetooth contention while projecting. Tickets 07, 13 and 14
still need the car.

