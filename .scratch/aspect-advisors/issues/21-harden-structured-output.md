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
