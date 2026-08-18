---
map: hands-and-senses
ticket: 21
title: "The proactive switch: Alfred speaks first"
type: grilling
status: graduated
status-detail: (2026-08-16) to .scratch/proactive-mode/
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The proactive switch: Alfred speaks first

material. **Question 3 is already SETTLED by Kevin and carries over as a settled decision of the
new map, not as an open question.** Every other raising ticket across LEGION inherits this map's
rules. See map.md, "Efforts in disguise".

## Question

Kevin (2026-08-16): "I want a toggle for the AI to be proactive. I have goals, alerts, and
location-aware stuff. Flip a switch and it reminds me. Much like Alfred does to Batman - it's past
10pm, perhaps rest is in order."

**Verified 2026-08-16: a proactive layer exists, and it is the wrong shape for this.**

What exists:
- `service/ProactivePreferences.kt` - ONE global boolean, `muted`, default false. So proactive is
  already ON, and the switch is an inverted mute rather than an opt-in.
- `service/ProactiveGate.speakIfIdle()` - gates on idle / muted / in-call / onboarding before a
  line reaches `ProactiveBus.requestSpeak()`.
- Existing callers, per the gate's own doc comment: the opener, drive-monitor chatter, arrival
  reminders, health alerts. **All car-shaped ambient chatter, none goal-aware, none time-aware.**
- `goals/GoalController` + `GoalProgress`, `advisor/` (playbooks + digests + `AdvisorAgent`),
  `sleep/SleepTarget`, and the whole body aspect - the raw material for real nudges, **but whether
  any of it is wired to a proactive raise is unverified. Zoom `AdvisorAgent` first.**

What does not exist: any trigger engine that evaluates goals, time of day, or location and decides
something is worth saying. Quiet hours. Per-category control. Any notion of a nudge budget.

Decide:

1. **What evaluates triggers, and this is the architectural fork.** Three shapes: (a) a
   deterministic rule engine (cheap, predictable, zero tokens, dumb); (b) a periodic LLM pass over
   current state (smart, varied, costs money on every tick, nondeterministic - it can invent a
   reason to speak); (c) **hybrid: deterministic rules decide WHETHER to speak, an LLM only
   phrases the line.** (c) is the same split as the reconciliation gate - determinism owns the
   decision, the model owns the prose - and it is the recommendation to argue against, not a
   foregone conclusion.
2. **The compulsion line, written as a test rather than a vibe.** CLAUDE.md §7 bans compulsion
   mechanics: streaks, re-engagement pings, manufactured return, guilt for being away. An Alfred
   rest-nudge is mechanically IDENTICAL to a re-engagement ping - same notification, same
   unprompted speech. The difference is content and intent, so it must be written as something a
   future ticket can be checked against. Proposed, to accept or redraw: a raise must (a) be
   anchored to a fact Kevin could verify himself (the clock, a goal he set, a sleep target, an
   NWS alert), (b) be actionable right now, (c) **never reference his absence, streak, or
   engagement with the app**, and (d) be silenceable forever in one instruction. (c) and (d) are
   the load-bearing halves; without them "it's past 10pm" becomes "you haven't talked to me in
   three days".
3. **~~One switch or many.~~ SETTLED (Kevin, 2026-08-16).** Master switch plus per-category
   control, **five categories, no tri-state, and the master is a true kill switch.**

   | Category | Raises | Owned by |
   |---|---|---|
   | **Safety** | NWS warnings, disasters at Kevin's location | [location intelligence](15-location-intelligence.md) |
   | **Timing** | "leave now" departure, calendar-anchored nudges | [location intelligence](15-location-intelligence.md) |
   | **Wellbeing** | goal nudges, health, the past-10pm rest line | [health](11-health-connect-scope.md), `goals/` |
   | **Fleet** | maintenance due, open DTCs, recalls | fleet aspect, [clear DTC](01-clear-dtc.md) |
   | **Digest** | morning brief, birthdays and dates | [brief](08-morning-brief.md), [people dates](19-people-dates.md) |

   - **Two states per category, not three.** On or off. No inherit state, no override flag - a
     category is a plain boolean and the master ANDs over all of them. Simple first.
   - **Nothing is exempt from the master, safety included.** Off means silent. A switch that does
     not fully silence is a switch nobody believes, and trust in the kill switch is what makes
     proactivity acceptable at all. (Note the one thing this does NOT cover: `CrisisDetector` is
     not proactive speech - it is a response to something Kevin said, so it is untouched by this
     switch and must stay that way.)
   - **The phone's notification listener is NOT a category.** It stays pull-only unless
     [ticket 04](04-notification-listener.md) argues otherwise; if it ever raises, it joins
     Timing rather than earning a sixth switch.
   - **Where it lives.** `SettingsScreen`/`SettingsRows` exist; `mission-control` owns screen
     aesthetics. Coordinate with that map rather than building a surface it re-skins.
4. **Quiet hours and the nudge budget.** "Past 10pm, perhaps rest" is itself a late-night line, so
   quiet hours cannot simply mute everything at night - the rest nudge lives THERE. Decide: what
   is silenced when, what may always speak (safety: NWS warning, crisis), and **how many times a
   day may Alfred speak unprompted at all?** A hard daily cap is the cheapest anti-annoyance
   mechanism and the strongest anti-compulsion guarantee.
5. **Delivery.** `ProactiveGate.speakIfIdle` speaks aloud only when a session is idle. A goal nudge
   at 10pm with the phone in a pocket needs a notification instead. Which raises speak, which
   notify, which wait silently for the next session? Note `MEMORY.md`: app logs DO reach logcat on
   the A25, so this is debuggable on-device.
6. **When it evaluates.** Doze and battery govern. Options: WorkManager periodic, AlarmManager for
   time-anchored nudges, geofence callbacks for location ones, or piggyback on existing wake
   events (drive end, arrival, unlock). Probably a mix - name which trigger class uses which, and
   confirm nothing needs a persistent wakelock.
7. **The register, and it is the whole feel of the product.** "It's past 10pm, perhaps rest is in
   order" is dry, deferential, and easy to ignore - it offers rather than instructs, and it never
   nags twice. That voice belongs in [the assistant identity](12-assistant-identity.md); this
   ticket should hand that ticket the proactive-specific rules (never repeat a declined nudge,
   never escalate tone, one line not a paragraph).
8. **Deprecating the mute.** Today `muted` is a global inverted boolean already shipping. Does the
   new model replace it, wrap it, or keep it as the master switch's storage? Existing callers
   (opener, drive chatter, arrival) must keep working or be deliberately retired - decide which,
   because "ambient car chatter" and "goal nudges" are different products sharing one pipe.

---

## Carried input (from ticket 04's premise check, 2026-08-16)

Ticket 04 was archived without being answered, but its premise check surfaced a fact this ticket
must build on. All `traced`.

**There is not one proactive gate. There are three.**

`ProactiveGate.speakIfIdle` (`service/ProactiveGate.kt:20-29`) is the shared one - onboarding
complete, not busy, not in a call, not muted - and **11 raise sites** go through it
(`ReminderAlarmReceiver.kt:70` directly, plus 10 via `AriaForegroundService.speakProactive`:
first-meeting greeting, ignition opener, NHTSA recalls, new trouble code, coolant overheat,
place arrival, two-hour break nudge, rough weather, odometer milestone, and idle chatter twice).

**But two callers bypass it entirely**, calling `ProactiveBus.requestSpeak` directly and
hand-rolling their own checks: `AmbientListener.kt:245` (which re-implements busy/call/mute at
`:240-243`) and `TelephonyController.kt:82` (incoming-call announcement). `ProactiveBus` is
deliberately **not** the choke point (`ProactiveBus.kt:18-23`).

**Consequence for this ticket:** replacing `ProactivePreferences`' single `muted` boolean with the
settled five categories is not enough. Anything raised through the bus inherits nothing, so the
master kill switch settled by Kevin ("a true kill switch, nothing exempt") **cannot be honoured
until the bus itself becomes the choke point or the two bypassers are brought back through the
gate.** Decide which.

Also relevant: `AmbientListener` treats mute as a hard **listening** gate, not just a speaking gate
(`:41-46`, `:110-113`) - stricter than `ProactiveGate`. That asymmetry is deliberate today and this
ticket should either keep it or kill it on purpose.

## Answer

**GRADUATED 2026-08-16 to `.scratch/proactive-mode/`** (Kevin: "chart it properly"). 8 tickets.

This ticket closes as a pointer, per the parent map's "Efforts in disguise" rule. Its body became the
new map's raw material, and everything settled here carries over as a **settled decision** of that
map rather than as an open question: master plus five categories, two states each, master is a true
kill switch with nothing exempt, `CrisisDetector` untouched, the notification listener is not a
category.

**The new map's tickets:** the choke point (one gate, not three); the trigger engine; the compulsion
test written checkable; categories to storage and surface; quiet hours and the nudge budget;
delivery; scheduling research; and the proactive register.

**[The choke point](../../proactive-mode/issues/01-one-gate-not-three.md) is first and blocks the
categories**, because Kevin's kill switch cannot be honoured while two callers bypass the gate.
