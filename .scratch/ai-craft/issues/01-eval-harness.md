---
map: ai-craft
ticket: "01"
title: "The eval harness: prompt obedience becomes a number"
type: task
status: resolved
status-detail: "Resolved 2026-08-24. Built and run for real the same night: clerk/honesty/quarantine/grounding all 100 percent at N=1; tone_judge 73 percent WEAK on judge disagreement over clause (d) - a finding, flagged to Kevin, not a harness bug."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer

Built at tools/evals/ (Python, stdlib-only, on-demand on Kevin's key, never CI) and run for real
2026-08-24. First report: clerk_crud, outcome_honesty, quarantine_speech, grounding all 100 percent
(N=1); tone_judge 73 percent WEAK - the judge flagged clause (d) on three absence-referencing
samples, a genuine ambiguity the disagreement-reporting design exists to surface, left unaveraged
per the ticket. Reports pin to a prompts fingerprint over AriaBrain/Personas/EngineToolbox.
Notable: Gemini rejects fabricated functionCall turns (missing thought_signature), so the honesty
suites let the model make its own real tool call and feed back a canned failure - recorded as a
SKILL for every future fixture. Tone ground-truth labels are the builder's reading of the
compulsion test, not Kevin's - the (c)/(d) sample set is his to bless when he cares to.
