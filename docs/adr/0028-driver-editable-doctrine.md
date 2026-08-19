---
status: accepted
decided: 2026-08-18
decided-by: Kevin
source: "decisions.md 2026-08-18"
tags: [adr]
---

# 28. Domain doctrine is a file the driver can edit

## Standing

ACCEPTED.

## Context

Playbooks were compile-time constants, and the voice dispatchers had behavioural rules but no domain knowledge. The same question got different answers depending on which door it came in.

## Decision

`advisor/Priming.kt` is the single resolver both paths call. `PlaybookStore` keeps the driver's own edit as plain text per profile under `filesDir`. Only `fleet` and `body` are primed on the voice path, because every model call in a bounded investigate loop carries the playbook.

## Consequences

- No migration: these are plain text files, not Room rows. Doctrine you can open in a text editor is the entire point.
- The editor is guarded: it rejects an edit over a 2,500-token ceiling, or one that deletes the professional-referral boundary.
- The guard is the kind of thing later work walks past while CI stays green. Same shape as the quant-viz silent regressions.
