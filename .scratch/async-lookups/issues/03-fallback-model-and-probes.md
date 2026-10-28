---
map: async-lookups
ticket: "03"
title: "Build: sub-agent fallback model and per-stage latency probes"
type: build
status: open
status-detail: "Unstarted. Branch feat/subagent-fallback"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: sub-agent fallback model and per-stage latency probes

Phone only. Branch `feat/subagent-fallback`, off `feat/async-read-tools` at the map commit (so the
two branches share this ticket file and merge cleanly). Touches nothing ticket 02 touches.

## Files

- `app/src/main/java/com/kevin/legion/ai/SubAgent.kt`: `route`, `investigate`, `askTyped`, `ask`,
  `postRaw`, `classify`, `DEFAULT_MODEL`.
- `app/src/main/java/com/kevin/legion/ai/GeminiRest.kt`: `post`, `classify`, `RestOutcome`.
- `app/src/main/java/com/kevin/legion/service/LiveLatency.kt` and the `LIVE_LATENCY` call sites
  in `LiveToolbox.kt`'s dispatch path (`SubAgent.route` callers around line 2660-2730, and the
  per-call model log near 6424).
- `tools/voice_latency.py` (stdlib only, doctest is its self-test).
- Tests: whatever covers `SubAgent` / `GeminiRest` today (find them), plus new ones.

## Part A: fallback model

1. `SubAgent` gets `FALLBACK_MODEL = "gemini-3.8-flash"` beside `DEFAULT_MODEL`
   (`gemini-3.5-flash-lite`). The voice-note model already uses 3.8-flash, so it is a known id.
2. When a POST classifies as `RateLimited` (429) or `Overloaded` (500/503), retry that one POST
   once on the fallback model, same body. Never on `KeyInvalid`, never on a blocked prompt, never a
   second fallback. `Offline` keeps `GeminiRest`'s existing one retry and does not escalate.
3. The fallback applies to every SubAgent entry point that goes through `postRaw` (`ask`,
   `askTyped`, `investigate`, `route`). Inside `investigate`'s loop, a fallback round continues the
   loop on the fallback model for the rest of that investigation (do not flip back mid-loop; the
   `thoughtSignature` echo may not transfer).
4. Log one line per fallback: `LIVE_LATENCY subagent_fallback from=<model> to=<model> reason=<RateLimited|Overloaded> tool=<name if known>`,
   and the existing per-call model log must name the model that actually answered.
5. Cost note in the doc comment: Flash-Lite is the default for cost; the fallback fires only on a
   failure that would otherwise be spoken as one.
6. Tests: a fake `GeminiRest` seam (or whatever the existing tests use) proving: 429 then 200 on
   fallback returns Success and logs; 429 then 429 returns RateLimited; 401 never falls back; a
   Network failure never changes model.

## Part B: per-stage probes

7. Each dispatcher that calls `SubAgent.route` logs, on completion:
   `LIVE_LATENCY route_ms=<total> route_post_ms=<router POST> route_tools_ms=<fan-out wall time> route_tool_count=<n> route_tools=<a,b> tool=<ask_fleet> model=<id> result=<Success|Failed|...>`.
   `investigate` callers log `investigate_ms=<total> rounds=<n> tool=<name> model=<id> result=<...>`.
   Timing comes from `SubAgent` itself (it owns the stages); expose it on `AgentResult` or through
   a small timing callback, whichever keeps `AgentResult`'s sealed shape intact.
8. `tools/voice_latency.py`: parse the new lines; add a "Lookups" table with p50/p95 per tool for
   total, router POST and fan-out, plus a count of fallbacks. Accept `async=true` on `tool_ms`
   lines (ticket 02 adds it) without failing. Extend the doctest sample. Update the docstring.
9. Do not change any timeout. Ticket 04 reads the numbers first.

## Verification

- `./gradlew compileDebugKotlin -Pnokey` green.
- `./gradlew testDebugUnitTest`: totals from JUnit XML, 0 failures.
- `./gradlew detekt`: no new issues.
- `python -m doctest tools/voice_latency.py` passes.
- Report lists each line format added, verbatim, so ticket 04 knows what to grep.
