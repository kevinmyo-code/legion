# Harden structured output: give SubAgent a responseSchema

Type: task
Status: resolved (2026-08-16) - built; schema shape unverified against a live call

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

## Answer

**Built 2026-08-16.** All four items addressed; one is deliberately unmeasured.

1. **`generationConfig` plumbed through `SubAgent`.** `StructuredOutputRequest(responseSchema)`,
   passed as an optional 5th parameter to `askTyped`, defaulted null. `askTyped`'s duplicated body
   construction folded into the shared `buildAskBody` (now `internal` for direct testing).
   **The other three callers are byte-identical** - `MemoryConsolidator`, `ReflectionEngine` and
   `AmbientListener` never pass it, and three tests mirror their exact call shapes to pin that they
   send no `generationConfig`. `investigate` is a separate path and untouched.
2. **`AdvisorAnswer.responseSchema()`** translates the prose contract into an OpenAPI-3.0 subset:
   `spoken` required STRING; `figures` an ARRAY of OBJECT whose `basis` is enum-constrained to
   `record|estimate|playbook`; `proposal` STRING `nullable: true` and absent from `required`.
   `AdvisorAgent.ask()` passes it.
   **The translation was non-trivial and is recorded:** the prose `RESPONSE_SCHEMA` was never a
   schema object at all. `figures`/`proposal` stay out of `required` to mirror `parse`'s existing
   leniency, and `proposal` is `nullable` because `parse` accepts absent-or-explicit-null.
3. **The parser and `ParseFailed` are untouched.** `RESPONSE_SCHEMA`'s prose was **kept**, not
   deleted - a schema constrains shape, not meaning, and nothing stops a schema-valid
   `basis: "record"` from being a fabricated figure.
4. **Token cost is UNMEASURED and that is stated rather than invented.** No live key is reachable
   from a JVM environment. What would be measured: `countTokens` on two otherwise-identical advisor
   bodies, one with `structuredOutput` and one without, isolating the delta to the added block.
   **Floor, executed not estimated: the schema serialises to 745 characters**, which by
   `AdvisorAgent.estimateTokens`' own chars/4 heuristic is roughly **186 tokens per advisor call**.
   Real cost will differ - a schema tokenises differently from plain text, and structured-output mode
   also changes candidate behaviour (no markdown fence to strip).

### The one claim that is inference, not execution

That Gemini's `responseSchema` takes **uppercase** `Type` enum names (`"STRING"`, `"OBJECT"`,
`"ARRAY"`) rather than lowercase JSON Schema convention is `reasoned` from the API's proto-derived
schema, **not verified against a live call.**

**It degrades safely, which is why it shipped unverified:** the prose schema was kept and the parser
is unchanged, so a rejected schema object falls back to exactly the pre-existing behaviour rather
than breaking. **First real advisor call on Kevin's key settles it.**

1452 tests green.
