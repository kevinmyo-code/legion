---
map: wake-word
ticket: "05"
title: "Who owns the microphone, and what yields to what?"
type: grilling
status: built
status-detail: "2026-08-21 - built; owes a run on the phone"
blockers: ["01"]
blocked-by: ["[[01-mic-under-doze]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Who owns the microphone, and what yields to what?

## Question

There is one microphone and at least four things that want it. **This is the ticket the ambient
listening map is waiting on** - it inherits whatever is decided here rather than deciding it twice.

The contenders:

| Claimant | Today |
|---|---|
| `WakeWordEngine` | Holds a Vosk `SpeechService` continuously while enabled |
| `GeminiLiveSession` | Takes the mic for the duration of a live turn |
| `AmbientListener` | Mutually exclusive with wake word **by an explicit guard in both `start()` methods** |
| A phone call | `service/TelephonyController.kt` is already aware of call state |
| Spotify / any recorder | Unmodelled |

The questions:

1. **When the live session opens, does the wake word release the mic or keep it?** Keeping it risks
   the engine hearing the assistant's own output; releasing it risks a gap where "hey <name>" does
   nothing and the driver does not know why.
2. **After a phone call ends, who restarts?** Nothing currently reconciles this, and
   `AssistantIgnition.resumeIfEnabled` exists because exactly this class of gap was found in
   production.
3. **Is the existing wake-word / ambient mutual exclusion the right rule**, or should ambient
   listening subsume the wake word entirely, since an open transcript already contains "hey <name>"?
   `AmbientListener`'s KDoc argues the latter. That argument was made for a head unit.
4. **What does the driver see when the mic is not available?** Silence is the failure mode this
   codebase keeps rediscovering.

Ticket [Does a foreground service still get the microphone with the screen off?](01-mic-under-doze.md)
must land first: several of these have platform-level answers that are not Kevin's to choose.

## Resolution - 2026-08-21 (Kevin)

**One arbiter object owns the microphone. Priority: a live turn beats ring listening beats the wake
word.**

### Why an object rather than written-down rules

Today three subsystems grab the mic independently - `WakeWordEngine`, `GeminiLiveSession`, and the
ring-listening window added the same day - and the only coordination between any of them is a
boolean.

**That boolean got stuck, and it cost a real bug.** `duckNow()`'s `ducked` flag stayed true after
Android took audio focus away, so every later turn short-circuited and the assistant spoke
inaudibly for the rest of the session while subtitles kept rendering
(`.scratch/proactive-mode/issues/13-silent-after-focus-loss.md`). Written-down precedence enforced
at each call site is the same shape that just failed: **N places that must each remember.**

An arbiter makes the question structural. It is the only thing that can answer "who has the mic
right now" at all, which today nothing can.

### The priority, and why this order

| Rank | Claimant | Why |
|---|---|---|
| 1 | **A live turn** | The only one where Kevin is mid-sentence. Cutting him off is the worst outcome available |
| 2 | **Ring listening** | Seconds long, with an action attached ("answer it") |
| 3 | **Wake word** | Yields to both, resumes after |

Ring-listening-over-live-turn was rejected deliberately: the ring build already refuses to tear down
a running conversation to announce a call, on the grounds that it would take the microphone from
someone mid-sentence to tell them something they can already hear. Inverting that here would
contradict a decision made hours earlier.

### This is a refactor, and it should be sequenced honestly

**[Ticket 13](13-weak-pickup-on-a-drive.md) has a symptom and no cause**, and mic contention is one
of its four candidates. The arbiter is worth building regardless - the focus bug alone justifies it -
but **do not claim it fixed the weak wake word** unless a log pull shows contention was implicated.
An output fix was already nearly credited with an input improvement once today.

## Built - 2026-08-21

`service/MicArbiter.kt` plus 13 tests covering **every claimant against every holder**, preemption,
refusal, release, and re-acquisition.

### The design decision worth keeping

`Claimant` is declared in priority order and **`ordinal` doubles as priority**, so the rule is one
line: a request wins if it outranks the holder, or IS the holder. There is a test asserting the
declaration order specifically, because reordering the enum would silently change behaviour
everywhere with nothing else to catch it.

The preemption listener fires **outside the lock**, with the reason in the code: a listener that
called back into `request`/`release` while the lock was held would deadlock.

### Two tests that encode decisions rather than mechanics

- **Ring listening never interrupts a live turn.** The ring build already refuses to tear down a
  running conversation to announce a call - taking the mic from someone mid-sentence to tell them
  something they can already hear. Inverting it here would have contradicted a decision made hours
  earlier.
- **A preempted claimant releasing later does not evict its successor.** This is the exact
  stale-state shape the whole object exists to prevent: the wake word is preempted, its own teardown
  calls `release()`, and if that cleared the holder then the live turn would silently lose a mic it
  legitimately took. That is the `ducked` bug wearing different clothes.

### Not claimed: this does not fix the weak wake word

[Ticket 13](13-weak-pickup-on-a-drive.md) still has a symptom and no diagnosis. Mic contention is one
of four candidates and nothing here establishes it was implicated. An output fix was nearly credited
with an input improvement once already today; this is not a repeat of that.

### One thing this build forced, worth knowing

Every `MicArbiter` test failed on `android.util.Log.d` not being mocked - not on anything it
asserted. `unitTests.isReturnDefaultValues = true` is now set, with the trade written into
`build.gradle.kts`: without it, **any class that logs is untestable in a plain JVM test**, and a rule
of "do not log in code you want to test" would push logging out of exactly the code most worth
explaining.

### Owed on the phone

The arbiter's logic is pure and tested; what is not tested is that the three claimants actually
CALL it correctly under real contention - a ringing phone during a live turn, the wake word
resuming after a conversation ends.
