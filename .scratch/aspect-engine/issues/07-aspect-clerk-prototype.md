---
map: aspect-engine
ticket: "07"
title: "The aspect clerk: prototype and latency"
type: prototype
status: claimed
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-meta-tool-surface]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# The aspect clerk: prototype and latency

## Question

Charter decision 8: the clerk is an executor SubAgent, not a router. The live model hands it a
natural-language instruction ("log today's workout, bench 3x5 at 185"); it runs a bounded
meta-tool loop and returns what it actually wrote. Prototype against the real `ai/SubAgent.kt`
investigate pattern and answer with numbers, not vibes:

1. **Latency.** Flash vs Pro, on a representative 3-5 step CRUD instruction, measured on Kevin's
   key. The live session is voice; how long a clerk round-trip is tolerable before the assistant
   must say "working on it"?
2. **Reliability.** Does Flash follow describe-then-write, or does it hallucinate field names?
   Quantify over a handful of instructions against a seeded store.
3. **Honesty plumbing.** The clerk's result format: rows written, rows failed, in words - so the
   live model's outcome verbs stand on a real result (sec 7).
4. **When NOT to clerk.** Single-field one-shot writes are probably faster direct; confirm and
   write the routing guidance into the meta-tool descriptions.

Prototype code is throwaway; the answer is numbers plus the clerk's contract.
