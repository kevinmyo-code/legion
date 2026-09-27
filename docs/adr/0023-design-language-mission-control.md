---
status: superseded
decided: 2026-08-14
decided-by: Kevin
supersedes: [0022-design-language-instrument]
superseded-by: [0050-design-language-soft-material]
source: "decisions.md 2026-08-14"
tags: [adr]
---

# 23. Design language: mission control

## Standing

SUPERSEDED 2026-09-27 by [[0050-design-language-soft-material]], surface by surface: HOME, the
Lists screens and the shell chrome first. Every screen not yet converted still renders in mission
control, and this ADR describes that look until the last one moves.

ACCEPTED and BUILT. Verified on the phone, not just in review.

## Context

Instrument and its cyberdeck-ui successor shipped, and Kevin brought four reference photographs that described something more specific than either.

## Decision

Red-orange chrome, mint-green data readouts, amber highlights and markers, one global CRT bezel with flat unwarped content inside, console-tiled module roots with focused drilldowns, and a bundled monospace face.

## Consequences

- Green is dropped entirely from the chart kit. The palette runs on two hues.
- Red collapsed from 50 call sites to 3, alarm-only. It was never exclusive before, which is why alarms did not read as alarms.
- The device caught five bugs that review could not, including a full card number rendered on screen. This map is why on-device verification is now a gate rather than a nicety.
