---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 97. Router-run reads: code may run the read a question plainly asks for

## Standing

**Some questions plainly ask for one read: what is due today, a balance, the cars on file. When the router recognises one, code runs that read before the model's first round.**
- The read goes into that turn's history as a real tool call and its result, so the model answers in one round instead of two.
- **Reads only.** The router never runs a write. Every write still goes through its tool, its guards and its confirm gate.

## Context

A data question today costs two model rounds around one tool. Measured on 2026-11-16, the first round takes about 1.0 s.

The agenda block ([[0093-voice-agenda-block-is-a-bounded-push-exception]]) won that second back for one domain by pushing context into every turn. This ruling generalises the gain without the push: the question pulls the read.

## Decision

- The read is the same engine tool the model would have called. It runs as the member, under `visible()`, and the trace marks it router-run.
- A read that returns third-party text taints the turn exactly as the model's own call would ([[0090-no-write-after-third-party-text]]).
- **Router-run reads replace the pushed agenda block** once the engine brain answers turns. The block retires with the phone loop ([[0095-alfreds-brain-runs-on-the-engine]]).
- For CLAUDE.md section 7's "pull-based tools always", a router-run read counts as pulled.

## Consequences

- A misrouted read costs one wasted query and no model round. The model can still call any tool in its allowlist.
- Amends [[0093-voice-agenda-block-is-a-bounded-push-exception]].
