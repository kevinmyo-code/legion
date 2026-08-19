# Research: who gets the microphone, and what happens to Spotify

Ticket: `.scratch/android-auto/issues/04-microphone-and-audio-focus-while-projecting.md`
Resolved: 2026-08-13
Sources: developer.android.com, source.android.com, android.googlesource.com. Every claim tagged
`documented` (with URL), `inferred`, or `field-report`.

## Headline

**The call does not buy you the microphone.** A plain foreground service that sets
`MODE_IN_COMMUNICATION` and calls `AudioManager.setCommunicationDevice(TYPE_BLUETOOTH_SCO)` reaches
the car's HFP mic through public, permission-light APIs, and gets the *same* capture-arbitration
privilege a telephony call gets, because Android defines "a voice call is active" by the audio
**mode**, not by the existence of a Telecom `Connection`. Settled decision 3's stated justification
does not survive the documentation.

The call surface still earns its place for three other reasons (in-car UI, Telecom-managed routing,
Android 17 hardening exemption). Those are different arguments and ticket 07 should be re-argued on
them. Detail in Q3.

Second headline, and it is a problem for the map's scope: the **only documented way for a projected
app to read the car's microphone through Android Auto itself** is `CarAudioRecord`, which lives in
`androidx.car.app` - the Car App Library the map put **out of scope**. See Q1.

---

## Q1. Can a projected third-party app hold the mic while Android Auto runs?

**Separate two things.** Android Auto projection is a host/UI protocol; LEGION's
`AriaForegroundService` is an ordinary phone service that keeps running underneath it. Nothing in
the docs suspends phone-side `AudioRecord` while AA is projecting. `inferred` (absence of a
restriction is not a documented permission; see experiment E1).

**Arbitration is documented and it is not permission-based.** Android 10+ concurrent-capture policy:

- "Privileged apps have higher priority than ordinary apps"; "apps with visible foreground UIs have
  higher priority than background apps"; "apps capturing audio from a privacy-sensitive source have
  higher priority"; "two ordinary apps can never capture audio at the same time". `documented`
  <https://developer.android.com/media/platform/sharing-audio-input>
- The Assistant is privileged: pre-installed and holds `RoleManager.ROLE_ASSISTANT`. "The Assistant
  can receive audio (no matter whether it's in the foreground or background) unless another app
  using a privacy-sensitive audio source is already capturing." `documented` (same page)
- **The loser gets silence, not an error and not a permission denial.** "The concurrency policy is
  implemented by silencing its captured audio rather than by preventing an application from starting
  capturing." `documented`
  <https://source.android.com/docs/core/audio/concurrent>,
  <https://developer.android.com/media/platform/sharing-audio-input>

**Consequence for LEGION as it stands today.** `GeminiLiveSession` opens
`MediaRecorder.AudioSource.VOICE_RECOGNITION` (`GeminiLiveSession.kt:888`). `VOICE_RECOGNITION` is
**not** on the privacy-sensitive list (`CAMCORDER` and `VOICE_COMMUNICATION` are). So when the
driver says "Hey Google" on the head unit, LEGION keeps a live `AudioRecord` that reads **zeroes**,
with no exception and no callback of its own. Server VAD sees silence; the turn dies quietly.
`documented` for the rule, `inferred` for the LEGION-specific consequence.

**Two fixes, both documented:**

1. Switch the capture source to `VOICE_COMMUNICATION` - privacy-sensitive, so the Assistant does not
   get to capture concurrently *if LEGION started first*. `documented`
2. API 30+: `AudioRecord.Builder.setPrivacySensitive(true)`. "When `setPrivacySensitive(true)`, the
   capture is private and even privileged Assistants cannot capture concurrently." `documented`
   (same page). minSdk is 24, so this needs a version guard.

Note the ordering caveat: "already capturing" is the test. If Assistant grabs first, LEGION loses.
There is no documented way for an ordinary app to pre-empt a started Assistant capture. `documented`
(by absence of any such API on that page).

**Detecting it instead of guessing:** register an `AudioManager.AudioRecordingCallback` before
capture and read `AudioRecordingConfiguration.isClientSilenced()` - "Returns true if audio is
silenced due to capture policy". `documented`. This is the honest way to surface "the car assistant
took the mic" in the UI rather than shipping a dead turn. Given the OPPO A17k filters LEGION's
logcat (auto-memory), an on-screen indicator is the only usable signal.

**The car mic, through Android Auto's own API.** Car App Library API level 5 added
"a new `CarAudioRecord` API to allow recording audio input via the host vehicle's microphone", and
"features annotated with API level 5 are compatible with Android Auto 7.9 and above". `documented`
<https://developer.android.com/jetpack/androidx/releases/car-app> (1.3.0-alpha01, 2022-07-27). Usage
requires a `CarContext` - i.e. a `CarAppService` templated app - and the docs mandate acquiring
`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` before recording and stopping on `AUDIOFOCUS_LOSS`.
`documented` <https://developer.android.com/training/cars/apps/library/car-microphone>

**This collides with the map.** "A Car App Library templated app (`androidx.car.app` ...)" is listed
Out of scope. `CarAudioRecord` is the only *documented* projected-mic path, and a `MediaBrowserService`
media app has no equivalent - the media surface never receives audio, only a parsed string via
`onPlayFromSearch(String query, Bundle extras)`: "Your app receives a parsed query string, not raw
audio". `documented` <https://developer.android.com/training/cars/media/voice-actions>
So the media door cannot itself be the microphone. Either the HFP/SCO route (Q2) works, or the map
must re-open `androidx.car.app`.

---

## Q2. Which physical mic does a foreground service get?

**Default: the phone's own mic.** Nothing routes capture to a Bluetooth device unless the app asks
for the communication device. `inferred` from the routing model below; no doc states a default in
those words.

**To get the car's mic you must put the input on the HFP/SCO device.** Two things must be true, and
AOSP states them as the trigger for bringing the SCO link up: "An active stream is patched to a SCO
device" and "The audio mode is set, and a patch to a SCO device exists". `documented`
<https://source.android.com/docs/core/audio/sco-audio-mgmt>

**The current API (API 31+).** `getAvailableCommunicationDevices()` -> pick the
`TYPE_BLUETOOTH_SCO` entry -> `setCommunicationDevice(device)`, which "selects the audio device that
should be used for communication use cases, for instance voice or video calls". Returns a boolean;
wait for `currentCommunicationDevice` to become the selected device with a timeout (the guide uses
30s) and call `clearCommunicationDevice()` before retrying on error, and again when the call ends.
`documented` <https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager>

**The old API is dead.** `setCommunicationDevice()` replaces `startBluetoothSco()`,
`stopBluetoothSco()` and `setSpeakerphoneOn()`; the AOSP deprecation text on `setSpeakerphoneOn` is
"Use `AudioManager#setCommunicationDevice(AudioDeviceInfo)` or `AudioManager#clearCommunicationDevice()`
instead." "Starting in Android 13 (API level 33), applications must migrate from
`AudioManager#startBluetoothSco()` to `AudioManager#setCommunicationDevice()`". Backward compatible
with HFP devices. `documented` (same guide; deprecation string from
`frameworks/base/media/java/android/media/AudioManager.java`, main branch).
minSdk 24 means a `SDK_INT < 31` fallback branch to `startBluetoothSco()` is still needed if LEGION
must work on the older of the two phones. `inferred`.

**The mode.** `MODE_IN_COMMUNICATION` is the VoIP mode and any app may set it - "SIP calls should
set the audio mode to `MODE_IN_COMMUNICATION`, while audio mode `MODE_IN_CALL` is reserved for
telephony", and `MODE_IN_CALL` "can only be selected by the main telephony application with the
`MODIFY_PHONE_STATE` permission". `documented` (AudioManager reference, surfaced via search; the
reference page itself is JS-rendered and would not fetch, so this is a quoted-snippet citation, one
notch weaker than a direct read - flag it if it becomes load-bearing).

**Missing permission, concretely.** `setMode` and `setCommunicationDevice` sit behind
`MODIFY_AUDIO_SETTINGS` (install-time/normal). LEGION's `AndroidManifest.xml` **does not declare it**
- grep for `MODIFY_AUDIO_SETTINGS` returns nothing; only `BLUETOOTH_CONNECT` is present. `traced`
(read the manifest). Any build ticket on this path adds that line first.

---

## Q3. Does a self-managed call change the answer? THE CRUX

**No. It does not unlock a microphone a plain foreground service cannot reach.** Three documented
legs:

1. **The routing APIs are public.** `setCommunicationDevice()` is a plain `AudioManager` method with
   no Telecom prerequisite anywhere in its guide; the guide's own worked example is an app doing it
   itself. `documented`
   <https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager>
2. **Telecom does the *same job*, and forbids you doing both.** Core-Telecom: "Don't use the
   `AudioManager#setCommunicationDevice` or `AudioManager#startBluetoothSco` APIs to manage audio
   routes when using Telecom; doing so will cause audio issues in your call." `documented`
   <https://developer.android.com/develop/connectivity/telecom/voip-app/telecom>
   That is Telecom offering a managed wrapper over the exact API a plain service would call - not a
   privilege gate. `inferred` from the quote.
3. **Capture arbitration keys off the audio mode, not off Telecom.** "A voice call is active if the
   audio mode returned by `AudioManager.getMode()` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`",
   and in that state the voice call always receives audio while an ordinary app gets silence.
   `documented` <https://developer.android.com/media/platform/sharing-audio-input>
   A plain foreground service that sets `MODE_IN_COMMUNICATION` **is** that voice call for arbitration
   purposes. This is the strongest single fact in this document: it means the mic-priority benefit
   Kevin was buying with a `ConnectionService` costs one `setMode` call. `documented` for the rule,
   `inferred` for "therefore LEGION wins over the AA Assistant" - verify with E2.

**Also: Telecom still does not capture or play audio for you.** Core-Telecom documents endpoint
observation (`currentCallEndpoint`, `availableEndpoints`, `isMuted`) and `requestEndpointChange()`,
but the app keeps its own `AudioRecord`/`AudioTrack`. `documented` (same page). So the call route
does not simplify `GeminiLiveSession` at all; it adds a second routing vocabulary on top of it.

**What the call surface DOES still buy** - keep these, drop the mic argument:

- **The in-car UI.** Map decision 2 already rests on this and it is unaffected. "During active calls,
  Android Auto automatically displays the in-call view provided by your telecom integration."
  `documented` <https://developer.android.com/training/cars/communication/calling>
- **Android 17 background-audio hardening exemption.** On Android 17 all apps need a visible activity
  or a non-`SHORT_SERVICE` FGS to touch audio at all, and apps targeting API 37 need an FGS with
  While-In-Use capability; "VOIP/Video calling apps using Telecom APIs" are listed as not affected,
  and "System-delegated FGS - Started via Telecom jetpack library" is one of the three ways to get
  WIU capability. `documented` <https://developer.android.com/about/versions/17/changes/bg-audio>
  This is a genuine future-proofing argument the ticket did not have.
- **Automatic media handling.** The system mutes other apps' audio during a call (see Q5).

**Cost the map should price in.** Android Auto's calling support requires the Telecom Jetpack
integration *plus* a `CarAppService` declaring `androidx.car.app.category.CALLING`, is **beta**, and
"apps can only publish to Internal Testing and Closed Testing tracks on Google Play". `documented`
<https://developer.android.com/training/cars/communication/calling>. LEGION is sideloaded, so the
Play track restriction may be moot, but whether a sideloaded, unapproved CALLING CarAppService
renders on a retail head unit is unknown and is exactly the "what if AA refuses to render the call"
fact the map says everything rests on. Ticket 07 territory; E4 below is the cheapest probe.

**Recommendation.** Re-argue settled decision 3. Either (a) drop the call and use
`MODE_IN_COMMUNICATION` + `setCommunicationDevice`, accepting that there is then no in-car UI at all
and the media surface is the whole product, or (b) keep the call for the UI and the Android 17 path,
knowing the mic was never the reason. Do not keep it on the mic argument as written.

---

## Q4. Echo

- **`VOICE_COMMUNICATION` is the source the platform is expected to echo-cancel.** CDD:
  implementations "should provide an acoustic echo canceler (AEC) on the capture path when capturing
  with `VOICE_COMMUNICATION`", and if provided it must be discoverable through
  `AcousticEchoCanceler`. `documented`
  <https://source.android.com/docs/core/audio/implement-pre-processing>,
  <https://source.android.com/docs/compatibility/cdd> (5.4 audio recording)
- Preprocessing is attached per source via `/vendor/etc/audio_effects.xml`; "the framework
  automatically requests the use of those effects from the HAL". `documented` (same AOSP page).
  So the effect LEGION attaches by hand in `attachVoiceEffects()` (`GeminiLiveSession.kt:1000`) is
  belt-and-braces on top of a source-driven default - and with `VOICE_RECOGNITION` as the source, the
  default set is the recognition set, not the communication set. `inferred`.
- **The HFP path has its own AEC in the car.** Hands-free calling over HFP is echo-cancelled by the
  head unit, which is why routing both directions through SCO is the clean configuration.
  `inferred` - no Android doc asserts this about arbitrary head units; it is the premise of HFP.
- **The dangerous configuration is a split path**: output on A2DP or the projection link, input on
  SCO. A phone-side `AcousticEchoCanceler` needs a valid downlink reference; if the far end of the
  output is a different transport, the reference is wrong and AEC does nothing useful. `inferred` -
  the strongest single reason to test E3 rather than reason about it.
- Half-duplex mitigates only the case where LEGION is not recording while speaking. LEGION's mic loop
  keeps the `AudioRecord` open across turns and gates forwarding (`GeminiLiveSession.kt:244`), so the
  hardware capture path is live during playback even when bytes are dropped. `traced`.

---

## Q5. Audio focus against Spotify

**Duck vs pause, documented:**

| Request | Documented meaning |
|---|---|
| `AUDIOFOCUS_GAIN_TRANSIENT` | "you expect to play audio for only a short time and you expect the previous holder to **pause** playing" |
| `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | "it's OK for the previous focus owner to keep playing if it 'ducks' (lowers) its audio output" |
| `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` | what the car-microphone doc tells you to hold while recording |

`documented` <https://developer.android.com/media/optimize/audio-focus>,
<https://developer.android.com/training/cars/apps/library/car-microphone>

**System ducking, and why LEGION's existing workaround is right.** "In Android 8.0 (API level 26),
when another app requests focus with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` the system can duck and
restore the volume without invoking the app's `onAudioFocusChange()` callback" - but only when the
playing app did not `setWillPauseWhenDucked(true)` **and is not playing speech content**. `documented`
(same page). Apps that opt out still pause and, as `GeminiLiveSession.kt:1258`'s comment records from
drive-notes ticket 01, frequently never resume. That comment is consistent with the docs; keep the
nudge-play-back-on-restore workaround.

**Android 12 fade-out** applies to a `USAGE_MEDIA`/`USAGE_GAME` holder when a second app requests
full `AUDIOFOCUS_GAIN` - not LEGION's case (it requests `TRANSIENT_MAY_DUCK`). `documented`.

**What a call does automatically.** "The system can mute audio from other apps while there is an
incoming call... If an app continues playing during the call, its playback is muted until the call
ends", for apps with `USAGE_MEDIA` or `USAGE_GAME`. `documented`
<https://developer.android.com/media/optimize/audio-focus>. So the call route pauses/mutes Spotify
whether or not LEGION asks - a real convenience, and also a real hazard: it is heavier than ducking
and there is no "duck only" option once you are a call. `inferred` for the hazard.

**Spotify App Remote vs generic MediaSession.** No behavioural difference at the audio layer.
App Remote is an app-to-app **control** binding into `com.spotify.music` (`SpotifyController.kt:57`
and its doc comment); the audio is produced by the Spotify process either way, and Spotify responds
to focus as an ordinary media app in both cases. `inferred` - Spotify's focus behaviour is not
documented by Google or by LEGION's SDK. The App Remote surface does give you an explicit
`resume()` that the generic path lacks, which matters only for the never-auto-resumes bug above.
`traced`.

**Trap for the HFP route.** Setting `MODE_IN_COMMUNICATION` is not focus-neutral: on many devices it
alone attenuates or re-routes media, and it can force output onto the narrowband SCO voice route
(8/16 kHz mono) so LEGION's 24 kHz `AudioTrack` sounds like a phone call and A2DP music is
interrupted. `inferred`, no doc found either way - this is the failure mode E3 exists to catch.

---

## Q6. Android 14/15/17 restrictions that bite

`targetSdk = 34`, `minSdk = 24`, `compileSdk = 36` (`app/build.gradle.kts:33-38`). `traced`.

- **Microphone FGS type.** Requires `FOREGROUND_SERVICE_MICROPHONE` + granted `RECORD_AUDIO`. Both
  present (`AndroidManifest.xml:14,18`, service type `connectedDevice|dataSync|microphone` at line
  114). `traced` + `documented`
  <https://developer.android.com/develop/background-work/services/fgs/service-types>
- **Android 14 while-in-use restriction, the sharp one.** "you cannot create a `microphone` foreground
  service while your app is in the background", `SecurityException` otherwise. The exemption list is
  verbatim: system component; app widgets; **interacting with a notification**; a `PendingIntent`
  sent from **a different, visible app**; device-owner DPC; an app providing `VoiceInteractionService`;
  `START_ACTIVITIES_FROM_BACKGROUND`. `documented`
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
  **An active telecom call is NOT an exemption.** So the call route does not solve background start
  either. Whether "tap play in Android Auto -> host binds LEGION's `MediaBrowserService` -> LEGION
  starts a mic FGS" counts as "a `PendingIntent` from a different, visible app" is **not documented**
  - a `MediaBrowserService` bind is not a `PendingIntent`. This is the single most likely place the
  whole design fails on a real device. `inferred`; test E5.
- **`phoneCall` FGS type**, if ticket 07 goes ahead: needs `FOREGROUND_SERVICE_PHONE_CALL` plus either
  `MANAGE_OWN_CALLS` in the manifest or `ROLE_DIALER`; documented use is "continue an ongoing call
  using the `ConnectionService` APIs". Self-managed qualifies via `MANAGE_OWN_CALLS`. `documented`
  (service-types page; `MANAGE_OWN_CALLS` also required by Core-Telecom). It is **additional to**
  `microphone`, not a substitute - the type list becomes
  `connectedDevice|dataSync|microphone|phoneCall`. `inferred` from the per-type prerequisite model.
- **Android 15.** `BOOT_COMPLETED` receivers cannot launch `dataSync`, `camera`, `mediaPlayback`,
  `phoneCall`, `mediaProjection` or `microphone` FGS. Separately, `dataSync` is capped at **6 hours
  per 24-hour window**, after which `Service.onTimeout()` fires and failing to `stopSelf()` throws
  `RemoteServiceException`. `documented`
  <https://developer.android.com/about/versions/15/behavior-changes-15>
  **LEGION declares `dataSync` on its always-on service.** On a phone running Android 15 with
  `targetSdk` bumped to 35, `AriaForegroundService` becomes a timed-out service on a long drive.
  Out of this ticket's scope but it belongs in someone's backlog. `traced` + `inferred`.
- **Android 17 background audio hardening.** Covered in Q3. Relevant now only as an argument, not a
  constraint: no Android 17 device is in the rig.

---

## Smallest on-device experiments

Rig: real head unit + OPPO A17k. Logcat is unusable for LEGION's own logs on this phone
(auto-memory), so every experiment needs its result rendered **in the app UI** - a debug screen with
a few text fields is the prerequisite for all five.

- **E1 - does the phone mic survive projection at all?** Plug in, project, start a normal LEGION
  turn from the phone screen. Show captured RMS on screen. Non-zero = capture works while AA runs.
  Kills or confirms the whole premise in ten minutes.
- **E2 - the Assistant collision, and whether the mode fixes it.** Same as E1, then say "Hey Google"
  at the head unit mid-capture. Display `AudioRecordingConfiguration.isClientSilenced()` and live RMS.
  Repeat three ways: (a) `VOICE_RECOGNITION` as today, (b) `VOICE_COMMUNICATION`,
  (c) `VOICE_COMMUNICATION` + `setMode(MODE_IN_COMMUNICATION)`. This one experiment answers Q1 and
  the arbitration half of Q3.
- **E3 - the HFP route end to end, and echo.** Add `MODIFY_AUDIO_SETTINGS`, then
  `setMode(MODE_IN_COMMUNICATION)` + `setCommunicationDevice(TYPE_BLUETOOTH_SCO)`. Display
  `getCommunicationDevice()`, the routed input device from `AudioRecordingConfiguration.getAudioDevice()`,
  and the `AudioTrack`'s routed device. Then: (a) speak from the passenger seat with the phone face
  down in a pocket - does the car mic pick it up; (b) have LEGION speak a long sentence while
  capture is live - is the reply echoed back into the next turn; (c) note what happened to Spotify
  and to the output quality. This is the decisive test for Q2, Q4 and the Q5 trap.
- **E4 - does AA render a sideloaded self-managed call?** Minimum: `MANAGE_OWN_CALLS`, a
  `ConnectionService`, place one self-managed outgoing call while projecting, look at the head unit.
  No LEGION integration needed. Cheapest possible probe of the fact the map says everything rests on;
  belongs to ticket 07 but this ticket's conclusion changes if it fails.
- **E5 - background start from the media surface.** Stub `MediaBrowserService`, tap play in Android
  Auto with LEGION's UI never opened, attempt `startForeground` with the `microphone` type, and
  render the outcome (started / `SecurityException`) on the debug screen next time the phone is
  picked up. Answers the Android 14 WIU question that no doc answers.

Run E1, E2, E3 in one sitting; they share the debug screen and take one drive.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| Concurrent-capture priority rules, Assistant privilege, silence-not-error, `setPrivacySensitive`, `isClientSilenced` | `documented` |
| `setCommunicationDevice` replaces `startBluetoothSco`/`setSpeakerphoneOn`; mandatory migration at API 33; HFP-compatible | `documented` |
| SCO link raised by active stream patched to SCO device + audio mode set | `documented` (AOSP) |
| `MODE_IN_COMMUNICATION` settable by ordinary apps; `MODE_IN_CALL` reserved to telephony | `documented`, but from a search-surfaced snippet of the AudioManager reference, not a direct page read - the reference page is JS-rendered and would not fetch |
| "Voice call active" defined by `getMode()`, hence a plain FGS gets call-grade capture priority | `documented` for the rule; `inferred` for the LEGION conclusion |
| Core-Telecom forbids `setCommunicationDevice` when using Telecom | `documented` |
| Telecom does not capture or play audio for the app | `documented` |
| AA calling needs a `CALLING` `CarAppService` + Telecom Jetpack, is beta, Play track restricted | `documented` |
| `CarAudioRecord` = car mic, Car App Library API level 5, AA 7.9+ | `documented` |
| Media surface receives a parsed query, never audio | `documented` |
| Focus duck/pause semantics, Android 8 auto-ducking conditions, Android 12 fade-out, call mutes media | `documented` |
| FGS type prerequisites; Android 14 WIU restriction and its 7 exemptions; Android 15 `BOOT_COMPLETED` + 6h `dataSync` cap; Android 17 hardening | `documented` |
| LEGION uses `VOICE_RECOGNITION`, requests `TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANT`, attaches AEC/NS by hand, keeps the record open across turns | `traced` (read `GeminiLiveSession.kt`) |
| `MODIFY_AUDIO_SETTINGS` absent from the manifest; service type is `connectedDevice\|dataSync\|microphone`; targetSdk 34 / minSdk 24 | `traced` |
| Spotify App Remote and generic MediaSession behave identically to audio focus | `inferred` |
| Head-unit HFP path carries its own AEC | `inferred` |
| Split output/input transports defeat phone-side AEC | `inferred` |
| `MODE_IN_COMMUNICATION` may narrowband the output and interrupt A2DP | `inferred` |
| A media-surface tap satisfies the Android 14 WIU background-start exemption | **unknown**, no doc either way - E5 |
| Any claim about what a real head unit does | **untested**; nothing in this document is `on-device` |
