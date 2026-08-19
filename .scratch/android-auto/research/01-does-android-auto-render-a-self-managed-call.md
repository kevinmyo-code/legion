# Research: Does Android Auto render a self-managed call at all?

Ticket: `.scratch/android-auto/issues/01-does-android-auto-render-a-self-managed-call.md`
Map: `.scratch/android-auto/map.md`
Researched: 2026-08-13

Every claim below is tagged `documented` (with the URL that owns it), `on-device` (verified against
Kevin's OPPO A17k over wireless ADB this session), `inferred`, or `field-report`. Nothing is asserted
as verified that was only reasoned.

---

## Short answer

**The plumbing exists and it is live on Kevin's phone.** Android Auto 17.2 ships an `InCallService`
that declares BOTH `android.telecom.IN_CALL_SERVICE_CAR_MODE_UI=true` AND
`android.telecom.INCLUDE_SELF_MANAGED_CALLS=true` (`on-device`). Those two meta-data flags are
exactly and only what the Telecom framework requires before it will hand a self-managed call to a
non-default-dialer surface (`documented`). So at the framework layer, Telecom **will** deliver a
LEGION self-managed call to Android Auto while projecting.

**What Android Auto then chooses to draw is not settled by any primary source.** Google's own
calling documentation attaches the in-call view to an app that declares the
`androidx.car.app.category.CALLING` category on a `CarAppService`, and that programme is beta and
Play-track-gated. Whether gearhead renders for an app with no car app category at all is a decision
inside closed-source gearhead code and **the documentation does not say**. That is one device test
(E1 below), and Kevin can run it.

**Independently of Android Auto, the car will see the call over Bluetooth HFP.** That is documented
in `PhoneAccount` and confirmed on-device: the phone's `BluetoothInCallService` also declares
`INCLUDE_SELF_MANAGED_CALLS=true`. This is the microphone answer, and it is the part settled
decision 3 actually depends on.

---

## Q1. Does Android Auto surface an ongoing self-managed call, and what does the driver see?

### The framework rule

`documented` - `PhoneAccount.CAPABILITY_SELF_MANAGED`, verbatim:

> "When set, `Connection`s created by the self-managed `ConnectionService` will not be surfaced to
> implementations of the `InCallService` API. Thus it is the responsibility of a self-managed
> `ConnectionService` to provide a user interface for its `Connection`s. Self-managed `Connection`s
> will, however, be displayed on connected Bluetooth devices."

Source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telecomm/java/android/telecom/PhoneAccount.java

`documented` - the exception to "will not be surfaced" is a single meta-data flag.
`TelecomManager.METADATA_INCLUDE_SELF_MANAGED_CALLS` (`"android.telecom.INCLUDE_SELF_MANAGED_CALLS"`),
verbatim:

> "A boolean meta-data value indicating whether an `InCallService` wants to be informed of calls
> which have the `Call.Details#PROPERTY_SELF_MANAGED` property. ... By default, the `InCallService`
> will NOT be informed about self-managed calls.
> An `InCallService` which receives self-managed calls is free to view and control the state of calls
> in the self-managed `ConnectionService`. **An example use-case is exposing these calls to an
> automotive device via its companion app.**"

Source: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/telecomm/java/android/telecom/TelecomManager.java
(AOSP mirror of the same file; identical text also at
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telecomm/java/android/telecom/TelecomManager.java)

`documented` - `METADATA_IN_CALL_SERVICE_CAR_MODE_UI` (`"android.telecom.IN_CALL_SERVICE_CAR_MODE_UI"`):

> "A boolean meta-data value indicating whether an `InCallService` implements an in-call user
> interface to be used while the device is in car-mode."

`documented` - Telecom's own filter, AOSP `InCallController.java`:

> `if (call.isSelfManaged() && (!info.isSelfManagedCallsSupported() || !call.visibleToInCallService()))`

and the car-mode swap machinery: `IN_CALL_SERVICE_TYPE_CAR_MODE_UI = 3`,
`CarSwappingInCallServiceConnection.chooseInitialInCallService(boolean isCarMode)`, car-mode package
tracked via `mCarModeTracker.getCurrentCarModePackage()`, entered through
`onAutomotiveProjectionStateSet(String automotiveProjectionPackage)`.
Source: https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/InCallController.java

### What is actually on the phone

`on-device` - `adb shell cmd package query-services -a android.telecom.InCallService` on the A17k
(CPH2471) returns four, including:

```
com.google.android.projection.gearhead/com.google.android.apps.auto.components.telecom.service.CarProjectionInCallServiceImpl
```

`on-device` - Android Auto version on the phone: `versionName=17.2.662634-release`,
`versionCode=172662634`, `targetSdk=37`.

`on-device` - decompiled manifest of the installed gearhead `base.apk` (`aapt2 dump xmltree`), two
`InCallService` declarations, both `android:enabled=false` in the manifest (gearhead flips them at
runtime, `inferred`):

| Service | `INCLUDE_SELF_MANAGED_CALLS` | `IN_CALL_SERVICE_CAR_MODE_UI` |
|---|---|---|
| `...telecom.service.NonCarInCallServiceImpl` | `true` | absent |
| `...telecom.service.CarProjectionInCallServiceImpl` | `true` | `true` |

`on-device` - `adb shell dumpsys telecom` shows the car-mode path really fires on this phone:

```
CarModeTracker:
  Car mode history:
    2026-08-12T14:21:01 - setAutomotiveProjection: packageName=com.google.android.projection.gearhead
    2026-08-12T14:25:47 - releaseAutomotiveProjection: packageName=com.google.android.projection.gearhead
```

`inferred` (mechanism only, high confidence): while projecting, Telecom binds
`CarProjectionInCallServiceImpl` as the car-mode UI, and because it declares
`INCLUDE_SELF_MANAGED_CALLS=true` a LEGION self-managed `Connection` **is passed to it**. The
historical "Android Auto only shows the HFP call" answer the ticket describes is **out of date at the
framework layer**.

### What the driver sees - not settled

`documented` - Android Auto renders the in-call view itself, apps do not:

> "Android Auto automatically provides and renders an in-call view during active calls, powered by
> your app's telecom integration. This in-call view replaces your app's templated screens while a
> call is ongoing."

Source: https://developer.android.com/training/cars/communication/calling

> "The in-call screen is provided by Android Auto with limited customization options."

Source: https://developer.android.com/design/ui/cars/guides/app-types/communications

`documented` - the quality bar tells you what that view contains, from the calling app's obligations
(CF-2): a call name and icon; for meetings, participant count and active speaker; **and the ability
for the user to mute themselves**. CF-1 covers initiate / accept / decline / leave. CF-3 requires a
call started before connecting to keep running and be displayed once Android Auto connects.
Source: https://developer.android.com/docs/quality-guidelines/car-app-quality

`inferred` - so if it renders at all, it is the real in-call template with mute and end, not a card
and not a notification. Mute and hang-up are exactly the two controls LEGION needs.

**The documentation does not say** whether gearhead renders that view for an app that supplies a
self-managed call but declares no `androidx.car.app.category.CALLING` `CarAppService`. Every Google
page describing the in-call view describes it inside the calling-category programme. gearhead may
gate on a package allowlist, on the declared category, or on nothing at all; that logic is closed
source and cannot be read.

**Smallest experiment (E1).** Throwaway debug build, no Car App Library, no media service: a
`ConnectionService` with `CAPABILITY_SELF_MANAGED`, `MANAGE_OWN_CALLS`, one button that calls
`TelecomManager.placeCall` to a `tel:`/`sip:` self URI, and a foreground notification within 5s.
Plug the A17k into the head unit, press the button, and look at the head unit screen. Then, still
connected, run `adb shell dumpsys telecom` and read `mInCallServices (InCalls bound)` plus the call
list. Three distinguishable outcomes:
1. Full in-call template on the head unit -> route is alive with no category and no Play track.
2. `CarProjectionInCallServiceImpl` bound and the call visible in `dumpsys` but nothing drawn ->
   gearhead is gating on the category or an allowlist; ticket 07 rules.
3. Call not delivered to gearhead at all -> the framework read above is wrong; ticket 07 rules.

Cost is one afternoon and it collapses the whole map's load-bearing unknown. Do this before anything
else on this map is built.

---

## Q2. Is a category or approval needed?

`documented` - for the supported path, yes, both:

> "To support answering and controlling calls on Android Auto, your app **must** integrate with the
> Telecom Jetpack library ... Your app must use its telecom integration at all times, not just when
> running Android Auto."

and

> "Declare the `androidx.car.app.category.CALLING` car app category in the intent filter of your
> `CarAppService`."

Source: https://developer.android.com/training/cars/communication/calling

`documented` - and the programme is gated:

> "Calling experiences are currently in beta. Apps can only be published to Internal Testing and
> Closed Testing tracks on Google Play at this time." ... "Do not promote builds with calling
> support to Open Testing or Production tracks. Submissions containing builds on those tracks will
> be rejected." Early access is by form nomination.

Same source, and repeated at
https://developer.android.com/design/ui/cars/guides/app-types/communications

`documented` - the calling app must also implement Core-Telecom's **remote surface support**
callbacks (`onAnswerCall`, `onSetCallDisconnected`, `onSetCallActive`, `onSetCallInactive` passed to
`CallsManager.addCall`), because the remote surface, explicitly including Android Auto, drives the
call. **Each callback must complete within 5 seconds or Telecom may tear the call session down.**
Source: https://developer.android.com/develop/connectivity/telecom/voip-app/telecom

`documented` - Core-Telecom also exposes call extensions aimed at remote surfaces, including a
**local call silence** extension described as being for automotive. That is the framework-level mute
LEGION would wire to muting the Gemini uplink. Same source.

`inferred` - the tension the ticket asks about is real and unresolved by docs: the framework flag
(`INCLUDE_SELF_MANAGED_CALLS`) is category-agnostic, but Google's product documentation only ever
describes rendering inside the category programme. Telecom owning the delivery does not prove
gearhead owns nothing on top of it. E1 is the only thing that separates these.

`documented` - note that LEGION does not need the Core-Telecom Jetpack wrapper specifically. The
platform `ConnectionService` + `CAPABILITY_SELF_MANAGED` route is still first-class API and is what
produces `PROPERTY_SELF_MANAGED` calls. Core-Telecom is Google's recommended wrapper over it and is
what the Android Auto page points at.

---

## Q3. Does the sideload / Play-install gate apply?

`documented` - Android Auto's unknown-sources developer option, verbatim from
https://developer.android.com/training/cars/testing :

> "Android Auto has a developer option that lets you run apps that aren't installed from a trusted
> source. This setting applies to media, messaging notifications, and parked apps but **doesn't apply
> to apps built using the Android for Cars App Library**."

and

> "To test your app in real vehicles, you must install it from a trusted source such as Google Play,
> with one exception detailed in Allow unknown sources."

`documented` - developer mode is unlocked by tapping **Version and permission info** ten times in
Android Auto's settings and accepting **Allow development settings?**.

Consequences, split by surface:

| Surface | Sideloadable? | Basis |
|---|---|---|
| Media browse service (ticket 02's half) | Yes, with unknown sources on | `documented`, "applies to media" |
| `CarAppService` with `category.CALLING` templated screens | **No** | `documented`, Car App Library apps are excluded from unknown sources |
| The self-managed `ConnectionService` itself | Not covered by the gate | `inferred` |

`inferred` - the last row is the important one. A `ConnectionService` is not an Android-for-Cars app
at all; it is registered with Telecom on the phone, and Telecom's binding decision reads meta-data,
not install provenance. So the unknown-sources gate should not touch it. **But if gearhead gates its
in-call rendering on the CALLING category, LEGION can never satisfy that gate on a sideloaded build**,
because the category lives on a `CarAppService` and Car App Library apps cannot be sideloaded, and
the map has ruled Play distribution out of scope. That is the single most likely way this route dies:
not the framework refusing, but the category being unreachable.

`inferred` - interaction with ticket 02: the media door is sideload-legal, the calling category is
not. If E1 returns outcome 2 or 3, the map's "media is the door, the call is the room" shape loses
the room, and ticket 07 inherits a question that is about distribution, not about telephony.

---

## Q4. Audio routing - car mic or phone mic?

This is the question settled decision 3 actually rides on, and it has the best-evidenced answer.

`documented` - the self-managed contract explicitly promises the Bluetooth surface even when
`InCallService` is denied: "Self-managed `Connection`s will, however, be displayed on connected
Bluetooth devices." (`PhoneAccount.CAPABILITY_SELF_MANAGED`, quoted in full above).

`on-device` - the phone's own HFP bridge honours that. `com.android.bluetooth.telephony.BluetoothInCallService`
in the installed `MtkBluetooth.apk` declares:

```
meta-data android:name="android.telecom.INCLUDE_SELF_MANAGED_CALLS" android:value=true
```

`inferred` (high confidence, mechanism traced end to end): a LEGION self-managed call is reported to
the paired car as a real HFP call. When call audio is routed to Bluetooth, the SCO link carries both
directions, so **the uplink is the car's own echo-cancelled microphone, not the phone's mic in a
pocket**. This is the behaviour decision 3 wants, and it is delivered by Bluetooth HFP, **not** by
Android Auto projection. It should therefore survive even outcome 3 of E1.

`documented` - routing is the app's to request and Telecom's to execute:
- `Connection.getCallAudioState()` reports Bluetooth / Earpiece / Wired / Speaker.
- Override `Connection.onCallAudioStateChanged(CallAudioState)` to track changes.
- `CallAudioState.getActiveBluetoothDevice()` / `getSupportedBluetoothDevices()` (API 28+).
- Request the route with `Connection.requestBluetoothAudio(device)` (API 28+) or
  `setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)` (API 23+).
- "The Telecom framework manages SCO connections for Bluetooth audio automatically when you request
  Bluetooth audio routing."

Source: https://developer.android.com/develop/connectivity/bluetooth/ble-audio/telecom-api-managed-calls

`documented` - and the app must NOT route audio itself:

> "Don't use the `AudioManager#setCommunicationDevice` or `AudioManager#startBluetoothSco` APIs to
> manage audio routes when using Telecom; doing so will cause audio issues in your call."

Also: "Ensure media uses `AudioManager.STREAM_VOICE_CALL`."
Source: https://developer.android.com/develop/connectivity/telecom/selfManaged

`inferred` - automatic vs requested: Telecom picks a default route and will normally prefer a
connected Bluetooth HFP device, but the documentation stops short of guaranteeing it for a
self-managed call in a car. Treat it as **request it explicitly** (`requestBluetoothAudio` with the
car device from `getSupportedBluetoothDevices()`), and observe `onCallAudioStateChanged` rather than
assuming. **The documentation does not say** what the default route is with a projecting head unit
attached.

**Smallest experiment (E2).** During the E1 call, log `getCallAudioState()` on every
`onCallAudioStateChanged` into the app's own UI (logcat is useless on this phone, per project memory),
speak with the phone face-down in the console bin, and hear whether Gemini transcribes cleanly. Route
value `ROUTE_BLUETOOTH` plus a clean transcript from across the cabin settles it. If it reports
`ROUTE_EARPIECE`/`ROUTE_SPEAKER`, call `requestBluetoothAudio` and re-check. E2 rides along on E1 at
almost no extra cost, and it is worth running **even if E1's rendering answer is no**, because HFP is
a separate surface from projection.

---

## Q5. VoIP audio posture, and does a WebSocket half-duplex session fit?

`documented` - the expected posture is one call, not a manual `AudioManager` dance:

```kotlin
class VoIPConnection : Connection() {
  init {
    setConnectionProperties(PROPERTY_SELF_MANAGED)
    setAudioModeIsVoip(true)
  }
}
```

Source: https://developer.android.com/develop/connectivity/bluetooth/ble-audio/telecom-api-managed-calls

`inferred` - `setAudioModeIsVoip(true)` is what puts the device into the communication audio mode;
LEGION should not set `AudioManager.mode = MODE_IN_COMMUNICATION` by hand, for the same reason it
must not call `startBluetoothSco`. The primary docs assert the "don't touch AudioManager routing"
half explicitly; the "Telecom sets the mode for you" half is inferred from `setAudioModeIsVoip`
existing and from the routing prohibition, **not** verified against a quoted line.

`documented` - `CAPABILITY_SUPPORTS_VIDEO_CALLING` is irrelevant to LEGION and should be omitted:

> "This is not an indication that the `PhoneAccount` is currently able to make a video call, but
> rather that it has the ability to make video calls."

Source: `PhoneAccount.java` (URL above). Core-Telecom's equivalent is
`CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING`; LEGION wants `CAPABILITY_BASELINE` only.

`inferred` - **nothing in the Telecom contract requires RTP, SIP, or any real media stream.** Telecom
manages call *state* and *audio routing*; it never inspects a media path. A self-managed call whose
"media" is `AudioRecord` from `STREAM_VOICE_CALL`-mode capture pushed over a Gemini Live WebSocket,
with playback via `AudioTrack` on `USAGE_VOICE_COMMUNICATION`, satisfies everything the framework
asks for. Half-duplex is a property of LEGION's session, not of the call, and server VAD does not
change what Telecom sees.

`documented` - three real obligations that DO bite LEGION's existing `AriaForegroundService`:
1. **Post a notification within 5 seconds of adding the call**, or foreground priority is lost.
2. Under API 34+ this uses foreground service types, not `ConnectionService`, for the FGS grant.
3. Remote-surface callbacks must return within 5 seconds.
Source: https://developer.android.com/develop/connectivity/telecom/voip-app/telecom

`inferred` - that lands squarely on the map's open "one service or two" question: the notification and
FGS lifetime are already `AriaForegroundService`'s job, and the 5-second callback budget means
answer/hold/disconnect must not be blocked behind a Gemini round trip.

---

## Q6. What breaks it

**An ongoing cellular call blocks the self-managed call outright.**
`documented` - `TelecomManager.isOutgoingCallPermitted` states Telecom will not permit the call when
"PhoneAccount has the `CAPABILITY_SELF_MANAGED` property (self-managed ConnectionService) and there
is an ongoing call in another ConnectionService", and separately when "there is an ongoing emergency
call". Self-managed accounts are also filtered out of emergency call handling entirely.
Source (javadoc text, mirrored): https://learn.microsoft.com/en-us/dotnet/api/android.telecom.telecommanager.isoutgoingcallpermitted
`field-report` label on the mirror itself; the method and its semantics are AOSP API surface in
`TelecomManager.java`, but the full javadoc block did not render in the primary fetches this session.
**Verify by reading the method's javadoc on the platform source before relying on the exact wording.**

`inferred` - practical consequence: LEGION must call `isOutgoingCallPermitted(handle)` before
`placeCall` and degrade gracefully, and a real phone call arriving mid-session will contend. Which one
wins, and whether Telecom holds or disconnects the LEGION call, **the documentation does not say**.

**The car's own phone app fighting for the surface.** `inferred` - not a fight, structurally: the car
head unit's UI is drawn by gearhead while projecting, and Telecom binds exactly one car-mode
`InCallService` at a time via `CarSwappingInCallServiceConnection`. The realistic failure is not
contention but gearhead simply declining to draw (Q1, outcome 2).

**Assistant preemption.** **The documentation does not say.** `on-device`, gearhead ships
`GhMicrophoneContentProvider` under `components.demand.audio`, which shows Android Auto mediates
microphone access for its own Assistant flow; what it does to an active self-managed call's SCO
uplink is not documented anywhere primary. **Smallest experiment (E3):** during the E1 call, say
"Hey Google" at the head unit and observe whether LEGION's audio state changes, whether the call is
held, and whether it recovers when Assistant finishes.

**Bluetooth not paired.** `inferred` - Android Auto over USB does not by itself carry call audio;
HFP does. If the phone is projecting but not HFP-paired, the car mic is not in the picture at all and
decision 3's whole premise fails. Confirm the pairing state is HFP-connected, not just AA-connected,
before drawing any conclusion from E2.

**`enabled=false` on gearhead's InCallServices.** `on-device` + `inferred` - both gearhead
`InCallService` components ship disabled and are presumably enabled at projection time. If they are
enabled lazily by a code path that only runs for a recognised calling app, that alone would produce
outcome 3. `dumpsys telecom`'s bound list during E1 distinguishes this.

---

## Recommendation

1. Run **E1 + E2 + E3 as one 30-minute head-unit session** off a throwaway debug branch. They share a
   single build and a single drive.
2. Treat **Q4 (HFP car mic) as very likely to hold regardless of E1's rendering answer**. That means
   even the worst rendering outcome leaves settled decision 3 partially intact: the mic comes from
   the car, the driver just may not see a LEGION screen. Ticket 07 should be framed as "what does the
   driver see and touch", not "does the mic work".
3. Do **not** build against `androidx.car.app` for calling. The category cannot be sideloaded, and
   the map has ruled Play distribution out of scope.

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | `CAPABILITY_SELF_MANAGED` hides connections from `InCallService` but shows them on connected Bluetooth devices | traced (AOSP `PhoneAccount.java`, quoted verbatim) |
| 2 | `INCLUDE_SELF_MANAGED_CALLS` meta-data is the only opt-in for a non-dialer `InCallService` to see self-managed calls | traced (AOSP `TelecomManager.java` + `InCallController.java` filter) |
| 3 | Android Auto 17.2.662634 on the A17k declares `CarProjectionInCallServiceImpl` with both `IN_CALL_SERVICE_CAR_MODE_UI=true` and `INCLUDE_SELF_MANAGED_CALLS=true` | on-device (`aapt2 dump xmltree` of the pulled `base.apk`) |
| 4 | The phone's `BluetoothInCallService` also declares `INCLUDE_SELF_MANAGED_CALLS=true` | on-device (`aapt2 dump xmltree` of `/system/app/MtkBluetooth/MtkBluetooth.apk`) |
| 5 | gearhead really enters the car-mode path via `setAutomotiveProjection` on this phone | on-device (`dumpsys telecom` CarModeTracker history, 2026-08-12) |
| 6 | Telecom will therefore deliver a LEGION self-managed call to gearhead's InCallService while projecting | reasoned (mechanism traced, never executed) |
| 7 | Whether gearhead draws an in-call view for an app with no `category.CALLING` | UNKNOWN, documentation silent, needs E1 |
| 8 | Android Auto renders the in-call view itself; apps cannot customise it | traced (developer.android.com calling + communications design pages) |
| 9 | The in-call view carries name, icon, mute, and leave | traced (car-app-quality CF-1/CF-2/CF-3) |
| 10 | Calling category is beta, Internal/Closed Play tracks only | traced (developer.android.com/training/cars/communication/calling) |
| 11 | Unknown-sources dev option covers media but NOT Car App Library apps | traced (developer.android.com/training/cars/testing, quoted) |
| 12 | A bare `ConnectionService` is outside the unknown-sources gate | reasoned |
| 13 | Car mic reaches the app via HFP SCO once call audio routes to Bluetooth | reasoned (from claims 1 and 4; not measured) |
| 14 | App must not use `setCommunicationDevice` / `startBluetoothSco` with Telecom | traced (Core-Telecom guide, quoted) |
| 15 | Default audio route with a projecting head unit attached | UNKNOWN, documentation silent, needs E2 |
| 16 | `setAudioModeIsVoip(true)` is the intended audio-mode posture; no manual `MODE_IN_COMMUNICATION` | traced for the API call, reasoned for "don't set the mode yourself" |
| 17 | Telecom requires no RTP/SIP; a WebSocket half-duplex session satisfies the contract | reasoned |
| 18 | Notification within 5s of `addCall`; remote-surface callbacks within 5s | traced (Core-Telecom guide) |
| 19 | A self-managed call is not permitted while another ConnectionService has an ongoing call, or during an emergency call | traced via javadoc mirror, NOT confirmed against platform source this session, re-verify wording |
| 20 | Assistant preemption behaviour during an active self-managed call | UNKNOWN, documentation silent, needs E3 |
| 21 | gearhead's InCallServices ship `enabled=false` and are enabled at runtime | on-device for the flag, reasoned for the runtime enable |
