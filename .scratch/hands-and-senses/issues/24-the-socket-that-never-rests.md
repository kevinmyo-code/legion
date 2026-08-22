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

## Measured 2026-08-22

Deliverable 1 only. Nothing in production changed - this is the number, not a fix.

### The setup payload

`app/src/test/java/com/kevin/legion/service/LiveSetupPayloadSizeTest.kt` assembles the real
`LiveToolbox.declarations()`, wraps it exactly as `buildSetup` does (a `googleSearch` entry plus one
`functionDeclarations` object), and serialises it.

| Part | Chars | Estimated tokens |
|---|---:|---:|
| tools array (66 declarations) | 56,879 | ~14,219 |
| system instruction (static half) | 7,879 | ~1,969 |
| **total** | **64,758** | **~16,189** |

**The estimator is chars/4** - the same arithmetic `PrimingTopic.MAX_CHARS` and
`PlaybookKeywordsTest` already use, whose accuracy is documented there as about 4% against
`countTokens` on this codebase's prose. **Every token figure above is an ESTIMATE.** JSON punctuation
and identifiers tokenize worse than prose, so the real count is likely somewhat higher, not lower.
No `countTokens` call was made; that needs a live key and would produce a figure not comparable with
the ones already written down in this repo.

**66 declarations, not 101.** 101 is every `fn(name = ...)` literal in `LiveToolbox.kt`, which
includes the onboarding-only set and the tools the 2026-08-17 dispatcher split hid behind an `ask_*`
tool. `declarations()` - what the setup message actually carries - returns 66.

**What the system-instruction figure covers.** The device-independent half only: the shipped `ALFRED`
register clause plus its delivery note, plus `SHARED_INSTRUCTIONS`. Excluded because they need a
Context, a Room read or today's date: `AriaBrain.safetyInstructions` (private; 1,099 chars, ~274
tokens, counted off the source), the date/clock block, the driver-profile fragment, the fleet
fragment, and all of `buildLiveContext`. So the real per-connect instruction is larger than 7,879
chars, by a few hundred tokens plus whatever live context is current.

**Daily arithmetic, at ~565 reconnects:** ~16,189 x 565 = **~9.1M estimated input tokens a day**, with
nobody using the app. Estimate on an estimate - the 565 comes from the observed 153-second cadence
held for a full day, which was itself only sampled across a few captures.

### The largest declarations

| Tool | Chars | ~tokens |
|---|---:|---:|
| `control_music` | 3,505 | ~876 |
| `manage_item` | 3,485 | ~871 |
| `play_music` | 2,413 | ~603 |
| `generate_goal_plan` | 2,052 | ~513 |
| `browse_my_music` | 1,998 | ~499 |
| `set_goal` | 1,654 | ~413 |

The music tools are now the top of the list, above `manage_item` - which was the largest of 79 at
~1,004 tokens when it was trimmed on 2026-08-17 and is ~871 now. The full 66-row breakdown prints
from the test's `per-tool breakdown names the largest declarations` case.

### The number is now a checkable fact

The test asserts the total stays under **20,000 estimated tokens** (about 23% of headroom over
today's ~16,189) and fails the build if the tool surface grows past it, the same posture
`PlaybookKeywordsTest` takes toward a playbook's size. Today's measured value is written into the
comment beside the ceiling so a future reader can see the growth.

### Question 4: does a resume skip the re-send? No.

**Traced, not reasoned.** `requestedResumeHandle` is read in exactly one place in the whole file
(`GeminiLiveSession.kt:878`), inside `buildSetup`:

```kotlin
put("sessionResumption", JSONObject().apply {
    requestedResumeHandle?.let { if (it.isNotBlank()) put("handle", it) }
})
```

`put("systemInstruction", ...)` and `put("tools", tools)` sit above it and are unconditional - there
is no branch on the handle anywhere else in `buildSetup`, and `SocketListener.onOpen` sends
`buildSetup(systemInstruction, functionDeclarations)` on every `onOpen` regardless. All four
`LiveSessionController` call sites pass `resumeHandle = sessionResumeHandle`.

**So a resumed connect and a cold connect send a byte-identical tool surface and system
instruction.** The only difference is the presence of one `handle` string. **The raw arithmetic
above stands; resumption does not discount it.** That was the decision-relevant unknown and it
resolves the expensive way.

## Verification

- The measured token figure, written down here, with how it was measured.
- Whatever changes, the tap-to-talk latency Kevin already has must not get worse without that being
  stated plainly as the trade.
