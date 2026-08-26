---
map: generated-ui
ticket: "04"
title: "Amend ADR 0035: where a hands path is allowed to live"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Amend ADR 0035: where a hands path is allowed to live

## Question

[[0035-every-voice-capability-has-a-hands-path]] says anything LEGION can do by voice must be doable
by hand. This map makes the phone voice-first, so the amendment has to be written rather than
assumed.

**The proposed amendment, for Kevin to rule on:** the hands path may live on ANOTHER SURFACE (the
PC app), EXCEPT where the capability is time-critical and phone-local, in which case the phone must
carry its own.

The exception is not theoretical. The ADR's own worst case is `answer_call`: the call arrives, the
assistant mishears "answer it", and there is no button. A PC in another room does not answer that
call. The same holds for anything done while driving, and for anything where the phone is simply
the only device present.

Decide:

1. Is the surface-may-differ amendment accepted?
2. What defines "time-critical and phone-local"? Recommend a test rather than a list, since a list
   goes stale: **if the capability's value expires before you could reach another device, it needs
   a phone-side hands path.**
3. What happens to the existing per-aspect screens (`ui/LedgerScreen.kt`, `FleetScreen.kt`,
   `TodayScreen.kt` and the rest, 121 files in `ui/`)? Recommend keeping them as the on-device
   fallback: they already exist, they cost nothing to keep, and they make the amendment safe by
   giving the phone a hands path for everything rather than only for the time-critical set.
4. The ADR's second reason survives every reliability fix and must be answered separately:
   **a voice-only capability is invisible.** Generated UI shows you the answer to a question you
   already knew to ask; it does not help you discover the question. Is `docs/voice.html` plus the
   PC surface the answer to discoverability, or does the phone owe something too?

## Note

Whatever is ruled here, both ADR 0035 and CLAUDE.md §7's feature-add checklist item need editing to
match. A checklist that still says "a capability reachable only by voice is not finished" while the
product ships exactly that is the doc-is-a-bug case §13 names.
