---
status: accepted
decided: 2026-11-13
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-13 - Alfred greets like a person, from your real day (Kevin)]]"
tags: [adr]
---

# 91. Alfred greets like a person, from facts about your real day

## Standing

**Every tap and wake word opens with a personal greeting built from real facts, and Alfred also
speaks up unprompted at four moments.** This amends ADR 0087's "a tap asks the model for nothing";
its Live tool budget is unchanged.

- **Facts first, always.** A greeting is a proactive prompt (CLAUDE.md section 1): the phone hands
  the model a facts pack (events that ended, today's events, open todos and Canvas work due soon,
  where he is when away from home, today's workout, budget pace) and the model may mention nothing
  outside it. An empty or unreadable source is said as such to the model, never as "nothing on".
- **Topics:** earlier events, todos and coursework, place, body and money.
- **Travel tips** come from a live web search when he is somewhere new, and that turn is
  read-through: no write tool runs in it (ADR 0090), and memory consolidation drops it (ADR 0075).
- **Unprompted moments:** morning first unlock, after an event ends, arriving somewhere new, before
  something due within a day. No daily cap; quiet hours (default 10pm to 8am) are a setting.
- **Never the same question twice.** A topic he has answered, or that was raised and silenced, is
  not raised again.
- **His answers become memories** through the existing consolidation (ADR 0075).
- **Compulsion test, amended for check-ins:** a check-in may be raised without being actionable
  (clause b). Clauses (a) a real anchor, (c) never his absence, streak or app use, and (d)
  silenceable in one instruction still bind every raise.
- **On Live too**, accepting the cost until the Kyutai pipeline is the daily default.

## Context

Kevin wants Alfred to feel like a person who knows his day. ADR 0087 had replaced the spoken
greeting with scripted lines because generated greetings were most of the Live spend; Kevin is
moving voice to the laptop's Kyutai pipeline, where a greeting costs a text generation.

## Consequences

- A tap now waits for the greeting before listening; the scripted line stays as the fallback when
  the facts pack or the model is slow or unreachable.
- The four topics include body and money; tone is the guard against nagging, and tone is the
  weakest lever (CLAUDE.md section 7). Each topic stays silenceable on its own.
- Build tickets: `.scratch/alfred-greets/`.
