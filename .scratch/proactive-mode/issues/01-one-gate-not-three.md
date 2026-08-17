# One gate, not three

Type: grilling
Status: open
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
