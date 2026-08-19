---
map: drive-test-2026-08-18
ticket: 02
title: "The context dies with the socket, and nothing says so"
type: task
status: resolved
status-detail: "built 0e3319b - resumption, goAway and compression all landed; neither the goAway margin nor a real resume has been observed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The context dies with the socket, and nothing says so

## Question

Kevin, on a real drive, 2026-08-18: *"after a few turns, it doesnt remember the previous turn what
we said. the context drops"*

**This is not [ticket 01](01-conversation-waits-for-the-driver.md).** Kevin reported both in one
breath and they are separate defects. Ticket 01 was a timer parking the socket; this one is what
happens to the conversation when a socket goes away for any reason at all.

### Root cause, traced 2026-08-18

**Gemini Live keeps conversation history in the SOCKET.** There is no client-side transcript feeding
the model. So the socket is the memory, and everything that ends a socket ends the conversation's
memory with it.

Nothing in the app resists that:

| Mechanism | State |
|---|---|
| `goAway` (the server's own warning that it is about to cut the session) | **Explicitly ignored.** `service/GeminiLiveSession.kt:779`: `// goAway / sessionResumptionUpdate are ignored in this version.` |
| `sessionResumptionUpdate` (the handle that would let a new socket resume the old session) | **Explicitly ignored**, same line. |
| `contextWindowCompression` in `buildSetup` | **Not requested.** |
| A session-resumption handle in `buildSetup` | **Not requested.** |

On any socket death, `LiveSessionController` nulls the session and re-prewarms a brand-new socket
carrying only `brain.buildBaseInstruction()`. No prior turns. No `buildLiveContext()` either - that
is injected into the **greeting** (`LiveSessionController.kt:53`, `:381`), and the resume path has no
greeting.

### The part that makes it a trust bug rather than a capacity bug

The next tap goes through `resumeWarm` (`LiveSessionController.kt:323`), which calls
`beginConversation(null)` (`:331`): **no greeting, no context, the mic just opens.**

So the assistant answers cold, and **neither it nor the driver is told the thread was lost.** From
the driver's seat it is indistinguishable from an assistant that simply stopped paying attention -
which is exactly how Kevin reported it.

## Decide

Three candidate fixes. **These are candidates to choose between, not a plan.** They are not mutually
exclusive and the right answer may be two of them.

1. **Handle `goAway` and renew before the server cuts the session.** The server tells us in advance;
   we throw the message away. Handling it turns an abrupt loss into a planned handover. Ask what the
   actual warning window is and whether it is long enough to do anything with mid-conversation.
2. **Request `contextWindowCompression` in `buildSetup`.** Addresses the case where the session ends
   because the context window filled, which is a different ender from a network drop. Ask which
   ender Kevin actually hit before assuming this is the fix.
3. **Replay a summarised transcript into a fresh socket's opening turn.** The only option that
   survives an ender we did not see coming, and the only one that works when the socket dies without
   warning. Costs tokens on every resume and risks the model treating replayed text as new driver
   speech.

**On option 3's source:** `captureEpisodicTurn` already exists in `GeminiLiveSession`
(`:821`, called from `:951`) and **may** be a usable source for that replay. Whoever builds this must
**check what it actually stores** - the format, the retention, and whether it drops turns (`:1021`
and `:1862` both describe skip conditions). Do not assume it holds a usable transcript.

## Binding regardless of which is chosen

**A driver must be TOLD when the thread was lost, rather than being answered cold.**

This is the same honesty rule that [ticket 04](04-what-the-assistant-says-when-it-cannot.md) turns
on, arriving from a different direction: an assistant that has forgotten the conversation and does
not say so is asserting a continuity it does not have. Silence here is the same failure as claiming
an action it did not take.

The `Closed` branch in `LiveSessionController` already flashes a notice and the `Idle` branch did not
- that asymmetry is what made [ticket 01](01-conversation-waits-for-the-driver.md) silent too. The
notice is cheap and the codebase already has the mechanism.

## Verification

- [ ] Reproduce the loss deliberately: kill the network mid-conversation, restore it, tap, and
      confirm the assistant no longer recalls the previous turn. Establishes the baseline before any
      fix. `on-device`.
- [ ] Confirm which ender Kevin actually hit on the drive - `goAway`, a network drop, or a context
      window - before choosing between the three options. Logs from the drive if they exist,
      otherwise a reproduction. `on-device`.
- [ ] After the fix: same reproduction, and the driver is told, in words, that the thread was lost.
- [ ] Confirm a resumed conversation does not replay stale context into an unrelated later
      conversation.

## RESOLVED 2026-08-19 - all three mechanisms, not a choice between them

Built in `0e3319b`. The ticket framed three candidates to choose between; the right answer was two
of them plus the honesty half, and the third for a different failure entirely.

| Mechanism | State |
|---|---|
| `sessionResumption` | **Built.** Handle captured from `sessionResumptionUpdate` and kept ON THE CONTROLLER, which is the whole point - it has to outlive the session object it came from. Sent back in `buildSetup` on the replacement socket. |
| `goAway` | **Built.** `timeLeft` parsed, and the session closes DELIBERATELY a margin early so the cutoff is a planned handover rather than a surprise. `"goAway"` joined `NORMAL_CLOSE_REASONS` so our own planned close never flashes CONNECTION LOST. |
| `contextWindowCompression` | **Built**, with a comment saying plainly it addresses the window filling, NOT a network drop. Kept visible so nobody later mistakes it for the fix. |
| Transcript replay | **Rejected.** Turn-boundary text is not continuable session state, and replaying it risks the model reading old text as new driver speech. |

Protocol fields were **fetched from `ai.google.dev/api/live` and grepped**, not recalled:
`sessionResumption.handle`, `sessionResumptionUpdate.newHandle`/`resumable`, `goAway.timeLeft`,
`contextWindowCompression.slidingWindow`. `traced`.

### The honesty half, which was the actual defect

A resume that works says nothing - continuity is the expected case. A thread that could NOT be
resumed now flashes **"RECONNECTED - LOST TRACK OF WHAT WE WERE SAYING"** and opens the next turn by
telling the MODEL it does not remember, rather than `beginConversation(null)` answering cold. A
crisis teardown clears the handle as well: that conversation must never silently resume.

### A correction worth keeping

The brief for this work justified skipping replay on the grounds that `captureEpisodicTurn` stores
driver turns only, because `companionTurnText` is gated on `subtitles`. **The executing agent
checked and corrected that**: `subtitles = true` is hardcoded now (`GeminiLiveSession.kt:492`, "a
core UX surface, not debug-only"), so companion text IS captured. The stale premise was reported
rather than used. Replay stays rejected on the better reason.

### Unproven, and neither is small

- **No real `goAway.timeLeft` has ever been observed**, so the 3s reconnect margin is a guess. The
  code says so.
- **The protocol gives no ack for a rejected or expired handle.** "A handle exists, therefore the
  resume worked" is the best available signal, not a confirmation. If a stale handle silently fails,
  today's code would stay quiet - which is exactly the failure this ticket was about, one level down.

### Verification, not yet run

Reproducible without a car, and deliberately left for Kevin because it drops the adb link the work
was done over:

```
adb shell cmd connectivity airplane-mode enable    # wait 5-10s mid-conversation
adb shell cmd connectivity airplane-mode disable
```

then tap and confirm either a silent resume with correct recall, or the notice plus a model line
that acknowledges the drop.

