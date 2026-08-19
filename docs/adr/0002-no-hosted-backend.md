---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2 and §7"
tags: [adr]
---

# 2. No Kevin-hosted anything

## Standing

LOCKED. Not reopenable without Kevin.

## Context

A hosted backend is the default answer to sync, key management, and rate limiting. It is also a thing one person has to run, pay for, and keep alive for as long as the app exists.

## Decision

No backend, no Firestore, no broker, no proxy, no hosted key. Data lives on-device and in the driver's own Drive `appDataFolder`. The Gemini key is BYO, direct to Google, with no intermediary.

## Consequences

- `service/LiveConnection.kt` resolves direct-or-nothing. There is no code path for a broker, and adding one is not a small change.
- Rate limits, quota, and cost are the driver's own, which is why the spend gate exists at all.
- The one third-party cloud dependency is the Shelly garage relay, which nobody here runs. See [[c1-context]].
