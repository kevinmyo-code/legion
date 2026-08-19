---
map: aspect-advisors
ticket: 01
title: The advisor contract
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The advisor contract

## Question

What is the shared shape all five advisors implement - one harness, five briefs, or five bespoke
agents? Specifically: one-shot SubAgent vs bounded investigate loop; the input contract (playbook
brief + digest + the user's question + goals); the output contract (spoken advice, plus an
optional structured proposal for the write-back path); how the orchestrator exposes them (one
`ask_advisor(aspect, question)` tool vs five tools, against the 69-tool token budget); and
whether an advisor sees its own past advice or only what was accepted into the record
(§7 falsifiable-memory rule). Legion-shape's "one idea, four coats" precedent (shared vocabulary,
separate storage) is the prior to test against.

## Answer

Grilled with Kevin, 2026-08-13. Four calls:

1. **One harness, five briefs.** A single `AdvisorAgent` wraps `SubAgent`; each aspect
   contributes a brief: playbook + digest builder + writable-proposal schema. The shared rules
   (estimate wording, no-compulsion, crisis stop) live once in the harness prompt so no aspect
   can forget them - the tier-tagging-at-the-tool-layer trick again.
2. **One `ask_advisor(aspect, question)` tool** on the live session. Accepting a proposal reuses
   existing write tools; no per-aspect tools, no accept tool.
3. **One-shot `askTyped`.** The digest is precomputed deterministically, so one POST carries
   brief + playbook + digest + goals + advice-log window + question, and returns prose plus an
   optional structured proposal. Chosen over `investigate` (slower, costlier, and Flash cannot
   combine structured output with tool declarations - `SubAgent`'s own docs, `traced`).
4. **Advisors keep an advice log** (Kevin's call, against the record-only recommendation): each
   exchange persists - question, advice gist, proposal, accepted/rejected. The digest carries the
   last ~N (about 3) exchanges per aspect; full history stays queryable on demand. Exact N is the
   token-budget ticket's to pin. Schema rides with the goal store ticket. The "I've told you
   three times" compulsion edge is explicitly the safety ticket's to police.

Segue surfaced mid-grilling and charted separately: Kevin flagged live-session context bloat and
asked whether the orchestrator can DISCOVER tools instead of carrying all ~69 declarations - now
the research ticket "Lean toolbox: tool discovery for the live session" (12).

Assumptions ledger: SubAgent API shapes and the structured-output/tools incompatibility -
`traced` (read `ai/SubAgent.kt`). Everything else - Kevin's decisions, recorded live.

## Correction, 2026-08-13 (found while building ticket 14)

Call 3 above says `askTyped` "returns prose plus a structured proposal in one POST". **That
overstated what `askTyped` does, and the error was mine, not the executing agent's.**

Verified by reading `ai/SubAgent.kt` directly: `askTyped` builds a plain request body -
`systemInstruction`, `contents`, optionally `google_search` - and has **no `generationConfig`, no
`responseSchema`, no `responseMimeType`**. It is `ask` with a different name and a typed
`AgentResult`; nothing constrains the model's output shape. So structured output today is
**prompt instruction plus a parser**, best-effort, not API-enforced. The harness handles this
correctly with an explicit `ParseFailed` outcome.

What is still TRUE and unchanged: one-shot beats `investigate` here, and Flash cannot combine
tool declarations with structured output - so the call shape decision stands. What changed is
only the strength of the guarantee behind it.

**Why this matters more than a doc nit:** the propose-accept-write path depends on a parseable
proposal. A best-effort parse means an occasional advisor answer whose proposal silently fails to
materialise, which reads to Kevin as the coach forgetting what it just offered.

**Follow-up, not silently absorbed:** the Gemini REST API *does* support `responseSchema` inside
`generationConfig`. Adding that plumbing to `SubAgent` would make the proposal path reliable
rather than best-effort. Filed as [Harden structured output](21-harden-structured-output.md)
rather than folded into a build ticket, because it changes a shared class every aspect and the
pantry vision path already use.
