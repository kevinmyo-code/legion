---
map: aspect-advisors
ticket: 09
title: The cross-aspect HOME advisor
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "08"]
blocked-by: ["[[01-advisor-contract]]", "[[08-aspect-digests]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The cross-aspect HOME advisor

## Question

What does the fifth advisor do that calling two aspect advisors in one conversation does not?
Candidate value: cross-domain synthesis (eating out hits both macros and budget; a car repair
hits the emergency fund; an overloaded week explains missed workouts). Open: does it receive all
four digests raw, or the four advisors' summaries; does it have its own playbook or only a
synthesis brief; how the orchestrator routes to it ("how am I doing overall") vs to an aspect
advisor; and its token cost per question, which is roughly the sum of the digests.

## Answer

Grilled with Kevin, 2026-08-13 (batched). Four calls, all to the recommendation.

**1. Its own condensed cross-aspect digest.** A fifth `DigestBuilder` produces one headline line
per aspect - the gap that matters, the trend direction, any goal off track - plus all goals and
any flagged exceptions. Roughly one aspect digest in size rather than four. The alternatives were
both rejected on cost: four raw digests is ~4x the prompt of any other question, and running the
four advisors first is five Gemini calls and five times the latency for one answer.

**2. Synthesis brief, no fifth playbook.** HOME's job is connecting, not domain expertise. It
spots cross-domain interactions (eating out hitting both macros and budget; a repair hitting the
emergency fund; an overloaded week explaining missed sessions), says which goal is most at risk,
and names the trajectory. **When a question needs domain depth it says so and points at the
aspect advisor** rather than improvising. No fifth body of research to maintain, and no risk of
it contradicting a playbook it only half holds.

**3. Just another aspect value**: `ask_advisor(aspect = "home", question)`. The tool description
says home is for overall or cross-cutting questions; the live model routes, and Kevin can always
override by naming the aspect out loud. No new mechanism, no second place for routing logic.

**4. Read-only; it hands off to the aspect.** HOME names the connection and says "ask the CRED
advisor to set that budget"; the concrete proposal comes from the advisor that owns that aspect
and its allowlist. This keeps the per-aspect allowlist simple and keeps the author of any
proposal the one actually holding the relevant playbook. It also means HOME needs no entry in
the propose-accept-write allowlist at all.

**Consequence for the harness:** the one-harness-five-briefs contract holds with a wrinkle - the
HOME brief carries no playbook and declares no writable operations. The harness must therefore
treat "playbook" and "writable set" as optional parts of a brief, not required ones. Worth
stating because a harness that assumes every brief has both would need reworking to admit HOME.

Assumptions ledger: all four are Kevin's decisions, recorded live. The harness consequence and
the cost comparisons (4x for raw digests, 5 calls for advisor summaries) - **reasoned** from the
contract and digest decisions, not measured; the token-budget ticket owns the real numbers.
