---
status: locked
decided: 2026-07-30
decided-by: Kevin
amended: 2026-08-16
source: "CLAUDE.md §1"
tags: [adr]
---

# 20. The companion is named per profile; LEGION is the app

## Standing

LOCKED, and CORRECTED 2026-08-16. The correction matters: an earlier reading of this decision as 'one global assistant identity' is wrong.

## Context

Midnight AI gave each car its own companion identity. Cars are data, not identities. But the opposite extreme, one hardcoded global name, is also wrong: the thing Kevin talks to is named by whoever set up the profile.

## Decision

LEGION is the app. The companion is user-named per profile via `companion_profiles`. `ai/Personas.kt` holds the register copy as `ALFRED` and `DOROTHY`; `ai/AssistantIdentity.kt` is a resolver whose `withName` swaps the persona's default name for the driver's.

## Consequences

- **Never hardcode an assistant name into copy.** Identity strings carry a name slot.
- Alfred and JARVIS name a register band, not a character. A profile can be Alfred's register wearing another name entirely.
- `ai/PersonaTraits.kt` still holds the freeform authoring path and has no production caller. Do not simply re-wire it: `personaFor()` silently falls back to ALFRED on any unrecognised string, so freeform prose written to that field is discarded without an error.
