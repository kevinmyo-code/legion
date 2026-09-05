---
status: superseded
decided: 2026-07-30
decided-by: Kevin
superseded-by: [0043-django-is-the-second-client]
source: "CLAUDE.md §2 and §7"
tags: [adr]
---

# 2. No Kevin-hosted anything

## Standing

**SUPERSEDED 2026-09-05 by [[0043-django-is-the-second-client]].** The clause is now "no Kevin-hosted
anything the PHONE depends on to function"; a Django second client may be hosted because the phone
does not need it to work. Original text below, unchanged.

LOCKED, and still binding in full. **NARROWED 2026-08-25 by
[[0038-byo-supabase-is-the-system-of-record]]: not superseded.** A household now runs its own
Supabase project, which is a backend by the letter of the decision below but not by its principle.
What this ADR forbids is a service Kevin has to run, pay for, and keep alive for other people. BYO
Supabase is the same shape as the BYO Gemini key: Kevin operates nothing, and a stranger who clones
the repo stands up their own project. The clause "no Kevin-hosted anything" is untouched. Read the
Decision below as amended on one point only, that data also lives in the household's own Postgres,
never in one of Kevin's.

## Context

A hosted backend is the default answer to sync, key management, and rate limiting. It is also a thing one person has to run, pay for, and keep alive for as long as the app exists.

## Decision

No backend, no Firestore, no broker, no proxy, no hosted key. Data lives on-device and in the driver's own Drive `appDataFolder`. The Gemini key is BYO, direct to Google, with no intermediary.

## Consequences

- `service/LiveConnection.kt` resolves direct-or-nothing. There is no code path for a broker, and adding one is not a small change.
- Rate limits, quota, and cost are the driver's own, which is why the spend gate exists at all.
- The one third-party cloud dependency is the Shelly garage relay, which nobody here runs. See [[c1-context]].
