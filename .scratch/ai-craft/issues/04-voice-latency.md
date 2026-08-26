---
map: ai-craft
ticket: "04"
title: "Why the voice feels slower than it should: duplex, and 104 tools every turn"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Why the voice feels slower than it should: duplex, and 104 tools every turn

## Question

Kevin, 2026-08-26, after using the voice agent in `bilawalsidhu/gods-eye-view`: *"his voice agent
seemed faster and smoother than my gemini live."* That project uses the OpenAI Realtime API.

The comparison is not apples to apples (a desktop browser on wifi against a phone running a
foreground service, a wake word engine and an OBD stack), so this ticket is not "switch to OpenAI".
It is: **which of the differences are real, which are ours, and which are worth fixing.**

Three candidate causes, in the order I would expect them to matter. Each needs a verdict.

### 1. Half-duplex against full-duplex. Probably the whole feeling. (`traced`)

CLAUDE.md §3 pins the voice stack as "Gemini Live WebSocket STS, **server VAD, half-duplex**".
OpenAI Realtime is full-duplex and supports barge-in: you talk over it and it stops.

Half-duplex means you WAIT for the assistant to finish before it will hear you. That is not
latency in the measurable sense at all, and it is very likely most of what Kevin felt. A system you
cannot interrupt feels slow even when its first token is fast.

**Decide:** does the Gemini Live API support barge-in / full-duplex in the shape LEGION needs, and
at what cost to `service/GeminiLiveSession.kt` and `LiveSessionController`? This is a research
question with a real answer, not a preference. **Do not guess it** - the half-duplex choice is
recorded in CLAUDE.md as a fact about the stack, and nobody has re-checked whether it is still a
constraint or now a habit.

### 2. 104 tool declarations on every single turn. (`measured`)

`service/LiveToolbox.kt` is **7,130 lines** and declares **104 tools**, counted directly. The model
reads those descriptions on every turn - CLAUDE.md §1 says exactly this in its own correction note,
which is how the 281 "driver" literals were found in the first place ("~149 of them in non-fleet
tool descriptions, which the model reads on EVERY turn").

That is a constant tax on time-to-first-token and on cost, paid on every utterance including
"what's the weather".

**Decide:**
- Which tools can be **tiered by context** rather than always sent. The precedent already exists
  and is Kevin's own: car context is injected only when the OBD dongle is connected, because that
  is the one signal that says he is IN a car. The same argument applies to the fleet TOOLS, not
  just the fleet context.
- What `docs/architecture/tool-inventory-2026-08-23.md` already settles: it says **33 die at
  cutover, 48 survive, 23 need a call**. Those 33 may already be removable. Start there before
  designing anything.
- Whether tool descriptions can be shortened without losing the caveats that make them safe. Many
  are long precisely because they encode "never say X unless Y" rules that exist for good reasons
  (§7's outcome-verb discipline). **Shortening them is not free** and must not quietly delete a
  guardrail to save tokens.

### 3. Environment and reliability, which is not latency but reads as it. (`traced`)

MEMORY and ADR 0035 both list what has actually been observed on the phone: the wake word not
firing, the microphone opening deaf, a closed socket, no key. Those do not add milliseconds, they
add **uncertainty**, and a system you are unsure heard you feels slower than one that answers late
but predictably.

**Decide:** is any of this worth measuring before it is worth fixing? A first-token time and a
turn-completion time, logged through `MidnightEvents`, would turn this whole ticket from
impressions into numbers. **Recommend doing that first** - it is cheap, and without it every change
below is judged on feel, which is the weakest possible evidence and exactly what this ticket is
trying to escape.

## Prior art already in the repo, read before starting

- `.scratch/hands-and-senses/issues/06-*.md` resolved that **session compression and resumption are
  mandatory** for Gemini Live cost and latency. Check whether that landed or is still owed.
- `docs/architecture/tool-inventory-2026-08-23.md` - the 104-tool census.
- `ai/AriaBrain.kt`'s `SHARED_INSTRUCTIONS` - the other thing sent every turn.

## Explicitly not in scope

Switching model providers. LEGION is Gemini Live on a BYO key direct to Google (CLAUDE.md §3), and
OpenAI Realtime would mean a second BYO key, a different billing story and a rewrite of the session
layer. If the duplex answer in item 1 turns out to be "Gemini cannot do it", that is a finding worth
writing down, not an automatic licence to switch.
