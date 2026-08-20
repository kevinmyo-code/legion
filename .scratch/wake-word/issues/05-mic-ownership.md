---
map: wake-word
ticket: "05"
title: "Who owns the microphone, and what yields to what?"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mic-under-doze]]"]
open-blockers: 1
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
