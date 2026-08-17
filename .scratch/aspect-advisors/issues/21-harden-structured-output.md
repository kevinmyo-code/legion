# Harden structured output: give SubAgent a responseSchema

Type: task
Status: open

## Question

Found while building the harness (ticket 14), and it corrects a claim in
[The advisor contract](01-advisor-contract.md): **`SubAgent.askTyped` does not enforce any output
shape.** Traced by reading `ai/SubAgent.kt` - the request body carries `systemInstruction`,
`contents`, and optionally `google_search`, and has **no `generationConfig`, no `responseSchema`,
no `responseMimeType`**. `askTyped` is `ask` with a typed `AgentResult`; the "typed" refers to the
result wrapper, not the model's output.

So the advisor's structured proposal is currently **prompt instruction plus a parser**,
best-effort. The harness handles failure honestly (an explicit `ParseFailed` outcome), but the
propose-accept-write path depends on a parseable proposal: a parse failure is an advisor answer
whose offer silently fails to materialise, which reads to Kevin as the coach forgetting what it
just said.

The Gemini REST API supports `responseSchema` inside `generationConfig`. The work:

1. Add optional `generationConfig` plumbing to `SubAgent` (`responseMimeType = "application/json"`
   plus a `responseSchema`), without disturbing `ask`/`investigate`.
2. Pass `AdvisorAnswer`'s schema from the harness.
3. Keep the parser and `ParseFailed` anyway - defence in depth, and the API can still return
   something unexpected.
4. Measure whether it changes token cost materially (schema rides in the request).

**Why this is its own ticket and not folded into a build:** `SubAgent` is shared by every aspect
agent AND the pantry vision path (`imageBytes`), so a change to its request body needs its own
blast radius and its own regression run. It is also not blocking - the advisors work without it,
just less reliably.

## Verification 2026-08-16 - NOT BUILT, premise still true today

Swept against the tree. **None of the four items has landed.** All `traced`.

- **Item 1, `generationConfig` on `SubAgent`: absent.** Grep for
  `generationConfig|responseSchema|responseMimeType` in `ai/SubAgent.kt` returns **zero hits**.
  `askTyped` (`:138-165`) and the shared `buildAskBody` (`:101-124`) send only `systemInstruction`,
  `contents`, and conditionally `tools.google_search`.
- **Item 2, pass AdvisorAnswer's schema from the harness: absent.** `advisor/AdvisorAnswer.kt:49-70`
  still documents the gap in its own KDoc and ships `RESPONSE_SCHEMA` as **prompt text enforced by
  instruction**. `AdvisorAgent.kt:92` calls plain `askTyped`.
- **Item 3 was already true before this ticket** - `AdvisorAnswer.parse` (`:72-74`) and the
  `ParseFailed` branch (`LiveToolbox.kt:2803`) both predate it.
- **Item 4, token-cost measurement: no evidence anywhere in the tree.**

**The blast radius is exactly what the ticket feared:** `askTyped` has four production callers -
`AdvisorAgent.kt:92`, `MemoryConsolidator.kt:93`, `ReflectionEngine.kt:84`, `AmbientListener.kt:233`.
