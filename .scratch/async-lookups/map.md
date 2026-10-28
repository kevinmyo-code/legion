---
map: async-lookups
title: "Lookups answer while Alfred keeps talking"
charted: 2026-10-28
charted-by: "Kevin + Fable"
effort: "`.scratch/async-lookups/`"
tickets: 7
open: 0
status: open
tags: [map]
---

# Lookups answer while Alfred keeps talking

**Kevin, 2026-10-28:** asked what LEGION could take from Kyutai's MoshiRAG
(`github.com/kyutai-labs/moshi-rag`), then *"make an implementation plan and spin up opus agents
to do it."* MoshiRAG itself is a 24 GB-GPU research model with its own voice; it cannot run on the
phone or beside Gemini Live. Its shape can: retrieval runs in the background while the speech model
keeps the floor, a short holding line covers the wait, the result is fed in when it lands, and a
retrieval that takes over 3 s is treated as a quality problem.

## Destination

Phone only (`app/`). The head unit stays on `gemini-3.1-flash-live-preview`, which has no
asynchronous function calling, and is out of scope here.

Kevin asks "any trouble codes?". Alfred says one holding line, the lookup runs, and the answer
arrives in Alfred's voice without Kevin speaking again. Nothing is stated before the result exists.
A write tool still blocks, exactly as the 2026-09-27 ruling says. If a sub-agent model is rate
limited or overloaded, the lookup retries once on a fallback model instead of failing. A logcat
capture says how long each stage of a lookup took.

## What changes, in one table

| MoshiRAG | LEGION today (dev `e838ccb2`) | After this map |
|---|---|---|
| Retrieval is asynchronous; the voice keeps going | Every declaration stamped `behavior: BLOCKING` (`GeminiLiveSession.withBlockingBehavior`) | Read-only lookups opt in to `NON_BLOCKING`; result sent with `scheduling: WHEN_IDLE`; writes stay `BLOCKING` |
| Pre-RAG filler covers the wait | Prompt asks for "One moment." before `ask_` tools | Same line, plus: say nothing factual until the result arrives |
| A fallback retrieval backend | One Flash-Lite model; 429/503 is a spoken failure | One retry on `gemini-3.8-flash` for RateLimited / Overloaded |
| Over 3 s of retrieval hurts | Router budget 42 s, specialist 32 s, no per-stage timing | Per-stage `LIVE_LATENCY` lines, p50/p95 from a real capture. Timeouts unchanged until measured |

## Tickets

- 01 decision (resolved 2026-10-28): read-only lookups may be non-blocking; writes stay blocking. ADR 0065
- 02 build: non-blocking read tools on the Live socket, holding-line prompt rule, setting, tests
- 03 build: sub-agent fallback model and per-stage latency probes
- 04 test: the A25 run that proves `WHEN_IDLE` speaks the result on `gemini-3.8-live`, and the p50/p95 report
- 05 build: conversation audit log, `LIVE_AUDIT` lines with what was said and each tool's args and result, read by `tools/voice_audit.py` (Kevin, 2026-10-28)
- 06 build: a livelier register for Alfred (quick, dry, quietly amused; the manner, not the lines), holding lines varied (Kevin, 2026-10-28)
- 07 build: ticket 04 run 3 follow-ups: a still-checking nudge at 4 s and 12 s (Kevin, 2026-10-28), a nudge for a holding line with nothing behind it and a 20 s answer hold on the follow-up window, sign-off, writes only when asked, time-varying figures only from a result, meal dates, `voice_audit.py` fixes

## The gate

`scheduling: WHEN_IDLE` on `gemini-3.8-live` is unverified. Google's Live tools page (read
2026-10-28) says asynchronous calls are not supported on 3.1 Flash Live and lists only 2.5 as
supporting them; it does not mention 3.8. The repo's own evidence that 3.8 runs non-blocking is the
2026-09-27 commit `29057645`, where 3.8-live kept talking past a tool call until every declaration
was stamped BLOCKING. Whether a `WHEN_IDLE` result makes the model come back and speak unprompted
is ticket 04's first question. If it behaves like `SILENT`, the setting ships OFF and the ADR
records why.
