---
map: android-auto
title: "Map: LEGION in the car (Android Auto)"
charted: 2026-08-13
charted-by: ""
effort: "`.scratch/android-auto/`"
tickets: 16
open: 10
status: open
tags: [map]
---
# Map: LEGION in the car (Android Auto)

## Destination

**LEGION is reachable from the driver's seat without handling the phone.**

It appears in **Android Auto's media list**. Tapping play does not play music - it places a
**self-managed telephony call** to LEGION, so the live Gemini session holds the **car's** microphone
and speaks through the car. The media browse tree also carries tap-to-hear briefings, and
`onPlayFromSearch` answers "Hey Google, ask LEGION X". Same brain, same 69 tools, one car-aware
prompt variant.

Reached when: every decision needed to build that is made, **including the ruling on what happens if
Android Auto refuses to render a self-managed call** - the single fact the whole shape rests on.
**Destination is DECISIONS**, like `.scratch/notes-lists-calendar/`, not SHIPPED. Build tickets
graduate out of the fog once the decisions land, the way `google-account-integration` did.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v16), `com.kevin.legion`. Branch
`feat/cyberdeck`. Read `CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding
anything. Most of `memory/library/` is FROZEN Midnight AI history and carries a status banner.

**Why this map exists.** Kevin, 2026-08-13: he wants to use the app in the car. The phrase used was
"as a widget", and charting immediately corrected it - **Android Auto has no third-party widget
surface**, and the only doors for a projected phone app are the Car App Library templates (fixed
categories) and `MediaBrowserService`. The disguise question was put to Kevin directly and answered.

**The repo starts from nothing here.** Checked at charting: no `androidx.car.app`, no
`MediaBrowserService`, no `automotive_app_desc`, nothing car-shaped in `AndroidManifest.xml`. There
is a `service/MediaNotificationListener` and a `media/` package (MusicController, SpotifyController),
but no media *publisher*. Clean slate.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for the HITL tickets,
`/research` for research tickets, `/prototype` where a surface question needs something to react to.

**The test rig is a real head unit.** Kevin has Android Auto in his car and can plug the OPPO A17k
in. That is the only rig that can prove car mic, HFP routing and the real in-call UI, so on-unit
verification steps are **performable** and CLAUDE.md L11 binds them fully - no on-unit step gets to
be a footnote here. Google's Desktop Head Unit emulator is worth adding later for fast
"does it render at all" loops; the map does not depend on it.

### Settled while charting (Kevin, 2026-08-13) - binding on every ticket

Constraints, not open questions. A ticket that contradicts one of these is wrong.

| # | Decision | Consequence |
|---|---|---|
| 1 | **Two surfaces, deliberately: a media app AND a self-managed call.** | Media is the door, the call is the room. Neither alone is the destination. Roughly double the work and Kevin took it with that said. |
| 2 | **The media surface starts the call.** Tapping play places the call; it does not play audio. | There is no LEGION icon in Android Auto's app grid for a calling app - the in-call screen only exists once a call exists. This is *why* both surfaces are needed, not a nicety. |
| 3 | ~~**The call disguise is chased for the MICROPHONE, not the metaphor.**~~ **AMENDED 2026-08-13 by tickets 01 and 04, hours after charting.** | The premise was that a telecom call is the *only* way to the car's echo-cancelled HFP mic. **It is not the only way, and it does still work.** Ticket 04: `MODE_IN_COMMUNICATION` + `setCommunicationDevice(TYPE_BLUETOOTH_SCO)` are public API any foreground service may use, so Telecom is a managed wrapper, **not a privilege gate**. Ticket 01: a self-managed call **is** displayed on connected Bluetooth devices, so the car sees it over HFP and the uplink is the car mic over SCO. **Two routes to the same microphone, neither needing Android Auto to render anything** - and they are alternatives, not a stack (Telecom forbids calling `setCommunicationDevice`). What the call uniquely buys is now only the in-car UI and an Android 17 background-audio exemption. **Settled decision 1 is re-opened and must be put to Kevin: ticket 07 case 4.** |
| 4 | **Same brain, same 69 tools, one car-aware prompt variant.** | No car-safe tool allowlist, no read-only mode. Rejected explicitly: a second surface to keep in sync as tools are added. Driving safety is carried by the *prompt*, not by removing capability. |
| 5 | **Push-to-talk is tap-to-start / tap-to-stop.** | Android Auto has no press-and-hold gesture; every control is a single tap for driver-distraction reasons. Any design assuming hold-to-talk is wrong. |
| 6 | **The rig is a real head unit.** | On-unit verification is possible, therefore binding (L11). |

## Decisions so far

<!-- one line per closed ticket -->

- [What does a sideloaded media app need to appear in Android Auto?](issues/02-what-a-sideloaded-media-app-needs.md)
  — **the door opens, and Google says so in as many words.** Android Auto's developer mode
  **explicitly covers media apps and explicitly does not cover Car App Library apps** - which
  vindicates the disguise choice for a reason nobody had while charting. The gate is two steps
  (developer mode, then an Unknown sources switch inside it) and **its persistence across an Android
  Auto update is undocumented**, so ticket 12's central question gets an on-unit experiment rather
  than an answer. Manifest surface is four pieces, `media3` `MediaLibraryService` is the documented
  path, and **nothing documented punishes a media app that never plays audio** - `STATE_CONNECTING`
  is the sanctioned holding state, which is exactly what settled decision 2 needed. Category review
  is a Play release-track gate that a sideloaded app never enters (`inferred`, not stated by Google).
  Two things handed onward: distraction limits **degrade silently** and `CarUxRestrictions` is
  AAOS-only so LEGION cannot read them (ticket 08), and the docs **require** `ACTION_PAUSE` and
  `ACTION_STOP`, so someone must rule on what pausing a phone call means (ticket 08).
- [Does voice search still deliver a raw spoken query?](issues/03-does-voice-search-still-deliver-a-raw-query.md)
  — **no, and charting's claim was wrong.** `onPlayFromSearch` fires, but `query` is documented as
  music entities re-joined, not the sentence: "Play Live from Moderat on SoundCloud" arrives as
  `"live moderat"`. The full sentence sits at the **undocumented** `android.intent.extra.user_query`,
  which cannot be a contract. **App-name routing is unsettled and the one primary signal cuts
  against it** - Google's only statement on invoking an app by name is Play-Console-keyed, and LEGION
  will never be indexed; the Assistant-to-Gemini NLU cutover (2026-09-04) adds more risk. Empty query
  ("play LEGION") is triply documented but **still contains the app name**, so it dodges parsing, not
  routing. **The genuinely name-free entry point is tapping the media list, which is settled decision
  2** - this finding strengthens it and weakens the destination's third leg. `onSearch` is a real
  second channel needing no name match, cheap enough to build regardless. Handed to ticket 08.
- [Who gets the microphone, and what happens to Spotify?](issues/04-microphone-and-audio-focus-while-projecting.md)
  — **the call does not buy the microphone, and settled decision 3 is falsified.** A plain foreground
  service reaches the car's HFP mic with call-grade capture priority via `MODE_IN_COMMUNICATION` +
  `setCommunicationDevice`; Telecom is a wrapper over the same API and explicitly forbids calling it.
  The only *documented* path to the car mic through Android Auto is **`CarAudioRecord`, which needs
  `androidx.car.app`** - the thing this map ruled out of scope, so it may have to come back. Three
  live defects in the repo: `MODIFY_AUDIO_SETTINGS` is missing from the manifest;
  **`GeminiLiveSession.kt:888` uses `VOICE_RECOGNITION`, which is not privacy-sensitive, so the
  Android Auto Assistant silences LEGION's capture with zeroes and no callback**; and Android 14
  forbids starting a `microphone` foreground service from the background, with a telecom call **not**
  on the exemption list - named as the likeliest place the whole design fails.
- [Can OBD keep its Bluetooth radio while projecting?](issues/05-obd-bluetooth-contention-while-projecting.md)
  — **the radios coexist; LEGION's own code is the problem.** RFCOMM rides ACL and HFP audio rides
  SCO, so 2 ACL + 1 SCO is structurally fine and SCO taxes throughput without evicting. Wireless AA
  is *less* risky than assumed (5 GHz, different band); **wired USB is unsettled** and if it does
  drop the head unit's Bluetooth link there is no HFP mic at all. The real finding is `traced` in
  shipped source: `Elm327Io.readUntilPrompt` polls `available()` and never blocks on `read()`, so a
  **quiet** link returns `""`, which `isFailureResponse` reads as a car fault - LEGION runs its
  K-line recovery ritual against a Bluetooth problem, leaves `_connectionState` at `CONNECTED`, and
  **reports the car as fine.** No ACL disconnect callback exists anywhere in `app/src/main`. BLE is
  worse: `GattInputStream.closed` is written and never read. Fix is local to `sendCommand`.
  **Claim is `traced`, not `tested`** - proving it is ticket 13.
- [Does Android Auto render a self-managed call at all?](issues/01-does-android-auto-render-a-self-managed-call.md)
  — **the framework path is open, proved on Kevin's phone, and the risk turns out to be distribution
  rather than telephony.** Android Auto 17.2.662634 ships `CarProjectionInCallServiceImpl` declaring
  both `IN_CALL_SERVICE_CAR_MODE_UI` and `INCLUDE_SELF_MANAGED_CALLS` - exactly what AOSP
  `InCallController` demands before handing a self-managed call to a non-dialer surface (`on-device`,
  gearhead APK pulled and dumped with `aapt2`). **What gearhead chooses to DRAW is closed-source**,
  and every Google page describing the in-call view attaches it to `androidx.car.app.category.CALLING`
  - a beta, Internal/Closed-track-only programme, and Android Auto's unknown-sources option verbatim
  "doesn't apply to apps built using the Android for Cars App Library". **So if gearhead gates on the
  category, the route dies on sideloading.** Surviving regardless: self-managed calls are displayed on
  connected Bluetooth devices, so the car gets the call over HFP and the mic over SCO. **Ticket 07 is
  reframed from "does the mic work" to "what does the driver see and touch".** No RTP needed
  (`setAudioModeIsVoip`, `STREAM_VOICE_CALL`), but **two hard 5-second Telecom budgets mean
  answer/hold/disconnect must never block on a Gemini round trip**. One 30-minute head-unit session
  settles all three unknowns, and `dumpsys telecom` distinguishes "gearhead never got it" from
  "gearhead got it and declined to draw" - different rulings for ticket 07.

- [Can a sideloaded Car App Library app run on Android Auto?](issues/16-can-a-sideloaded-car-app-library-app-run.md)
  — **what Kevin asked for exists and is documented; two gates stand in front of it, and the harder
  one is not technical.** `PaneTemplate` plus a prominent Action is exactly "a push to talk button and
  a display of my aspects", and general-purpose templates carry **no category restriction**.
  Gate one, distribution: real-vehicle testing must use a trusted source and the unknown-sources
  exception explicitly excludes this library - **`reasoned` from three adjacent sentences, never
  stated by Google**, and contested by field reports about `pm install -i com.android.vending`.
  Gate two, **category honesty**: no generic category exists, IOT is closest and covers only the
  Shelly garage door, so LEGION would declare IOT for an app that is mostly not IOT. **That is
  Kevin's judgement to make, not a research finding.** Two routes the map never costed: the
  **Desktop Head Unit is untouched by the distribution gate**, and **Play internal app sharing has no
  car form-factor review** while counting as a trusted source. `CarAudioRecord` needs no category but
  **replaces** the app's own `AudioRecord`; one APK can be both media and template. Out-of-scope
  ruling sharpened, not reversed.

- **VERIFICATION SWEEP 2026-08-16** (Kevin: "check every ticket if built or not. repo is ahead").
  **Nothing closed, and that is the finding.** Nine of the ten open tickets are **not build tickets**
  - 07, 08, 09, 10, 11 and 12 need a RULING FROM KEVIN; 06, 13 and 14 need a HEAD UNIT. Only
  [the live session can be silenced](issues/15-live-session-silenced.md) is partially built, and it
  is annotated in place. Unlike `.scratch/google-account-integration/`, **these tickets are not
  stale docs - they are genuinely blocked, mostly on Kevin.**
  **Three findings from the sweep:**
  1. **Ticket 13's defect is STILL LIVE and its file has never been edited.** `git log --
     vehicle/Elm327Io.kt` returns exactly one commit, the original seed. `readUntilPrompt` still
     polls `available()` and never blocks on `read()`, and `ObdResponseParser.isFailureResponse`
     still returns true for a blank response - **so a quiet Bluetooth link and a car-side "NO DATA"
     are still indistinguishable**, `_connectionState` still reads CONNECTED, and a K-line re-init
     fires at what may be a Bluetooth problem. Same defect class as the clear-DTC transaction rule.
  2. **`isSilenced` only reaches a debug screen** - one production consumer mirrors it into
     `CarProbeLog`; no driver-facing surface reads it.
  3. **A fifth orphan**: `BleTransport.closed` (`:70`) is written by `shutdown()` (`:80`) and
     **never read** - `available()` and both `read()` overloads ignore it.
  **Two deviations already documented in the code but worth surfacing:** ticket 06 said a stub
  serving an empty browse root was enough and that ticket 08's tree contents must not be pre-empted;
  `LegionMediaLibraryService` serves four real rows built from live Room data, with its own doc
  admitting it was "built PROVISIONALLY per Kevin's direct ask". And ticket 14 said the call probe
  must stay on a throwaway branch; those files are on `feat/mission-control`.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Proactive announcements while driving.** `service/ProactiveBus` and `GlanceCardController` exist
  and the fleet aspect is the one aspect that is *most* alive while driving. Whether LEGION may
  interrupt - a coolant warning, a reminder coming due, a place trigger - and through which surface
  (start a call unprompted? a media metadata change? a notification AA renders?) is a real question
  that cannot be phrased sharply until tickets 01 and 04 land.
- **One service or two.** `service/AriaForegroundService` already holds the live session, the mic and
  a foreground notification. Whether the `ConnectionService` and the `MediaBrowserService` are hosted
  by it, wrap it, or are separate components with their own lifecycles is a structural call waiting
  on 01.
- **What the surface does when the call ends and when the car goes away.** Does the session persist
  to the phone, or die with the projection? Touches the same lifecycle ground as ticket 10.
- **Whether `androidx.car.app` has to come back into scope.** Ruled out at charting. Two findings now
  push against that ruling: `CarAudioRecord` (API level 5) is the only *documented* way for a
  projected app to read the car microphone, and the in-call surface appears to need the library's
  CALLING category. Both have non-library workarounds that may hold. **Do not re-open it on
  inference** - ticket 14 settles the rendering half on device first.
- **Build tickets.** This map stops at decisions; the build graduates afterwards.

**Graduated 2026-08-13, the same day as charting, out of the research findings:**
[prove the OBD silent stall](issues/13-prove-the-obd-silent-stall.md),
[does gearhead draw the call](issues/14-does-gearhead-draw-the-call.md),
[the live session can be silenced](issues/15-the-live-session-can-be-silenced.md).
**Two of the three are defects in shipped code, not Android Auto work.** They sit here because this
map surfaced them.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Android Automotive OS** (the built-in, car-manufacturer Android). No hardware, entirely different
  distribution story, and nothing on this map transfers cleanly.
- ~~**A Car App Library templated app**~~ **RE-EXAMINED 2026-08-13 by ticket 16, at Kevin's request,
  and the ruling STANDS - but for different reasons than it was made for.** He asked for "a push to
  talk button, and some kind of UI display to see my aspects", which is not a media app at all; it is
  `PaneTemplate` plus a prominent Action, and that **exists, is documented, and is exactly the shape
  he wants** - general-purpose templates carry no category restriction. Two gates stop it, neither
  architectural. **Distribution:** testing in a real vehicle must use a trusted source, and the
  unknown-sources exception explicitly excludes this library - `reasoned` from three adjacent
  sentences, never stated by Google, and contested by field reports about `pm install -i`. **The
  harder gate is category honesty:** no generic or template-only category exists, IOT is closest and
  covers only the Shelly garage door, so LEGION would be declaring IOT for an app that is mostly not
  IOT. **That second gate is Kevin's judgement, not a technical fact**, and it is why this stays out
  of scope rather than graduating. Two things carried forward: the **Desktop Head Unit is untouched
  by the distribution gate** and gives a real dev loop, and **Play internal app sharing carries no
  car form-factor review** while still counting as a trusted source.
- **Play Store distribution and Google's Android Auto category approval.** LEGION is sideloaded onto
  two phones. Whatever the developer-mode gate costs, it is cheaper than a store listing, and the
  commercial model is dead (CLAUDE.md §2).
- **Wake word as the car trigger.** `assets/vosk-model/` is a README only; wake word has never run.
  Pulling it in would drag unbuilt work onto this map. The media surface is the trigger instead.
