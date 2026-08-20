---
status: accepted
decided: 2026-08-19
decided-by: Kevin
source: "decisions.md 2026-08-19"
tags: [adr]
---

# 31. The assistant may not assert an outcome it did not observe

## Standing

ACCEPTED and BUILT. Presence is guarded by a unit test. **Obedience is not guarded at all, and cannot be on this machine.**

## Context

Kevin asked for navigation on a real drive. No navigation tool existed, so the model produced "opening it" and nothing opened. The prompt's existing rule - always call the matching tool before claiming you have done something - cannot bind where there is no tool to call. The one honesty rule in the prompt that has never failed is the garage relay clause, which does not ask the model to be careful: it forbids the two verbs that assert an outcome the app cannot observe, and mandates two that assert only the action taken.

## Decision

Outcome-asserting vocabulary - done, started, sent, opened, booked, played, set, on its way - may be spoken **only after a tool call in the same turn came back successful**. A tool that came back unsuccessful is the same as no tool at all. When there is no tool, the companion says so plainly and offers the nearest thing it genuinely has a tool for, never an invented alternative. The clause lives in `sharedInstructions`, where a persona edit cannot reach it, and enumerates no capabilities.

## Consequences

- The rule is conditioned on the tool RESULT, not on a list of what LEGION cannot do, so it survives every new tool. A negative list would be correct only until the next one landed.
- This is CLAUDE.md §4's reconciliation posture applied to speech. The gate quarantines a figure it could not verify; this quarantines a verb whose outcome it could not verify.
- It binds ingestion-shaped tools too: [ADR 0002](0002-reconciliation-gate.md) already forbids asserting an unverified figure as fact, and this forbids narrating one as done.
- **A prompt rule is the only lever that can prevent this.** Nothing inspects the spoken audio, so by the time a sentence exists it has been streamed to the driver. A runtime detector was costed - always-on output transcription, a token cost every turn on the driver's own key - and declined, because it could only notice a false claim after the fact.
- An obedience eval against the driver's key was also declined. `AriaBrainHonestyClauseTest` therefore proves only that the rules are still in the prompt, including the four older ones nothing had ever guarded. **A green suite is not evidence the assistant told the truth on a drive.**
