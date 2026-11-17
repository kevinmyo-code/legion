---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 96. The agent graph: a code router, a specialist per domain, a generalist for misses

## Standing

**Code routes each turn to one specialist agent.** Each specialist has its own prompt, its own tool allowlist, its model and its write permission. The specialists are:
- **day**: dates and notes;
- **money**: the ledger and the bought log;
- **fleet**;
- **places**;
- **body**, including goals;
- **concierge**: memory, search, the brief, docs, the Claude worker, records and procedures;
- **phone**: device control;
- **mail**: [[0099-engine-mail-read-draft-send]].

A turn the router cannot place goes to the **generalist**, which carries the whole surface. A question that names two domains gets one agent holding both specialists' tools. No composer round follows it.

## Context

Kevin: *"that was my initial plan, a specialist agent for each aspect or domain"*.

Routing with a model costs about a second per turn. The phone's `look_up` and `ask` sub-agent call measured 0.9 to 1.2 s on 2026-11-16. A single large prompt is where "to-do" turned into "reminder"; `service/tools/day/ListRouting.kt` exists because of it.

## Decision

- **The router is code.** It holds a lexicon per specialist and keeps follow-ups with the previous specialist. No model call is made to route. When a question plainly names one read, the router may run that read itself ([[0097-router-run-reads]]).
- **A specialist is files in the repo.** Each one is a YAML spec, a prompt file and its router terms. Adding a specialist means adding files, not changing the framework.
- **The spec loader checks every spec at import:**
  - every tool it names exists;
  - read-through tools appear only in specs allowed to hold them;
  - write permission is declared.
- **Specialists start from the seven aspects, merged where the work is one job.**
  - Dates and notes form one agent, because "what's due" merges both.
  - Pantry's bought log goes to money.
  - **The aspect list is unchanged.** An eighth aspect is still Kevin's ruling, and no agent creates one.
- **The router may start a procedure ([[0070-procedures-run-by-the-engine]]) in code.** A `device` step kind joins the closed set of step kinds. It is a relayed phone read and may only read.

## Consequences

- A specialist refuses a tool outside its allowlist in words, and the refusal is counted as a misroute.
- The eval suites cover each specialist ([[0100-agent-runtime-traces-and-eval-gates]]). Today's 21 standing cases cover only day and fleet, so each specialist's suite lands with it.
- Amends [[0070-procedures-run-by-the-engine]] by adding the `device` step kind.
