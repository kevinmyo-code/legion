---
map: hands-and-senses
ticket: 24
title: "The socket restarts every 2.5 minutes, all day"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The socket restarts every 2.5 minutes, all day

## What was observed

Found incidentally while chasing the deaf-mic bug ([[15-see-a-deaf-mic]]), in three separate
logcat captures on the A25, 2026-08-22, with nobody using the app:

```
12:36:19 session_end: The operation was aborted.   12:36:21 session_start
12:38:54 session_end: The operation was aborted.   12:38:55 session_start
12:41:27 session_end: The operation was aborted.   12:41:27 session_start
12:44:00 session_end: The operation was aborted.   12:44:00 session_start
12:48:09 ...  12:50:42 ...  12:53:14 ...  12:55:47 ...
```

**Every 2 minutes 33 seconds, consistently, and the app reconnects immediately every time.** That is
roughly **565 reconnects a day** on Kevin's own BYO key.

`session_end` with that text comes from `onClosed` (`GeminiLiveSession.kt:841`), so the SERVER is
closing it and "The operation was aborted." is the server's stated reason. `WARM_HOLD_MS` is 180
seconds, so **this is not the app's own warm-hold expiry** - the numbers do not match and that
explanation must not be assumed.

## Why this matters more than "it reconnects, so what"

**Every connect re-sends the entire context.** `buildSetup` (`GeminiLiveSession.kt:852`) puts
`systemInstruction` AND the full `tools` array on every single setup message. That is **101 tool
declarations**, re-established from scratch on each reconnect.

**The cost is an ESTIMATE and must be treated as one until measured.** One data point exists:
`manage_item`'s declaration was measured at roughly 1,004 tokens when it was trimmed on 2026-08-17,
and was then described as the largest of 79. A whole-surface figure has never been measured. So the
honest statement is: the re-sent payload is plausibly tens of thousands of input tokens, multiplied
by ~565 a day, and nobody has checked.

**Measuring it is the first deliverable, not the fix.** `countTokens` against the assembled setup
payload gives a real number. A fix argued from a guessed number is how a non-problem gets optimised
and a real one gets missed.

## What to find out, in order

1. **Measure the setup payload.** Real token count for `systemInstruction` plus the 101 declarations.
2. **Why 153 seconds?** Establish whether this is a documented server-side session lifetime, an idle
   timeout, a keepalive that is missing, or something the app is doing. **Do not start from a
   theory** - the deaf-mic ticket earned that rule and it applies here.
3. **Does the prewarmed socket need to exist at all when nobody is talking?** It is held so a tap
   answers instantly. That is a real benefit and this ticket must not assume it away. But "warm all
   day" and "warm when he is likely to speak" are different products, and the second may cost a
   fraction of the first.
4. **Does session resumption already avoid the re-send?** `sessionResumption` is already requested
   and a handle is already carried (`resumeHandle`). If a resumed session skips re-establishing the
   tool surface, the cost may already be far lower than the raw arithmetic suggests - and if it does
   not, that is the finding.

## Verification

- The measured token figure, written down here, with how it was measured.
- Whatever changes, the tap-to-talk latency Kevin already has must not get worse without that being
  stated plainly as the trade.
