---
status: accepted
decided: 2026-08-25
decided-by: Kevin
supersedes: [0010-drive-appdatafolder-only-store]
source: "decisions.md 2026-08-25"
tags: [adr]
---

# 38. A BYO Supabase project is the system of record

## Standing

ACCEPTED, NOT YET BUILT. Nothing in this ADR ships today: `.scratch/backend-erp/` is six resolved decision tickets and a seven-phase sequence, and the first phase has not started. Recorded as accepted because it binds every design choice from here, not because it is running.

## Context

LEGION was built local-first: Room is the truth, Drive `appDataFolder` is the sync channel, and nobody runs a server. That held while the phone was the only consumer. Kevin's 2026-08-25 reframing broke the premise rather than the design: the backend IS the ERP, the Android app becomes one consumer among several, and a Windows surface is explicitly coming. A sync channel between two phones and a system of record several clients read and write are different things, and Drive was only ever the first.

Two findings had also been sitting unresolved against the old model since the pivot: Drive has no compare-and-swap, so shared-file last-write-wins can silently lose rows, and the Drive OAuth client is keyed to a signing cert, which already breaks clone-and-run for a stranger.

## Decision

A Supabase project, one per household and owned by that household, is the system of record. Postgres holds the data, PostgREST and RPCs are the write path, and Supabase Auth with email and password identifies the two users. Room becomes a full local replica rather than the truth, and Drive keeps exactly one job: carrying `DatabaseSnapshot` backups.

**[[0002-no-hosted-backend]] is NARROWED, not superseded, and the distinction is the whole point.** Its principle was never "no server exists anywhere" - it was that nobody has to run, pay for, and keep alive a service for other people. A household's own Supabase project is BYO in exactly the shape the Gemini key already established: Kevin operates nothing, pays for nothing on anyone else's behalf, and a stranger who clones the repo stands up their own project and runs the committed migrations. No Kevin-hosted anything still binds in full.

## Consequences

- Migrations are committed SQL under `supabase/migrations/`, applied with the Supabase CLI, so clone-and-run stays testable rather than aspirational.
- Sign-in is email and password. Google OAuth was rejected because it needs two client IDs, one keyed to the app's SHA-1 cert, which is the clone-and-run trap already open against Drive. Magic link was rejected because Supabase's built-in email service is documented at 2 messages per hour, "not meant for production use", with no delivery SLA.
- Visibility is a household: one `household_members` table and RLS through a `security definer` helper. No roles, no tenancy, no approval workflows, ever.
- The session lives in `KeyVault` but fails closed, with no plaintext fallback. That fallback exists for cheap head units with flaky keymaster, and phone-only killed the reason. A refresh token is a standing credential to all household data, not a Gemini key.
- The free tier has zero backup retention and pauses after 7 days of inactivity. A keep-alive is therefore load-bearing, not a nicety, and recovery moves to a scheduled `DatabaseSnapshot` with a restore that has actually been exercised.
- Writes go direct with no offline queue, so the commit RPC is idempotent on the file's content hash. Without that, a lost ack leaves the phone unable to distinguish success from failure, and [[0031-speech-honesty-clause]] is binary with no vocabulary for "unknown".
