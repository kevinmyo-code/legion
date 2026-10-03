---
map: mapbox-nav
ticket: "11"
title: "Voice tools over the live trip, and the Google hand-off retired"
type: build
status: built
status-detail: "Built 2026-10-03, suite green; owes a run on the phone (list in Built)"
blockers: ["04", "05", "10"]
blocked-by: ["[[04-voice-tool-surface]]", "[[05-who-speaks-the-turns]]", "[[10-route-and-guidance]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Voice tools over the live trip, and the Google hand-off retired

## Build

The tools from 04, calling the same controller the nav screen calls. Spoken turns per 05.

**In the same change:** `open_navigation`'s Google intent path, `NavigationController`, its test and
the `google.navigation` / `geo` `<queries>` entries are removed (ADR 0054). `tools/voice_guide_copy.py`
updated for every new tool.

## Verification

- Unit: every tool's failure result says what did not happen; trip status with no trip says
  "not navigating", never zeros.
- `python tools/voice_guide.py` exits clean.
- On the phone: each tool by voice, while guiding.

## Built (2026-10-03)

**Four tools (04).** `navigation/voice/NavVoiceTools.kt` is the logic; `LiveToolbox` declares
`navigate`, `change_trip`, `trip_status`, `end_trip` and `navVoiceTool` parses arguments and maps the
result (on Main: the SDK and controller are main-thread). Each is a thin wrapper over the controller verb
the nav screen's tile calls; every `success` is the controller's `NavResult.ok`, read from the SDK after the
call, and `navigate` additionally requires the phase to be GUIDING. Failure wording says what did not
happen ("Nothing is navigating", "The trip is unchanged, still going to X").

**Ambiguity (03).** Several plausible places: nothing starts, `success` is false, the result carries the
top pick with its distance, the others, and an instruction to read back and wait. The yes is a second call
with the same words and `choice` (1 is the top pick, the rest numbered in the order read). Candidates are
held in memory for 5 minutes (they are Mapbox results: never stored). A multi-step request (an ambiguous
`via` after a clear destination) remembers the confirmed part so the answer does not loop. `add_stop` uses
the same mechanism.

**Background start (07).** After a successful `navigate` (and `overview` / `recenter`) the tool brings the
nav screen forward: `service/NavScreenOpener.kt` starts `MainActivity` with `EXTRA_ROUTE = navigate` and then
observes `ProcessLifecycleOwner` for up to 2 s. If LEGION is not visible the result says the map could not
be brought up and that guidance is running. The deep-link navigate is single-top so an already-open nav
screen is not stacked.

**Google hand-off retired (ADR 0054).** `open_navigation`, its dispatch, `LiveToolbox.openNavigation`,
`location/NavigationController.kt`, `NavigationControllerTest`, the `google.navigation` and `geo`
`<queries>`, and `MidnightEvents.navigationLaunch` are gone. The `com.google.android.apps.maps` package
query stays (it is not part of the hand-off; the app drawer lists installed apps). The navigation prompt
section in `AriaBrain.kt` is rewritten for the four tools.

**Turn cues (05).** `MapboxNavSdk` registers a `VoiceInstructionsObserver` (Mapbox's own voice player is
never created). `NavCueSpeaker` speaks `announcement()` with on-device `TextToSpeech`, one steady voice,
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, ducking other audio. The decisions are `NavCueArbiter` (pure,
tested): a cue wins; muted drops it; back-to-back cues share one hold and only the newest waiting cue is
kept (an older one is stale); no engine says the cue nowhere, logs, and shows a one-line notice on the
guiding sheet. A cue holds the live session through `AssistantCueBridge`: `GeminiLiveSession` pauses its
AudioTrack (no flush, the reply resumes) and stops forwarding mic audio until the resumed reply has
drained (`CueHoldState`, tested). `MicArbiter.Claimant.NAV_CUE` (below every conversation claimant, above
the wake word) takes the mic from the wake recognizer for the cue; it is refused while a live turn holds
the mic, which is why the live session gates itself.

**Mapbox ToS items (research 01 section 6).** Setup row "Share usage data with Mapbox" over the SDK's own
`TelemetryUtils.setEventsCollectionState`, showing the SDK's answer read back. One-line location disclosure
on the "Where to?" sheet (no disclosure existed).

### Readings and calls to check (nothing here was asked of Kevin)

- **A cue wins over the user too.** A cue during the user's sentence is spoken at once and the mic is gated,
  so the part of the sentence spoken over it is lost. Delaying it would make the turn cue late.
- **`NAV_CUE` ranks below `VOICE_NOTE`.** A cue during a recording is still spoken (and the recording hears
  it); a conversation is never preempted.
- **Highways and ferries have no hands control** on the nav screen (only the tolls tile). Voice-only at the
  parameter level; ADR 0035 does not ask for parameter parity. Said in the voice-guide copy.
- **`trip_status` with no trip returns `success=true`** with a "Not navigating" message (the question was
  answered); `end_trip` with nothing running is `success=true` "Nothing to end". Neither can license an
  outcome verb.
- **`avoid` adds to what is already avoided**; "none" clears.
- A preview started by voice is brought to the screen too (otherwise it would be an invisible preview).

### Verification accounted for (L11)

- Unit: each tool's argument parsing and result mapping against a real controller over the fake SDK
  (success, failure wording, not navigating, ambiguity read-back and the yes, expiry), the cue arbiter
  (cue, back-to-back, muted, mute mid-cue, no engine, refusing engine), the session hold sequence, the
  MicArbiter grid with `NAV_CUE`: **done**.
- `python tools/voice_guide.py` exits clean: **done**.
- On the phone, **deferred to ticket 12 or an earlier walk**: each tool by voice while guiding and with no
  trip; the ambiguity read-back and yes; a voice start with another app in front (does Android let the
  launch through; does the result say so when not); a cue while Kevin is talking and while the assistant is
  talking (pause, resume, never transcribed); mute by voice and tile; cues with the screen off; the TTS
  engine start (and the no-engine notice); the telemetry switch reading back what the SDK says; the
  disclosure line.
