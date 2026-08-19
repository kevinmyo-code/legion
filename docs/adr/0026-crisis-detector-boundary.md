---
status: locked
decided: 2026-08-02
decided-by: Kevin
source: "CLAUDE.md §7"
tags: [adr]
---

# 26. Genuine distress stops the character

## Standing

LOCKED. The one safety rule that is not a matter of taste.

## Context

Once [[0025-warmth-allowed-compulsion-banned]] allows a persona to be warm and present, the case where staying in character actively hurts someone has to be named explicitly.

## Decision

Genuine distress routes to `ai/CrisisDetector.kt`, which surfaces real resources and **stops performing the character**. Never counsel. Never simulate a professional.

## Consequences

- **Known gap: the crisis resource is US-only (988).** Anyone using this outside the US gets a number that does not work.
- This is the one place where breaking the persona is the correct behaviour, not a bug.
