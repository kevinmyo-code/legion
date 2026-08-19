---
map: proactive-mode
title: "Map: Proactive mode"
charted: 2026-08-16
charted-by: ""
effort: "`.scratch/proactive-mode/`"
tickets: 9
open: 7
status: open
tags: [map]
---
# Map: Proactive mode

## Destination

**Alfred can speak first, and Kevin trusts the switch that stops him.** Decisions locked for: the
trigger engine, the compulsion line written as a checkable test, how the five settled categories
become storage and a surface, quiet hours and a nudge budget, delivery, Android scheduling, and the
register.

Destination is DECISIONS, same shape as its parent map. **One exception: [the choke
point](issues/01-one-gate-not-three.md) carries its own build spec** - it is a prerequisite rather
than a feature, and nothing else on this map is honest until it lands.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v24), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, 2026-08-16: *"I want a toggle for the AI to be proactive. I have
goals, alerts, and location-aware stuff. Flip a switch and it reminds me. Much like Alfred does to
Batman - it's past 10pm, perhaps rest is in order."* Charted as its own map because the parent
ticket carried eight decision clusters plus a build.

### What exists today - verified 2026-08-16, not remembered

| Claim | Reality |
|---|---|
| A proactive layer ships | **Yes, and it is the wrong shape.** `ProactivePreferences` is ONE global boolean, `muted`, default `false` - so **proactive is already ON**, and the control is an inverted mute rather than an opt-in. |
| Kevin can turn it off | **NO.** `setMuted`/`toggle` have **zero callers anywhere.** `ProactivePreferences` is not referenced in `ui/` at all. The switch exists and nothing is wired to flip it. |
| There is one gate | **There are three.** `ProactiveGate.speakIfIdle` (onboarding / busy / in-call / muted) has 11 raise sites, but `AmbientListener.kt:245` and `TelephonyController.kt:82` call `ProactiveBus.requestSpeak` **directly**, hand-rolling their own checks. `ProactiveBus` is deliberately NOT the choke point. |
| The five categories exist | **No.** Zero hits for `ProactiveCategory`, `Wellbeing`, or any proactive enum. They are a decision on the parent map and nothing else. |
| Anything wellbeing-shaped ships | **No.** Zero hits for bedtime, wind-down, rest. The "past 10pm" line has never existed. |
| Nineteen things can speak unprompted | **Yes** - first-meeting greeting, ignition opener, NHTSA recalls, new trouble code, coolant overheat, place arrival, two-hour break nudge, rough weather, odometer milestone, idle chatter (x2), fired reminders, incoming calls, and the ambient listener. **All car-shaped ambient chatter. None goal-aware, none time-aware.** |

**Standing preferences for this effort (Kevin, 2026-08-16):**
- Kevin is at the abstraction layer. Bring him forks with real cost or taste; decide implementation
  without asking.
- Pull-based tools remain the default everywhere else; this map is the ONE place LEGION initiates.
- Nothing that requires a Kevin-hosted backend.
- **Install and look.** Every UI finding of 2026-08-16 came from a screenshot, not from the suite.

### Settled, carried in - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **Master switch plus FIVE categories - Safety, Timing, Wellbeing, Fleet, Digest.** Two states each, no tri-state. (Kevin, 2026-08-16.) | Every raising ticket across LEGION maps its lines onto one of the five. Nobody invents a sixth switch or their own opt-in. |
| 2 | **The master is a TRUE kill switch. Nothing is exempt, safety included.** | Off means silent. A switch that does not fully silence is a switch nobody believes, and belief in it is what makes proactivity acceptable at all. |
| 3 | **`CrisisDetector` is untouched by this map.** It responds to something Kevin said rather than initiating, so it is not proactive speech. | It must stay outside the master switch. Do not "unify" it. |
| 4 | **The notification listener is NOT a category.** | It stays pull-only. If it ever raises, it joins Timing rather than earning a sixth switch. |
| 5 | **The kill switch cannot be honoured until the three gates become one** (verified 2026-08-16). | [The choke point](issues/01-one-gate-not-three.md) blocks the categories ticket, and every other ticket assumes it landed. |
| 6 | **`mission-control` owns screen aesthetics.** | Any surface coordinates with that map rather than building something it will re-skin. |

## Decisions so far

<!-- one line per closed ticket -->

- [What may a background process actually do on Android in 2026?](issues/07-scheduling-research.md)
  — **The threat is Samsung's sleeping-apps layer, not the six-hour cap.** Full findings with
  per-claim source labels in [research/07-scheduling.md](research/07-scheduling.md).
  **Two premise corrections, both errors in the charting brief:** the app targets **SDK 34**, not 36
  (`app/build.gradle.kts:38`) - the A25 *runs* 16, which is a different thing - so the six-hour
  `dataSync` cap, the `BOOT_COMPLETED` FGS block and the global-DND lockout are **all dormant today
  and armed by a `targetSdk` bump**, which sideloading means nothing forces. And the Samsung evidence
  cited in the brief was wrong: `memory/wireless-adb-available.md:29-31` attributes the
  `/data/local/tmp` failure to **Git Bash path mangling**, not an OEM block, and no `pm clear` entry
  exists.
  **The finding that matters most:** Samsung's own documented sleeping-apps layer puts an app unused
  for ~3 days into a **restricted bucket - one alarm per day, no network - while the foreground
  service keeps running and everything looks fine.** A voice assistant used daily **without its UI
  ever being opened** is exactly that profile. [VENDOR, Samsung's own docs.]
  **Also:** on Android 16 and applying at target 34, jobs running concurrently with a foreground
  service now obey the runtime quota - **the FGS no longer buys unlimited WorkManager runtime.**
  `AriaForegroundService` uses `dataSync` as its unconditional base type **because it is the only
  type with no runtime prerequisite**, and it is the only one with a kill timer; neither it nor
  `LedgerIngestService` implements `onTimeout`, so the future failure is a **fatal
  `RemoteServiceException`, not degradation**. AOSP source settles that the FGS keeps network
  through Doze. **No app-only DND bypass exists at any importance or category** - two independent
  user gates, which bounds "what may always speak". `notes/AlarmScheduler`'s degrade-in-words posture
  is confirmed still correct, with two gaps filed.
  **Recommended, not yet decided:** drop `dataSync` from `AriaForegroundService` (declaring
  `CHANGE_NETWORK_STATE` so `connectedDevice` needs no runtime grant); request the
  battery-optimisation allowlist at onboarding, which buys exact alarms, FGS background-start and
  partial Doze exemption in one prompt; and tell Kevin to mark LEGION "never sleeping".
  **Ten items need the phone. The gate on all of them: does `AriaForegroundService` survive a
  12-hour screen-off unplugged run at all? Run that first.**

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Which nudges actually ship first**, beyond the "past 10pm" rest line that started this. The
  categories are settled; their contents are owned by other maps (location intelligence, health,
  fleet, the brief) and cannot be listed until those resolve.
- **Whether a declined nudge is remembered**, and for how long. "Never nag twice" is easy to say and
  needs somewhere to store the refusal - which touches `.scratch/hands-and-senses/issues/20-memory-decay.md`
  and its own unstarted map.
- **Per-category quiet hours** versus one global window. Cannot be specified until
  [quiet hours](issues/05-quiet-hours-and-budget.md) settles the simple case.
- **Whether the Digest category subsumes the morning brief** or merely delivers it. Waits on
  `.scratch/hands-and-senses/issues/08-morning-brief.md`.
- **A "why did you say that?" affordance.** If Alfred speaks unprompted, being able to ask what
  triggered it is the cheapest trust mechanism available - but it depends on the trigger engine
  having an inspectable reason, which is [ticket 02](issues/02-trigger-engine.md)'s outcome.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **`CrisisDetector`.** Settled decision 3. It is not proactive speech and must not be folded in.
- **Making the notification listener a raise source.** Settled decision 4.
- **Any compulsion mechanic** - streaks, re-engagement pings, manufactured return, guilt for being
  away. CLAUDE.md §7, permanent, not this map's to reopen.
- **A Kevin-hosted backend, or push from anywhere but the device itself.**
- **Rebuilding the assistant's core register.** This map hands proactive-specific rules to whatever
  owns the voice; it does not redefine it.
