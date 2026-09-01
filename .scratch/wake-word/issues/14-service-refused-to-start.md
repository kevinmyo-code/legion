---
map: wake-word
ticket: 14
title: "The assistant silently stops running, and every surface still says On"
type: bug
status: resolved
status-detail: "2026-08-21 - made visible rather than defeated; owes a run on the phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The assistant silently stops running, and every surface still says On

## Found by chasing something else

Kevin: *"call came in > no announcement from ai."* The raise history had **no `incoming_call` row**,
and rows are only written once a raise passes the gate - so it never reached the gate. That ruled
out the call code immediately.

`dumpsys activity services`:

```
startForegroundCount=0
infoAllowStartForeground=[... code:DENIED ... targetSdkVersion:34 ...]
```

and in logcat, at 18:02 and again at 18:09:

```
ForegroundServiceStartNotAllowedException: startForegroundService() not allowed
  due to mAllowStartForeground false
MidnightEvents: app_start_failed stage=resume_assistant_ignition
```

**The service was not running and had not been for 45 minutes.** Nothing could announce a call
because nothing was listening.

## The false assumption, which was written down

`MidnightApplication.onCreate` calls `AssistantIgnition.resumeIfEnabled`, under this comment:

> Safe to call from a foreground app launch specifically because the app is starting because the
> user opened it, so none of the background-foreground-service-start restrictions apply

**`Application.onCreate` runs whenever the PROCESS starts** - a broadcast, a job, a content-provider
touch, a package replace. Not only when someone opens the app. From API 31 Android refuses a
foreground-service start from the background regardless of what the app believes it is doing, and
this app targets 34.

So the persisted flag said On, `AssistantIgnition.isEnabled` agreed, every surface repeated it, and
nothing ran. **This is the exact failure `80a1758` was written to end**, arriving through a
different door - and [proactive-mode ticket 09](../../proactive-mode/issues/09-fgs-start-delay.md)
predicted precisely this shape: "the process dies with a fatal exception, and per the same commit's
own finding every surface will still read On."

## The fix: make it visible, do not try to defeat it

**Android will not be argued out of this restriction**, and pretending otherwise would produce a
worse bug. So:

- `AriaForegroundService.isRunning` - a truthful, process-scoped flag set in `onCreate`/`onDestroy`.
  It answers "is it up", never "is it supposed to be up".
- `AssistantIgnition.resumeIfEnabled` catches the refusal, records `startRefused`, and logs a
  retrievable event instead of throwing into a `runCatching` that discards it.
- **The settings row now reports reality**: *"On, but NOT running - Android blocked it from starting
  in the background. Reopening the app fixes it."*

That last line is the whole point. A switch that describes a stored boolean rather than the running
system is the same class of lie as a kill switch that does not silence.

## What genuinely recovers it

`MainActivity` already calls `resumeIfEnabled` on resume, and a launch IS a foreground start Android
permits. **Opening the app fixes it** - which is why this was survivable rather than fatal, and why
it went unnoticed: Kevin opens the app often enough that the window is usually short.

## Owed

- On the phone: force-stop, let the process restart in the background, confirm the row says "NOT
  running" rather than "On", then open the app and confirm it recovers.
- **Not attempted here:** getting the service back up without the app being opened. That is a real
  question - `BootReceiver` already does it at boot, and whether a narrower trigger exists for the
  mid-life case belongs with proactive-mode ticket 09's scheduling work rather than in a bug fix.
