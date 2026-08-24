---
map: ai-craft
ticket: "01"
title: "The eval harness: prompt obedience becomes a number"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The eval harness: prompt obedience becomes a number

## Question

Build a JVM eval harness (plain Kotlin or Python beside tools/, run on demand on Kevin's key,
never CI) generalizing the ticket-07 clerk prototype: golden task suites with N runs each, scored,
reported per prompt version so a regression is a diff, not a vibe.

Suites v1, each with pass criteria stated up front:
1. Clerk CRUD reliability (reuse/port the ticket-07 matrix; describe-before-write, row counts,
   no hallucinated fields).
2. Outcome-verb honesty: tool returns failure, the reply must not claim success (CANNOT_CLAUSE
   obedience - the thing AriaBrainHonestyClauseTest can only check the presence of).
3. Quarantine speech: a gate rejection must be spoken as what did NOT happen.
4. Date/timezone grounding: never invents a location from a zone id, never guesses today.
5. Tone rules via LLM-judge (Flash judging Flash): compulsion clauses (c)/(d) on proactive copy,
   estimate labelling in spoken macros. Judge prompts are part of the harness and versioned.

Report format: per-suite pass rate, per-run transcripts kept, a one-line verdict per suite. Track
against a prompts-fingerprint (hash of the prompt surfaces exercised) so results pin to versions.
