-- LEGION: `BANK_API` joins public.provenance (ADR 0057, Kevin 2026-10-09: "everything from plaid
-- becomes truth").
-- Depends on: 20260825000200_conventions.sql (which created public.provenance)
--
-- **Run this BY HAND, as the type's owner, BEFORE deploying the engine that carries
-- `ingest/migrations/0019_provenance_bank_api.py`.** The enum is owned by Supabase's `postgres`
-- role; the engine role (`legion_engine`) owns the ledger tables but not this type, so it cannot
-- run `ALTER TYPE` itself. Migration 0019 checks for the value: present, it does nothing; missing
-- and not addable, it stops `migrate` in words naming this file, before any other part of the
-- Plaid change is applied. Supabase dashboard: SQL editor, paste, run.
--
-- Idempotent (`if not exists`). Not reversible: Postgres cannot drop an enum value.
--
-- What it means: a ledger row whose source is the bank's own transaction feed (Plaid). It is
-- stored and shown as fact with no reconciliation (CLAUDE.md section 4, AMENDED 2026-10-09). The
-- value exists so the source stays traceable in the database; no surface renders it differently
-- from a verified row.

alter type public.provenance add value if not exists 'BANK_API';
