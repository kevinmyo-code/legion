---
map: proactive-mode
title: "Map: Proactive mode"
charted: 2026-08-16
charted-by: ""
effort: "`.scratch/proactive-mode/`"
tickets: 12
open: 2
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

**REACHED, 2026-08-21.** All eight decision tickets are resolved and the two open tickets are both
`built`, owing only a run on the phone. Twenty-one settled decisions. **Nothing on this map is built
except the choke point, the ambient retirement, and the concierge rename** - the trigger engine, the
five switches, the cap, the quiet hours, the typed raise object and the notification fallback are all
decided and unwritten. The build wants its own map; the shopping list is at the foot of
[ticket 02](issues/02-trigger-engine.md).

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
| Kevin can turn it off | ~~**NO.**~~ **STALE - wired 2026-08-18 with ticket 01.** `ui/SettingsScreen.kt:343` calls `setMuted`, and `ui/SettingsRows.kt`'s `ProactiveSpeechRow` states the kill-switch semantics on screen in words. |
| There is one gate | **There are three.** `ProactiveGate.speakIfIdle` (onboarding / busy / in-call / muted) has 11 raise sites, but `AmbientListener.kt:245` and `TelephonyController.kt:82` call `ProactiveBus.requestSpeak` **directly**, hand-rolling their own checks. `ProactiveBus` is deliberately NOT the choke point. |
| The five categories exist | **No.** Zero hits for `ProactiveCategory`, `Wellbeing`, or any proactive enum. They are a decision on the parent map and nothing else. |
| Anything wellbeing-shaped ships | **No.** Zero hits for bedtime, wind-down, rest. The "past 10pm" line has never existed. |
| The prompt layer is de-carred | **Only three files of it.** Commit `557c436` renamed 45 literals in `AriaBrain`, `AriaForegroundService` and `LiveSessionController` - and missed `LiveToolbox.kt`, which alone holds **183 literal lines saying "driver"**, ~149 of them in non-fleet tools, sent to the model every turn. [Ticket 11](issues/11-reframe-missed-the-toolbox.md). Verified 2026-08-21. |
| A periodic LLM pass is hypothetical | **No - one already ships.** `AmbientListener` runs a `SubAgent` over the overheard transcript, decides `SILENT` or not, and **writes the spoken line itself**. Shape (b) from [ticket 02](issues/02-trigger-engine.md) is live today, with no guard beyond the `SILENT` convention. Verified 2026-08-21. |
| A raise remembers what it already said | **No, and it cannot.** Every raise hand-rolls its own dedup state and all of it is in memory - `AriaForegroundService.kt:97` calls it "Process-life." The service is `START_STICKY`, so a restart re-arms every nudge. "Never nag twice" is impossible today, not merely weak. Verified 2026-08-21. |
| A raise supplies the facts it asks about | **Ten of eleven do.** The startup opener did not, and invented "lunch with Sam" on the phone (2026-08-21). Survey and the rule it argues for: [ticket 10](issues/10-what-a-raise-may-say.md). |
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
| 8 | **The trigger engine is HYBRID (shape c): deterministic rules decide whether to speak, an LLM only words the line.** (Kevin, 2026-08-21, [ticket 02](issues/02-trigger-engine.md).) | Same split as the reconciliation gate. `startHealthMonitor` is the existing worked example. No rule may be an LLM judgement call, and no tick spends a token to decide nothing. |
| 9 | **A raise's memory of itself lives in Room, not in a field.** (Kevin, 2026-08-21.) | One `proactive_raise` table backs never-nag-twice, [the budget](issues/05-quiet-hours-and-budget.md), and the reason affordance. Nothing new may hand-roll process-life dedup state. |
| 10 | **Every raise carries the reason that fired it.** (Kevin, 2026-08-21.) | Nearly free under decision 8 - the rule IS the reason. [Ticket 08](issues/08-proactive-register.md) owns how it is worded aloud. |
| 11 | **No raise may let the model decide BOTH that there is something to say and what it is.** (2026-08-21; `AmbientListener` was the only one and is [retired](issues/12-retire-ambient-listening.md).) | This is decision 8 stated as a prohibition, so a future feature cannot reintroduce shape (b) by the side door. |
| 12 | **The compulsion test, four clauses.** A raise is anchored to a fact Kevin could verify, actionable now, never references his absence or streak or engagement, and is silenceable forever in one instruction. **Lives in CLAUDE.md §7**, checked by a test over the raise registry. (Kevin, 2026-08-21, [ticket 03](issues/03-compulsion-test.md).) | Clauses (a) and (d) become machine-checked. **(b) and (c) stay human-reviewed** - do not let the test's existence imply all four are covered. |
| 13 | **A nudge about a goal Kevin set and then ignored is PERMITTED.** (Kevin, 2026-08-21, against the recommendation.) | The useful/guilt line is now TONE, not a checkable rule, and it rests on [ticket 08](issues/08-proactive-register.md)'s clause - the weakest lever available. Recorded so nobody re-derives the rejected fifth clause and thinks it is new. |
| 14 | **Master + five categories live in Room, not SharedPreferences.** Fresh install is QUIET (master on, every category off); an existing `muted=false` upgrades to Safety+Fleet+Timing on, so Kevin's behaviour does not change. (Kevin, 2026-08-21, [ticket 04](issues/04-categories-storage-and-surface.md).) | Eligible to sync, which is not the same as syncing - `sync/` has never executed. Do not document these as synced until a device pair proves it. |
| 15 | **Three unprompted SPOKEN lines a day. Safety is outside the cap, inside the master.** (Kevin, 2026-08-21, [ticket 05](issues/05-quiet-hours-and-budget.md).) | The only anti-compulsion guarantee that is countable. "Always speaks" is always shorthand for *while the master is on* - read every future exemption against that. |
| 16 | **Quiet hours are per category per window. Wellbeing may speak inside the night window; Digest, Fleet and Timing may not.** (Kevin, 2026-08-21.) | Resolves the founding tension by design rather than by exception: the rest nudge that started this map is legal where it lives. |
| 17 | **It speaks aloud THROUGH THE DAY, not only in a car** - gated on screen-on AND no live calendar event. (Kevin, 2026-08-21, [ticket 06](issues/06-delivery.md): *"thats the whole point of something like jarvis or alfred"*.) | The delivery-layer counterpart to the concierge reframe. Every existing raise assumes driving; none may after this. No calendar permission resolves to *unknown, so notify*, never *free, so speak*. |
| 18 | **A raise that cannot be spoken is NOTIFIED, never dropped**, on its own proactive channel. One delivery per raise - never both. (Kevin, 2026-08-21.) | The cap governs SPEECH, not existence. An OS channel is a second kill switch Kevin can use without the app knowing, so nothing may ever claim a notification was delivered or seen. |
| 19 | **A raise is a typed object, not a String** - id, category, rule, facts - and the bus refuses one whose facts are empty for a subject it invites the model to mention. (Kevin, 2026-08-21, [ticket 10](issues/10-what-a-raise-may-say.md).) | Churns all 11 call sites once and pays for three things: decision 7's contract, decision 9's raise row, and decision 18's "spoke, so do not notify". |
| 20 | **Unreadable and empty are different sentences, for every permissioned source.** (2026-08-21, generalised from `OpenerCalendarBriefing`.) | Applies to contacts, health, notifications, anything added later - not just the calendar. |
| 21 | **The proactive register is ONE shared clause every persona inherits**, at file scope in `ai/AriaBrain.kt` beside `CANNOT_CLAUSE`. A declined nudge suppresses that rule for a fixed window, silently. (Kevin, 2026-08-21, [ticket 08](issues/08-proactive-register.md).) | Never per-persona - that is the known weakness this map was told not to repeat. Suppression happens before the raise reaches the model, so there is no "second time" state for it to leak. |
| 7 | **An unsolicited prompt states the facts of any subject it asks the model to mention, or forbids that subject in words.** Silence about a subject is not neutral - it is where invention goes. (2026-08-21, from the invented "lunch with Sam"; wording and enforcement are [ticket 10](issues/10-what-a-raise-may-say.md)'s to settle.) | Every raise site pre-fetches or says "you do not know". Unreadable and empty must never render as the same sentence. |

## Decisions so far

<!-- one line per closed ticket -->

- [The compulsion line, written as a test](issues/03-compulsion-test.md) — **Four clauses accepted,
  into CLAUDE.md §7, checked by a test over the raise registry.** The hardest case went the other way
  from the recommendation: a nudge about a goal Kevin set and then ignored is **permitted**, which
  moves the useful-versus-guilt line out of a checkable rule and into tone. Said plainly on the ticket,
  including what it costs.

- [Five switches that actually switch something](issues/04-categories-storage-and-surface.md) —
  **Room-backed so they can sync, quiet by default, and Kevin's own phone carries its current
  behaviour.** A fresh install says nothing until asked; an existing `muted=false` becomes
  Safety+Fleet+Timing on. An empty category row says *"nothing uses this yet"* in words - a switch that
  governs nothing must not imply it does.

- [Quiet hours, and how often it may speak](issues/05-quiet-hours-and-budget.md) — **Three spoken
  lines a day, Safety uncapped, quiet hours per category per window.** The founding tension is resolved
  by design: Wellbeing may speak inside the night window, which is where the rest nudge lives. Over the
  cap means *not spoken*, never *lost* - it becomes a notification. A brush-off spends a slot and is
  inferred from the reply, with the imperfection of that inference stated and bounded.

- [Speak, notify, or wait](issues/06-delivery.md) — **It speaks aloud through the day**, Kevin
  overriding the session-gated recommendation: *"thats the whole point of something like jarvis or
  alfred right, throughout the day keeps me on track."* Gated on screen-on plus a calendar check for a
  live event. Own notification channel; anything unspeakable is notified; one delivery per raise, which
  fixes `ReminderAlarmReceiver`'s existing speak-and-notify echo.

- [How it sounds when nobody asked](issues/08-proactive-register.md) — **One shared clause every
  persona inherits**, at file scope beside `CANNOT_CLAUSE`, never per-persona. A declined nudge goes
  quiet for a fixed window and returns in an identical tone. Register-by-category is the better answer
  and is deferred, not lost. "Why did you say that?" names the rule and the fact, never a
  justification.

- [What an unsolicited prompt may contain](issues/10-what-a-raise-may-say.md) — **Contract
  accepted and made enforceable by a typed raise object**, because a convention with a comment is
  exactly what ticket 01 found had already failed once. Unreadable-versus-empty is promoted to a rule
  of the contract for every permissioned source, not a calendar detail.

- [What decides there is something worth saying](issues/02-trigger-engine.md) — **Hybrid (c): rules
  decide, the model words it.** `advisor/` turned out to be pull-only with a single caller
  (`LiveToolbox.kt:3488`) and no path to unprompted speech, so nothing collides - but its five
  deterministic `DigestBuilder`s and `DigestText`'s `[proven]`/`(estimate)`/`"not logged"` vocabulary
  are the right inputs, while `AdvisorAgent` itself is not reusable (it needs a human question and
  always spends a call). **`AmbientListener` was the live shape (b) and is retired.** The finding
  that shaped the rest: every raise's dedup state is process-life against a `START_STICKY` service,
  so "never nag twice" was impossible - a `proactive_raise` Room table now backs it, the budget, and
  the reason each raise carries. Four calls, full record on the ticket.

- [The boot-started service takes 123s to call startForeground](issues/09-fgs-start-delay.md) —
  **Closed as a misreading, from AOSP source. No fix, no bug.** `startForegroundDelayMs` is a benign
  diagnostic string appended to `mInfoAllowStartForeground`, measured from **ServiceRecord creation**
  and **sticky**, so a large value on a healthy service is normal. Two constants were being conflated:
  the 10s one only triggers a restriction re-check, while the ANR clock is a different 30s constant.
  Decisive: the string is emitted only when `!r.fgRequired`, and the ANR timer arms only when
  `r.fgRequired` - **a record that can print this field is one whose ANR timer was never armed.** The
  temp allowlist affects allow-START only. And point 4 was already true: `startForegroundCompat()` is
  the second statement of `onCreate`, so the delay was impossible from app code all along.

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
- ~~**Whether a declined nudge is remembered**~~ **Settled by [ticket 02](issues/02-trigger-engine.md)
  (decision 9): it is remembered, in a `proactive_raise` Room table.** What is still open is the
  narrower question of **how a brush-off is detected** - inferred from the reply, or asked for -
  and how long a refusal suppresses its rule.
- ~~**Per-category quiet hours** versus one global window.~~ **Settled 2026-08-21 (decision 16):
  per category, per window.** What is still open is the window's actual hours.
- **Register as a property of the CATEGORY**, not the persona - a 3am Safety warning and a Wellbeing
  rest nudge cannot share a delivery. Deferred by [ticket 08](issues/08-proactive-register.md), and it
  needs [the categories](issues/04-categories-storage-and-surface.md) built first. It sits ON TOP of
  the shared clause, never instead of it.
- **Whether the Digest category subsumes the morning brief** or merely delivers it. Waits on
  `.scratch/hands-and-senses/issues/08-morning-brief.md`.
- ~~**A "why did you say that?" affordance.**~~ **Settled: [ticket 02](issues/02-trigger-engine.md)
  decision 10 stores the reason, [ticket 08](issues/08-proactive-register.md) decides its wording.**
  What remains is building it.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **`CrisisDetector`.** Settled decision 3. It is not proactive speech and must not be folded in.
- **Making the notification listener a raise source.** Settled decision 4.
- **Any compulsion mechanic** - streaks, re-engagement pings, manufactured return, guilt for being
  away. CLAUDE.md §7, permanent, not this map's to reopen.
- **A Kevin-hosted backend, or push from anywhere but the device itself.**
- **Rebuilding the assistant's core register.** This map hands proactive-specific rules to whatever
  owns the voice; it does not redefine it.
