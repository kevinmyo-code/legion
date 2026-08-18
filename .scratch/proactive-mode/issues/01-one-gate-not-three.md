# One gate, not three

Type: grilling
Status: resolved (2026-08-18, Kevin - 3 calls, plus one premise correction)
Blocked by: -

## Question

**This blocks the honesty of everything else on the map.** Kevin settled that the master is a true
kill switch with nothing exempt. **Today that is impossible**, and it has nothing to do with the
categories not existing yet.

Verified 2026-08-16, all `traced`:

- `ProactiveGate.speakIfIdle` (`service/ProactiveGate.kt:20-29`) checks onboarding-complete, not
  busy, not in a call, not muted. **11 raise sites** go through it: `ReminderAlarmReceiver.kt:70`
  directly, plus ten via `AriaForegroundService.speakProactive` (first-meeting greeting, ignition
  opener, NHTSA recalls, new trouble code, coolant overheat, place arrival, two-hour break nudge,
  rough weather, odometer milestone, idle chatter twice).
- **`AmbientListener.kt:245` and `TelephonyController.kt:82` bypass it entirely**, calling
  `ProactiveBus.requestSpeak` directly and hand-rolling their own checks. `ProactiveBus` is
  deliberately **not** the choke point (`ProactiveBus.kt:18-23`).

So a master switch that gates `ProactiveGate` silences 11 of 13 paths and leaves two speaking.

Decide:

1. **Where the choke point goes.** Two shapes: (a) bring the two bypassers back through
   `ProactiveGate`; (b) move the gate INTO `ProactiveBus` so nothing can raise without passing it.
   (b) is structurally safer - it makes bypass impossible rather than merely unused - but
   `ProactiveBus.kt:18-23` documents a deliberate reason it is not the choke point today. **Read
   that reason and answer it rather than overriding it silently.**
2. **`TelephonyController`'s incoming-call announcement - is it even proactive speech?** It is a
   response to an external event, not an initiation, which is arguably closer to `CrisisDetector`
   (settled decision 3) than to a nudge. If it is not proactive, it belongs outside the switch and
   this becomes a two-gate problem. **Decide deliberately; do not fold it in by default.**
3. **`AmbientListener`'s asymmetry.** It treats mute as a hard **listening** gate, not just a
   speaking gate (`:41-46`, `:110-113`) - stricter than `ProactiveGate`. That is deliberate today.
   Keep it or kill it on purpose: a master switch that also stops the microphone is a stronger
   privacy promise, but it means "stop talking" and "stop listening" stop being separable.
4. **What happens to the 11 existing raises when categories arrive?** Each must map onto one of the
   five, or be retired. First-meeting greeting and idle chatter are neither goal-aware nor
   time-aware - **are they Wellbeing, or are they the ambient car chatter that a nudge system should
   deprecate?** The parent ticket flags that "ambient car chatter" and "goal nudges" are different
   products sharing one pipe.

**This ticket carries its own build spec once resolved** - it is a prerequisite rather than a
feature, and the categories ticket cannot land honestly before it.

## Answer

**Resolved 2026-08-18.** Stark decided call 1 (implementation, per this map's standing preference);
Kevin decided calls 2, 3 and 4.

### 0. Premise correction, traced 2026-08-18

The question above says a master switch gating `ProactiveGate` "silences 11 of 13 paths and leaves
two speaking". **Both bypassers do check mute**: `AmbientListener.kt:113` and
`TelephonyController.kt:80` each call `ProactivePreferences.isMuted`/`muted.value` before raising.
They hand-roll the check rather than skip it.

What is actually broken is narrower and worse-shaped than "two paths ignore the switch":

- **Nothing enforces it.** Three authors each remembered. The fourth raise site added by anyone,
  ever, inherits nothing.
- **The hand-rolled copies disagree.** Neither bypasser checks `OnboardingState.isComplete`, which
  `ProactiveGate` does - so both can speak over first-run setup, which is a live defect today, not a
  hypothetical one.

The conclusion is unchanged (one choke point), but the urgency is different: today's kill switch
works by convention, and this ticket converts it to structure.

### 1. Where the choke point goes - into `ProactiveBus`, with the documented objection answered

`ProactiveBus.kt:18-23`'s reason for not being the choke point is **correct and stays honoured**:
`DtcSheet`'s ASK button is driver-tapped, and a mute must never silence a button the driver just
pressed.

The answer is that the bus was never the problem - having ONE undifferentiated `requestSpeak` was.
Split it by who asked:

- `ProactiveBus.speakSolicited(prompt)` - the driver asked, by voice or by tap. Never gated. The
  `DtcSheet` ASK path and anything else driver-initiated calls this, and its name says why it is
  exempt instead of leaving that to a comment.
- `ProactiveBus.speakIfAllowed(context, prompt): Boolean` - the ONLY unsolicited path. Runs the four
  checks (onboarding complete, not busy, not in a call, not muted) and returns whether it spoke.
- The raw emit goes **private**. Bypass becomes impossible rather than merely unused, which is
  option (b)'s structural safety without overriding (a)'s reason.

`ProactiveGate.speakIfIdle` stays as a thin delegate so the 11 existing raise sites do not churn.

### 2. Incoming-call announcement - INSIDE the switch (Kevin)

Settled decision 2 says the master is a true kill switch with nothing exempt, "because a switch that
does not fully silence is one nobody believes". Call announcements are the first candidate exemption
and are refused on exactly that ground. `TelephonyController.announceIncoming` routes through
`speakIfAllowed` like everything else. Consequence, accepted: mute and the assistant stops telling
you who is calling.

This does NOT touch `CrisisDetector` (settled decision 3) - that responds to something Kevin said.

### 3. The mic gate stays (Kevin)

`AmbientListener`'s stricter treatment - mute stops it LISTENING, not merely reacting - is kept, as
Kevin's original explicit requirement. The master switch is a privacy promise, not a politeness
setting. Accepted cost: "stop talking" and "stop listening" are not separable.

### 4. The 11 existing raises - map the useful, retire the filler (Kevin)

Retired outright, three raise sites:

- **`speakQuietLine`, both branches** (`AriaForegroundService.kt:802-819`). Both fire on SILENCE
  rather than on anything being due - including the "offer to run through your list" branch, which
  is list-aware but not due-aware. Speech triggered by the absence of conversation is the closest
  thing in this app to a mechanism engineered to produce engagement, which CLAUDE.md sec 7 bans by
  name. The timer that drives it goes with it.
- **The first-meeting greeting** (`AriaForegroundService.kt:415`). The `markFirstSessionDone` write
  beside it STAYS - the avatar-tap greeting depends on that flag.

Kept, and each maps onto a category in ticket 04: coolant overheat and the two-hour break nudge to
**Safety**; new trouble code, NHTSA recalls and the odometer milestone to **Fleet**; the ignition
opener and place arrival to **Timing**; fired reminders to **Timing**; rough weather to **Safety**.
The ambient listener's reaction is Wellbeing-adjacent and is ticket 04's call, not this one's.

### 5. Carried into the build, and what is NOT in it

**In:** the split bus, both bypassers converted (which also closes the onboarding hole), the three
retirements, and **a Settings row that actually flips the master switch** - `setMuted`/`toggle` have
had zero callers since they were written, so the switch this whole map depends on has never been
reachable by a human.

**Not in, deliberately:** the five categories and their storage/surface (ticket 04), quiet hours and
the nudge budget (05), and the default. The master currently defaults to UNMUTED, so proactive is on
out of the box; whether an opt-in default is right is ticket 04's call, and flipping it here would
silence things Kevin has today without him asking for that.
