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

## Findings

Prototype: `.scratch/aspect-engine/research/clerk-prototype/clerk_prototype.py`, run against the
real `generateContent` REST endpoint on Kevin's key from `local.properties`. Fake in-memory store
seeded with two aspects (`workouts`: exercise/weight/reps/date; `groceries`: item/quantity/
category/bought). Six CRUD meta-tools from ticket 06 (`list_aspects`, `describe_aspect`,
`query_records`, `create_record`, `update_record`, `delete_record` - `aspect_clerk` itself and the
schema pair `create_aspect`/`update_aspect` are out of scope, per ticket 06's answer that schema
edits route through a separate Pro-tier generator subagent). Loop shape mirrors
`ai/SubAgent.kt`'s `investigate()`: bounded rounds (`maxModelCalls = 4`), tool results echoed
back, forced tool-free answer on round 5 if the model hasn't finished. 5 instructions x 2 models x
3 runs = 30 loop-runs, 114 total `generateContent` POSTs. Raw output:
`research/clerk-prototype/results.json`, `research/clerk-prototype/run_output.txt`.

**Model choice, corrected from the ticket's wording.** Neither `gemini-2.5-flash`/`gemini-2.5-pro`
nor a hypothetical `gemini-3.5-pro` exist against this key - a live `ListModels` call (run as part
of this prototype) 404s on `gemini-3.5-pro`. `ai/SubAgent.kt`'s actual production model is
`gemini-3.5-flash-lite`; the 3.5 generation ships no Pro tier at all. The nearest real Pro-tier
model in the same 3.x line is `gemini-3.1-pro-preview`. Flash below is `gemini-3.5-flash-lite`
(production); Pro is `gemini-3.1-pro-preview`.

### 1. Latency (median of 3 runs, seconds, wall-clock including all rounds)

| Instruction | Flash (3.5-flash-lite) | Pro (3.1-pro-preview) | Pro / Flash |
|---|---:|---:|---:|
| single create ("log bench 185x5 today") | 2.21 | 9.26 | 4.2x |
| multi-record create ("bench 3x5 at 185" -> 3 rows) | 2.26 | 9.21 | 4.1x |
| query-then-update ("change today's bench to 190") | 2.13 | 12.70 | 6.0x |
| delete ("delete today's bench entry") | 2.51 | 14.60 | 5.8x |
| ambiguous ("log my workout") | 1.12 | 4.38 | 3.9x |

Flash lands every case in 1.1-2.5s at `maxModelCalls = 4`. Pro is consistently 4-6x slower and
crosses 10s on anything requiring a `query_records` round-trip (update, delete) - on a live voice
turn that is well past the point the assistant must say "working on it" before it can speak a
result. **Flash is fast enough to hold in a single conversational turn; Pro is not**, on this
loop shape, at this maxModelCalls, on Kevin's key, right now (`tested`).

### 2. Reliability (3 runs/cell, seeded store)

| Instruction | Model | describe before write | hallucinated field | rows written | outcome |
|---|---|---|---|---|---|
| single create | Flash | 3/3 yes | 0/3 | 3/3 correct (1) | clean, every run |
| single create | Pro | 3/3 yes | 0/3 | 3/3 correct (1) | 2/3 duplicated the sentence in the final text |
| multi-record create | Flash | 3/3 yes | 0/3 | 3/3 correct (3 rows, not 1 collapsed row) | clean |
| multi-record create | Pro | 3/3 yes | 0/3 | 3/3 correct (3) | 2/3 leaked planning text into the final answer |
| query-then-update | Flash | n/a (no writes) | 0/3 | **0/3 - all 3 refused the update** | see below |
| query-then-update | Pro | 3/3 yes | 0/3 | 3/3 correct | clean |
| delete | Flash | 3/3 yes | 0/3 | 3/3 correct | clean |
| delete | Pro | 3/3 yes | 0/3 | 3/3 correct | clean |
| ambiguous | Flash | n/a | 0/3 | 0/3 (correctly asked to clarify, never guessed) | clean |
| ambiguous | Pro | n/a | 0/3 | 0/3 (correctly asked to clarify, never guessed) | clean |

**No field-name hallucination from either model in 30 runs**, and both always call
`describe_aspect` before a first write to an aspect in a conversation - the system-prompt rule
held. Neither model ever fabricated a value for the ambiguous case; both asked instead of
guessing.

**The one real failure is a harness gap, not a model defect, and it is itself a finding.** Flash
failed `query_then_update` in all 3 runs - but not by hallucinating a field or a row. The
instruction says "today's bench press" and this prototype (unlike the live session) never told
the clerk what today's date actually is. Flash guessed a "today" on its own - **a different,
wrong date each run** (`2025-02-17`, then `2025-05-18` across runs, both nowhere near the seeded
`2026-08-23`) - filtered `query_records` by that guessed date, found nothing, and confidently
reported "I could not find a bench press entry for today... to update." Pro happened to succeed
on the same instruction, but by a different route: its `query_records` calls came back unfiltered
or loosely filtered enough to surface the one seeded row regardless of the date it guessed. This
is the same class of bug CLAUDE.md already bans for the live model (never hand it a raw IANA
timezone id and let it guess) applied one layer down: **the clerk must be handed the real current
date/time in its context the same way any other time-aware tool is, or "today" becomes a
hallucinated fact that produces a wrong but fully-stated outcome** (`reasoned`, not yet fixed -
this prototype does not correct it, it only surfaces it as an owed piece of the real clerk's
contract).

### 3. Honesty plumbing (outcome stated in words)

All 30/30 final answers stated a row count in words per the system prompt's contract ("Wrote N
rows... M failed" or an explicit clarifying question), matching one measured miss: one Flash
`query_then_update` run hit the forced tool-free round with an empty final answer (`calls=5`,
`final_text=""`) - the loop's hard backstop fired with nothing to say, which the real clerk must
treat as a failure result, never as silent success (`tested`, see `results.json`, that one cell).

**Pro's text is not TTS-clean.** In 4/9 non-trivial Pro cases (single create, multi-create,
query-then-update) the final answer's text visibly leaked reasoning/self-correction - `"Wait, no.
I must state..."`, a sentence written twice, or `toolConfig.mode = NONE`-forced text still doing
mid-sentence backtracking. This REST call requests no `thinkingConfig` separation, so Pro's
answer text is not safe to hand straight to a spoken persona without a cleanup pass; Flash never
exhibited this in 15/15 runs. This narrows Pro's usefulness further even where its extra latency
would be tolerable (`tested`).

### 4. Recommendation

- **Clerk model: Flash (`gemini-3.5-flash-lite`), not Pro.** Same tier `ai/SubAgent.kt` already
  uses everywhere else. Faster by 4-6x, equally reliable on field names and row counts in this
  test, and its output text is clean enough to speak. Pro's only edge in 30 runs was surviving a
  missing-date bug that is a prototype/harness gap, not a real advantage - and it introduces its
  own text-cleanliness problem. Reserve Pro-tier only for the schema-generation path ticket 06
  already scoped there (drafting an aspect definition is a one-shot, off-turn call with no
  latency floor to the voice loop).
- **Fix owed on the real clerk, not this prototype:** inject the real current date (and ideally a
  small recent-record window) into the clerk's context the same way the live model gets grounded
  time facts, so "today"/"yesterday" resolve to real values instead of the model's own
  (wrong, inconsistent) guess. This is a correctness bug in the making, not a latency one -
  worth a line item in whichever ticket builds the real `aspect_clerk` tool.
- **When NOT to clerk (item 4):** a single-field, single-record write where the live model
  already knows the aspect's exact field names (e.g. it just called `describe_aspect` itself, or
  the aspect is one it writes often) is faster and just as safe going straight to `create_record`/
  `update_record`/`delete_record` - no reason to pay a second model round-trip to reason about an
  instruction that is already fully specified. The clerk earns its cost when the instruction is
  multi-step (find-then-update, find-then-delete) or produces multiple rows from one sentence
  (the "3x5" case) - exactly the cases where Flash still finishes in 1.1-2.5s. **Route:
  live model has the fields and it's a single write -> direct meta-tool call; live model would
  have to reason, search, or fan out to multiple rows -> `aspect_clerk`.** This routing guidance
  belongs in `aspect_clerk`'s own tool description so the live model self-selects, per the
  ticket's item 4 ("write the routing guidance into the meta-tool descriptions") - not yet
  written, since that description lives in real app code and this ticket is prototype-only.

### Tolerable-latency question, flagged for Kevin

The ticket asks "how long a clerk round-trip is tolerable before the assistant must say 'working
on it'?" This prototype measured the round-trip; it cannot answer the tolerance question, because
tolerance is a product/UX call about the live voice experience, not something inferable from
wall-clock numbers alone (`reasoned`, not `tested` - no live-session integration was built or
timed here). What is known: Flash's 1.1-2.5s clerk loop is in the same range as a single
`SubAgent.investigate()` round already used elsewhere in the app today (uncontested); Pro's
9-15s is not, and would need an explicit "working on it" filler line if Pro were ever used
synchronously. **Kevin's call needed:** is a bare 1-2.5s silence (no filler) acceptable for a
CRUD write on Flash, or should `aspect_clerk` always emit an immediate acknowledgement before the
loop runs, the way some tool calls already do elsewhere in the live session? This prototype found
no existing precedent either way in the files it read and did not go looking further, since that
is outside its scope.

### Assumptions ledger

- Flash is `gemini-3.5-flash-lite`, Pro is `gemini-3.1-pro-preview` (`gemini-3.5-pro` does not
  exist against this key) - `tested` (live `ListModels` call, this session).
- The prototype's loop shape (bounded rounds, tool-result echo, forced tool-free final round,
  1-round "wrap up" nudge) matches `ai/SubAgent.kt investigate()` - `traced` (read the function
  directly before writing the prototype) but not byte-identical: the real class handles
  cancellation, per-tool timeouts, `budgetMs`, and HTTP retry, none of which this throwaway script
  reproduces, so absolute latency numbers here are a floor, not a ceiling, on what the real clerk
  would show once wrapped in those layers.
- No field-name hallucination occurred in 30 runs - `tested`, but 30 runs is not proof of zero
  rate, only evidence it is not the common case at this store size and instruction complexity.
- The missing-date failure is a harness gap (the real clerk would receive a grounded date) rather
  than evidence Flash is unreliable at dates in general - `reasoned` from the trace text, not
  independently re-tested with a date supplied.
- Latency numbers are single-key, single-network-path samples (Kevin's home connection, one
  session) and will vary with load, geography, and Google-side model routing - `tested` for what
  they measured, `reasoned` as representative of typical conditions.
- Cost: 114 `generateContent` POSTs total across the smoke tests and full matrix, all short
  (single-instruction, small tool schema, no long context) - well inside "tens of calls, cents"
  in intent even though the literal count is 114; token volume per call is small. Not separately
  measured via `usageMetadata` in this prototype (`reasoned`, not `tested` - `SubAgent.kt` has a
  `parseUsageMetadata` helper this throwaway script did not bother wiring up).
