---
map: drive-test-2026-08-18
ticket: 01
title: "A conversation waits for the driver, not a ten-second timer"
type: task
status: resolved
status-detail: "2026-08-18, fixed in commit 1e3ee04 - NOT re-tested on a drive"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# A conversation waits for the driver, not a ten-second timer

## Question

Kevin, on a real drive, 2026-08-18: *"conversation drops after 3 turns."*

### Root cause, traced 2026-08-18

After every completed turn the `vadMode` branch of `turnComplete` armed a 10-second idle timer. On
expiry, `keepWarm && vadMode` took `parkWarm()`: the mic stopped, the socket parked, `LiveEvent.Idle`
was emitted, and `LiveSessionController`'s `Idle` branch set `Phase.IDLE` **with no `showNotice`** -
unlike the `Closed` branch right below it, which does flash one.

So a normal driving pause - a mirror check, a merge, finishing a thought - silently ended hands-free
listening and put the strip back to "Tap to talk". Three exchanges is about how long it takes to
accumulate one 10-second gap.

`IDLE_TIMEOUT_MS = 10_000L` (`service/GeminiLiveSession.kt:1932`).

### This had already been found once, and the fix was never applied where it mattered

The identical failure was found during **onboarding** and fixed by making the timeout a per-`start`
parameter, `idleTimeoutMs` (`GeminiLiveSession.kt:188`, `:432`, `:448`). Its own comment says the 10s
default "was being hit by completely normal 'let me think about that' pauses".

**No caller ever passed it.** All three `s.start(` sites in `LiveSessionController.kt` - `:187`,
`:388`, `:408` - leave the default. So every hands-free conversation still ran on 10 seconds, on a
drive, where such pauses are more frequent rather than less.

This is the L11 shape again: a correct finding, a correct fix, and nobody checked that the fix
reached the path the driver actually uses.

## Answer

**Resolved 2026-08-18, fixed in commit `1e3ee04`.** Kevin's call, asked directly.

### The decision

A hands-free conversation now waits **indefinitely**. It ends when:

| Ender | Where |
|---|---|
| Kevin taps the strip | `LiveSessionController.kt:230` - `onTap`'s `inConversation -> stop()` |
| The socket dies | Already flashes CONNECTION LOST and returns the strip to a persistent "Tap to talk" |
| The crisis path fires | `ai/CrisisDetector.kt`, unchanged |

Any timer left armed by an earlier turn is cancelled, so a stale one cannot fire mid-chat.

`armIdleTimeout` now governs **speak-only sessions only** - its one remaining caller: `vadMode`
false, no mic open, nothing to keep warm for. The warm park itself is unaffected and is still reached
by the proactive `suppressMicNextTurn` branch.

### Accepted consequence

**An active conversation has no upper bound.** A forgotten one holds a live mic and a billed Gemini
session open until the service is torn down. Kevin accepted this explicitly rather than trading it
for a longer timer, on the ground that any timer is the same defect with a larger number.

If that cost ever bites, the replacement is a **different shape** - ignition off, screen off, a
distance travelled - not a shorter timer. Filed on the map under "not yet specified".

### Deliberately not addressed here

The **context-loss defect is separate** and is [ticket 02](02-context-dies-with-the-socket.md).
Kevin reported both in one breath ("conversation drops" and "it doesnt remember the previous turn")
and they are two different bugs. `1e3ee04` says so in its own commit message and fixes only the
first.

## Verification

**This has NOT been re-tested on a drive.** The fix is committed and reasoned; it is not confirmed
on-device.

- [ ] `./gradlew compileDebugKotlin -Pnokey` — `reasoned`, not run as part of this charting.
- [ ] **Drive test:** hold a hands-free conversation through at least three deliberate pauses longer
      than 10 seconds and confirm the mic stays open and the strip does not return to "Tap to talk".
      This is the only check that closes the original report. `on-device`, **outstanding**.
- [ ] **Tap still ends it.** Confirm `onTap` during an active conversation stops it - the indefinite
      wait must not have made the conversation unkillable. `on-device`, **outstanding**.
- [ ] **Speak-only sessions still close.** A proactive line spoken on a warm socket must still park;
      `armIdleTimeout`'s remaining caller is the one path that should still time out. `on-device`,
      **outstanding**.
- [ ] **Watch the billed-session consequence once.** Leave a conversation open and confirm what the
      accepted cost actually looks like in practice before deciding it is acceptable in the long
      run. `on-device`, **outstanding**.

Per L11, these are gates, not notes. This ticket is `resolved` as a **decision and a commit**, and
its on-device verification is openly outstanding rather than assumed.
