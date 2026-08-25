---
map: hardening
title: "Map: Hardening - the four named weaknesses"
charted: 2026-08-24
charted-by: "Kevin + Fable"
effort: "`.scratch/hardening/`"
tickets: 5
open: 2
status: open
tags: [map]
---
# Map: Hardening - the four named weaknesses

## Destination

**The four ranked weaknesses from the 2026-08-24 architecture review closed or consciously
accepted:** UI regression tests that catch what Robolectric cannot (Kevin: "especially the UI
testing part is important"); the sync channel's data-loss window closed by design, not scale;
an automatic pre-migration backup; encryption-at-rest decided on the record. Weakness 2
(LiveToolbox bloat) is excluded - the cutover and thermo pass already own it.

## Notes

**Domain:** LEGION (CLAUDE.md binds). Vendored skills that apply: testing-setup (Roborazzi),
android-testing, gradle-build-performance. Ticket 02 REOPENS a locked section-2 decision
(no Kevin-hosted anything) at Kevin's own initiative, 2026-08-24 - a backend is on the table
for the sync channel only; local Room stays primary regardless.

## Decisions so far

- [Screenshot tests](issues/01-ui-screenshot-tests.md) - ten baselines live in the JVM suite; DeckGrid graphicsLayer capture limitation named.

- [Sync channel v2](issues/02-sync-channel-v2.md) - **Supabase, BYO project, sync + push**; every push must pass the compulsion test; supersedes xlsx-as-channel when built.
- [Encryption at rest](issues/04-encryption-at-rest.md) - platform encryption accepted on the record; ADR owed.

## Not yet specified

- Whether eval-style screenshot baselines gate merges or stay advisory (same open question as
  the eval harness).

## Out of scope

- LiveToolbox decomposition (cutover + /thermo-review own it).
- Moving primary storage off-device. Never.
