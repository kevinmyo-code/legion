---
map: proactive-mode
ticket: 13
title: "It speaks and you hear nothing, once Spotify takes audio focus"
type: bug
status: built
status-detail: "2026-08-21 - root-caused from a real drive log and fixed; owes a drive to confirm"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# It speaks and you hear nothing, once Spotify takes audio focus

## The report

Kevin, from a real drive, 2026-08-21: *"transcript subtitles say sir? but nothing is said on
speakers. same with spotify skip track etc. i see the subtitles but dont hear anything."*

Everything else in that drive worked - all Spotify functions verified, wake word acceptable.

## Root cause, from the log rather than from theory

The drive's logcat buffer was still intact and contained, at 17:11:39:

```
AudioManager: dispatching onAudioFocusChange(-1) to ...GeminiLiveSession
```

**-1 is `AUDIOFOCUS_LOSS`** - permanent, not transient. Spotify (or the car) took focus outright.

**The trap is one line.** `GeminiLiveSession.duckNow()` opens with `if (ducked) return`. Nothing
cleared `ducked` on a focus loss, because the focus listener was **diagnostic only** - it logged and
returned. So:

1. Focus is lost. `ducked` stays `true`, describing a state the app is no longer in.
2. Every later turn calls `duckNow()`, hits the guard, and **never re-requests focus**.
3. The `AudioTrack` keeps being written to, and the system keeps it inaudible.
4. **Subtitles keep working**, because output transcription arrives over the socket and never
   touches the audio path.

That last step is why this presented as *"it works but is silent"* rather than as a failure, and why
no error appears anywhere. It also explains "sometimes": it needs Spotify to grab focus once, and
then it persists for the rest of the session.

## The fix

The listener now clears `ducked` on any of `AUDIOFOCUS_LOSS`, `LOSS_TRANSIENT`, and
`LOSS_TRANSIENT_CAN_DUCK`. That is all. The next turn re-requests focus honestly instead of
short-circuiting.

**Deliberately does NOT** pause playback, alter capture, or try to grab focus back. Taking focus
from whatever just claimed it is how two apps fight over a car stereo. **Losing focus is normal** -
a call, a notification, Spotify. Never noticing was the bug.

## The comment that made this findable

The listener's own doc said, when it was added on 2026-08-17, that reacting to focus loss before a
real log pull *"would be exactly the kind of guess this file's own delicacy warns against"* - and
that the evidence had to come from a real pull first.

**That was right, and it is why the listener existed at all.** Without it there would have been no
`onAudioFocusChange` line in the log to find, and this would have stayed a mystery about the car
stereo. The instrumentation was added before the fix was justified, and the fix waited for its
evidence. Both halves mattered.

## Owed

- **A drive.** Play Spotify, let it take focus, then talk. Speech should be audible.
- The `AUDIO_FOCUS` lines now go to `CarProbeLog` as well as logcat, so a repeat is one pull away.

## Not this ticket

**The wake word is sometimes weak** - same drive, same report, different subsystem. Filed separately
rather than folded in here, because a focus fix must not be credited with a microphone improvement
it did not make.

## A SECOND, larger bug found while diagnosing "no announcement on an incoming call"

2026-08-21, same evening. A call came in and nothing was announced. The raise history had **no
`incoming_call` row at all** - and a row is only written once a raise passes the gate, so it never
got that far.

It was not the call code. `dumpsys` on the service:

```
startForegroundCount=0
infoAllowStartForeground=[... code:DENIED ...]
```

and in the log, twice:

```
ForegroundServiceStartNotAllowedException: startForegroundService() not allowed
  due to mAllowStartForeground false
MidnightEvents: app_start_failed stage=resume_assistant_ignition
```

**The assistant service was not running, and had not been for 45 minutes.** Nothing could announce
a call because nothing was listening. Filed and fixed as
[wake-word ticket 11](../../wake-word/issues/11-service-refused-to-start.md).
