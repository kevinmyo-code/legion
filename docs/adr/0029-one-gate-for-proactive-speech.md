---
status: accepted
decided: 2026-08-18
decided-by: Kevin
source: "decisions.md 2026-08-18"
tags: [adr]
---

# 29. One gate for unprompted speech, and nothing is exempt

## Standing

ACCEPTED.

## Context

Unprompted speech had three bypass paths around the gate that was supposed to control it. A kill switch with three ways around it is not a kill switch.

## Decision

The choke point moved into `service/ProactiveBus.kt`, which exposes exactly two doors: `speakSolicited` for when the driver asked, and `speakIfAllowed` for everything else. The master switch stops the microphone, not merely the speech.

## Consequences

- The incoming-call announcement IS proactive and is silenced by the master switch. Kevin refused the first proposed exemption: mute means you are not told who is calling.
- Ambient car chatter was retired outright. Two paths that fired on silence were deleted as engineered engagement, which is [[0025-warmth-allowed-compulsion-banned]] applied.
- Bypass is now structurally impossible rather than merely unused. `ai/CrisisDetector.kt` stays outside and untouched.
