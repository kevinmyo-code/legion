# The proactive switch: Alfred speaks first

Type: grilling
Status: open
Blocked by: -

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
3. **~~One switch or many.~~ SETTLED (Kevin, 2026-08-16): a master switch plus per-category
   control.** One toggle is the primary control and the thing Kevin flips; categories inherit it
   and can be tuned after. What remains to decide here:
   - **The category list.** Candidates, each owned by the ticket that raises it:
     safety (weather/disaster, from [location intelligence](15-location-intelligence.md)),
     departure ("leave now", same ticket), goals, fleet (maintenance due, DTC, recall),
     [morning brief](08-morning-brief.md), [notifications](04-notification-listener.md),
     [health](11-health-connect-scope.md), [people dates](19-people-dates.md).
     Fewer, broader categories beat a settings page nobody reads - argue the cut.
   - **Tri-state or two.** A category that is explicitly ON should probably survive the master
     going off only for safety; everything else should die with the master. So: does each
     category store on / off / inherit, or just an override flag? Simple first.
   - **Whether any category is exempt from the master.** An NWS tornado warning at Kevin's
     location is the candidate. If nothing is exempt, the master is a true kill switch, which is
     cleaner and easier to trust - decide deliberately, because a switch that does not fully
     silence is a switch nobody believes.
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
