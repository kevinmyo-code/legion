---
map: android-auto
ticket: 01
title: "Does Android Auto render a self-managed call at all?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Does Android Auto render a self-managed call at all?

## Question

The whole map rests on this. The plan is for LEGION to place a **self-managed telephony call to
itself** (`ConnectionService` with `PhoneAccount.CAPABILITY_SELF_MANAGED`, permission
`MANAGE_OWN_CALLS`), so the live Gemini session legitimately owns the microphone and is routed
through the car. Historically, an Android Auto head unit showed only the Bluetooth HFP call coming
from the phone, and third-party calling apps did not appear. Whether that is still true is the
single load-bearing unknown.

Establish, against primary sources (Android `android.telecom` reference and guides, the Android Auto
/ Android for Cars developer documentation, AOSP `Telecom` and `CarTelecom` source, release notes):

1. **Does Android Auto surface an ongoing self-managed call at all** while projecting? If it does,
   what does the driver actually see - the real in-call template with mute and end, a reduced card,
   or a notification only?
2. **Is a category or approval needed?** Android Auto restricts which app categories may render.
   Is a self-managed call exempt from that (because Telecom, not Android Auto, owns the surface), or
   does the app additionally need a declared, approved Android Auto category?
3. **Does the sideload / Play-install gate apply** to a self-managed call, or only to apps that
   declare an Android Auto category? (Ticket 02 owns the media-app half of this question; do not
   duplicate it, but note any interaction.)
4. **Audio routing.** When a self-managed call is active while projecting, does the mic come from the
   **car's** microphone over Bluetooth HFP/SCO, or from the phone's own mic? Is that automatic, or
   does the app have to request a route (`AudioManager`, `CallAudioState`, `setAudioRoute`)?
5. **`CAPABILITY_SUPPORTS_VIDEO_CALLING` / voip audio mode:** what `AudioAttributes` /
   `MODE_IN_COMMUNICATION` posture a self-managed call is expected to adopt, and whether that is
   compatible with a WebSocket-driven half-duplex session rather than a real RTP stream.
6. **What breaks it.** Known failure modes: does an active HFP call from the phone block a
   self-managed call, does the car's own phone app fight for the surface, does Assistant preempt?

The answer must state which claims are **documented** and which are **inferred**. If the honest
answer is "the documentation does not say and only a device test can settle it", say that plainly and
state the smallest on-unit experiment that would settle it - Kevin has a real head unit.

**If the answer is no**, the call route dies and ticket 07 rules on the fallback. Everything about
the microphone (settled decision 3) is downstream of this.

Findings go to `.scratch/android-auto/research/01-does-android-auto-render-a-self-managed-call.md`.

## Answer

**The framework path is open, and it was proved on Kevin's own phone rather than argued from docs.
What Android Auto chooses to DRAW is still unknown, and the risk turns out to be distribution, not
telephony.** Full findings and citations:
[research/01-does-android-auto-render-a-self-managed-call.md](../research/01-does-android-auto-render-a-self-managed-call.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged.
Method was read-only: the installed gearhead `base.apk` pulled over wireless ADB and its manifest
dumped with `aapt2`.

1. **The ticket's premise is out of date at the framework layer** (`on-device`). Android Auto
   17.2.662634 ships `CarProjectionInCallServiceImpl` declaring **both**
   `android.telecom.IN_CALL_SERVICE_CAR_MODE_UI=true` and
   `android.telecom.INCLUDE_SELF_MANAGED_CALLS=true` - exactly the two meta-data flags AOSP
   `InCallController` requires before it will hand a self-managed call to a non-dialer surface.
   `dumpsys telecom` also shows gearhead really calling `setAutomotiveProjection` on this phone.
2. **The catch, and it is a real one.** What gearhead *draws* is closed-source and undocumented.
   Every Google page describing the Android Auto in-call view attaches it to an app declaring
   **`androidx.car.app.category.CALLING`** on a `CarAppService`. That programme is beta and Internal
   or Closed Play tracks only - and Android Auto's unknown-sources developer option, verbatim,
   "doesn't apply to apps built using the Android for Cars App Library". **So the category is
   unreachable on a sideloaded build.** If gearhead gates its in-call surface on that category, this
   route dies on **distribution**, not on telephony. Ticket 12 now has a second, sharper edge.
3. **The part that survives regardless, and it matters most.**
   `PhoneAccount.CAPABILITY_SELF_MANAGED` documents that self-managed connections "will, however, be
   displayed on connected Bluetooth devices", and `BluetoothInCallService` was confirmed on-device to
   declare `INCLUDE_SELF_MANAGED_CALLS=true` too. **The car therefore sees the call over HFP and the
   uplink is the car's own microphone via SCO** - settled decision 3's actual goal is delivered by
   **Bluetooth**, not by projection. Read alongside ticket 04, which found a plain foreground service
   can reach the same microphone with `MODE_IN_COMMUNICATION` + `setCommunicationDevice`: there are
   now **two** routes to the car mic and neither depends on Android Auto rendering anything.
4. **Ticket 07 must be reframed.** It was written as "does the mic work". It is now
   **"what does the driver see and touch"**.
5. **No RTP stream is required** (`documented`): `setAudioModeIsVoip(true)`, **never** touch
   `setCommunicationDevice` or `startBluetoothSco` when using Telecom, media on `STREAM_VOICE_CALL`,
   skip `CAPABILITY_SUPPORTS_VIDEO_CALLING`. Note the direct contradiction with ticket 04's route -
   they are alternatives, not a stack. **Two hard 5-second budgets bite `AriaForegroundService`**:
   the notification after `addCall`, and remote-surface callbacks - so answer, hold and disconnect
   **must not block on a Gemini round trip**.
6. **Documented blockers**: Telecom refuses a self-managed call while another `ConnectionService` has
   an ongoing call, and during any emergency call. Assistant preemption is undocumented. One ledger
   row (the `isOutgoingCallPermitted` wording) came from a javadoc mirror rather than platform source
   and is flagged for re-verification.

**The experiment.** E1 rendering, E2 audio route and E3 Assistant preemption collapse into **one
30-minute head-unit session** off a throwaway branch: a bare `ConnectionService`, one button, no Car
App Library. `dumpsys telecom`'s bound-services list during the call distinguishes "gearhead never
got it" from "gearhead got it and declined to draw" - **different rulings for ticket 07**. Four
ledger rows are flagged UNKNOWN, each with the experiment that settles it.
