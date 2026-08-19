---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 27. The app does not start itself, and works without a key

## Standing

ACCEPTED.

## Context

Midnight AI started on boot, which is reasonable for a car launcher and hostile on a phone. Separately, a first-run wall demanding an API key would make the app useless to anyone who wants only the parts that do not need one.

## Decision

Ignition is an explicit user toggle and `BootReceiver` was deleted. The Gemini key is optional and requested at the point of use, which is the assistant and the LLM ingestion fallback only.

## Consequences

- Clone-and-run holds harder: a stranger can import bank statements, read OBD, and use saved places without ever entering a key. Supports [[0003-clone-and-run]].
- `OnboardingState.isComplete` now honestly proxies 'has a Gemini key' rather than pretending to be a tour.
- The free-tier training disclosure appears on the key screen, at the moment of consent.
