# Speak, notify, or wait

Type: grilling
Status: open
Blocked by: 07

## Question

`ProactiveGate.speakIfIdle` speaks aloud **only when a session is idle**. A goal nudge at 10pm with
the phone in a pocket therefore says nothing at all - the raise is silently dropped.

Blocked by [scheduling research](07-scheduling-research.md), because what a background process may
actually do in 2026 Android constrains every option here.

Decide:

1. **Which raises speak, which notify, which wait for the next session.** Probably a mix, and
   probably a property of the category rather than the individual raise.
2. **What a notification looks like.** LEGION already posts on three channels - `aria_channel`
   (foreground service, LOW), `ledger_ingest_channel` (LOW), `reminders_channel` (HIGH, the only
   noisy one). **Does proactive get its own channel?** A channel is also a second kill switch the OS
   gives Kevin for free, which cuts both ways: it is a real escape hatch, and it is a way for him to
   silence LEGION without the app knowing.
3. **What happens to a raise nobody heard.** Dropped, retried, or queued for the next session. **A
   silently dropped safety warning is the worst outcome on this map** - and the current behaviour
   drops everything when the phone is idle-but-locked.
4. **Speaking aloud when the phone is not in the car.** Every existing raise assumes a driving
   context. A 10pm rest nudge does not. **Does Alfred ever speak aloud outside a car session**, or is
   voice strictly a driving surface and everything else a notification?
5. **The echo hazard**, already found: `ReminderAlarmReceiver` speaks a fired reminder AND posts a
   notification for the same item in the same method. Whatever is decided here must not double up.
