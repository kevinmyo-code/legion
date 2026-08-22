---
map: hands-and-senses
ticket: 25
title: "BUILD: where is my package, when is my flight"
type: build
status: built
status-detail: "Built. Owes the on-phone run: ask both questions, confirm the answer names the mail and says estimate, confirm nothing lands in memory."
blockers: ["18"]
blocked-by: ["[[18-inbox-intelligence]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# BUILD: where is my package, when is my flight

Every decision is settled in [[18-inbox-intelligence]]. **Read its resolution table before writing
anything** - this ticket exists so that resolving 18 did not make a fully-decided, entirely unbuilt
feature disappear from the board.

## What to build

Two answers over mail LEGION can already read. `gmail.readonly` is granted and `GmailToolLogic`
passes a `q` query through unchanged, so this needs **no new auth, no new storage, no new
dependency**.

- **"Where's my package?"** Carrier and tracking number live in shipping mail. Report what the mail
  itself says: last update, delivery estimate.
- **"When's my flight?"** Airline and hotel confirmations carry dates, times, confirmation numbers,
  addresses.

## The four rules that shape it, from 18's resolution

1. **Mail only.** No carrier API, no aggregator, no new key. Stale-but-honest is the chosen trade,
   so **the staleness must be audible**: what the mail said and when the mail was sent.
2. **Every answer names its source.** *"Your United confirmation from Tuesday says 6:15am."*
   Mandatory, not a nicety. This extraction has no printed total to reconcile against, so naming the
   mail is the only thing that lets Kevin check it. Without it, the estimate label is decoration.
3. **Estimate, never fact** (§4 rule 5). The tool description says so and the spoken line says so.
   A wrong flight time is a missed flight.
4. **Read-through, absolutely.** Nothing stored: no Room row, no `CompanionMemory`, no
   `EpisodicTurn`, not even a summary. Join `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` so the existing
   machinery covers it - do NOT invent a second notion of read-through.

## The calendar boundary, and it is a correctness rule

**The calendar wins wherever it has the event.** Airlines already push flights into Google Calendar
and `read_calendar` answers from it deterministically, with no extraction and no estimate. Mail fills
the gaps: packages, which never reach a calendar, and trips never added to one.

Two paths answering one question at different confidence is how a deterministic answer gets
overwritten by a guessed one. The tool descriptions must make the boundary unmissable to the model.

## Verification

- Suite green **both** ways: `./gradlew testDebugUnitTest` and `testDebugUnitTest -Pnokey`.
- `python tools/voice_guide.py` exits 0 with user-facing copy for every new tool.
- A test that the new tools are in `EPISODIC_EXCLUDED_TOOLS` and that a turn touching one stores the
  tool NAME and not its content - the audit trail's existing redaction test is the model.
- A failure result that says in words what did NOT happen (no mail found, no permission, offline).
- On the phone: ask both questions, confirm the answer names the mail it read and says it is an
  estimate, and confirm nothing lands in memory afterwards.
