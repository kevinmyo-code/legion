---
title: Glossary
tags: [docs]
---

# Glossary

**This file holds one-line glosses and pointers. It is not the definition.** CLAUDE.md owns the
vocabulary; a second competing glossary is the exact failure the read order exists to prevent. If a
gloss here starts explaining more than CLAUDE.md does, that is a bug in this file, not an
improvement.

Terms are listed because they are load-bearing and easy to misread, not because they are obscure.

## The shape of the project

| Term | Gloss | Defined in |
|---|---|---|
| **aspect** | fleet, ledger, or pantry. Not "module", not "feature" | CLAUDE.md §1 |
| **LEGION** | the app. **Not** the assistant's name | CLAUDE.md §1, [[0020-companion-identity-per-profile]] |
| **companion** | the thing you talk to. User-named per profile. Never hardcode a name | [[0020-companion-identity-per-profile]] |
| **register band** | "Alfred/JARVIS" names a *tone*, not a character. `ai/Personas.kt` holds the copy | CLAUDE.md §1 |
| **clone-and-run** | a stranger clones, sideloads with their own cert and key, and it works | [[0003-clone-and-run]] |
| **BYO key** | the driver's own Gemini API key, direct to Google, no proxy | [[0002-no-hosted-backend]] |
| **appDataFolder** | the private per-app area of the driver's own Drive. The only store | [[0010-drive-appdatafolder-only-store]] |

## Ingestion

| Term | Gloss | Defined in |
|---|---|---|
| **the gate** | the reconciliation check. Not a permission gate, not a paywall | CLAUDE.md §4, [[0006-reconciliation-gate]] |
| **quarantine** | a document that failed the gate and was written **nowhere**. Not "error", not "partial import" | CLAUDE.md §4 rule 2 |
| **anchor** | the total a document prints about itself, which the gate checks against | [[0009-provisional-unreconciled-tier]] |
| **provenance** | `DETERMINISTIC` or `LLM_RECONCILED`. Both passed the same gate. **Not a trust discount** | CLAUDE.md §4 rule 4 |
| **`UNRECONCILED`** | a provisional row from a source with no anchor. Transient, and said in words on every surface | [[0009-provisional-unreconciled-tier]] |
| **estimate** | a value the source never stated. Pantry macros are the canonical case. Never presented as fact | [[0008-estimates-are-not-facts]] |
| **spend gate** | **two different things.** The LLM cost prompt ([[0016-llm-spend-gate-after-deterministic]]), and a retired Midnight AI access gate ([[0030-retired-carry-overs]]). Say which |
| **twin transactions** | genuinely identical rows within one statement. Why dedup counts rather than tests | [[0015-dedup-counts-per-tuple]] |

## The aspect engine

| Term | Gloss | Defined in |
|---|---|---|
| **aspect engine** | the runtime metadata system (`aspects`/`record_types`/`field_defs`/`records`) that is now the app's spine. Not a per-domain table set - one generic store | [[0037-the-aspect-engine-is-the-spine]] |
| **record type** | a user- or plugin-defined schema (e.g. "Transaction", "Workout") made of field defs. Defines a type, not an instance | `engine/FieldConfig.kt`, [[0037-the-aspect-engine-is-the-spine]] |
| **field def** | one typed field on a record type (13 field types v1: text, number, money-cents, date, etc.) | `memory/library/decisions.md` 2026-08-23 |
| **`RecordStore`** | `engine/RecordStore.kt`. The single write door for every engine record - reference integrity, delete policy, trash, computed fields | [[0037-the-aspect-engine-is-the-spine]] |
| **provenance** *(engine sense)* | tagged per engine record same as ledger/pantry provenance, but the write path is now `RecordStore` rather than a per-aspect DAO | CLAUDE.md §4 rule 4, [[0037-the-aspect-engine-is-the-spine]] |
| **widget pager / dashboard** | `ui/widgets/`, `LegionRoute.DASHBOARD`. The app's home screen since the 2026-08-24 home-flip cutover; "Classic" reaches the old per-aspect screens | [[0037-the-aspect-engine-is-the-spine]], `docs/architecture/cutover5-2026-08-24.md` |
| **mirror** | the xlsx-per-aspect export into the user's own Drive folder (`engine/mirror/`) - the audit surface and the two-phone sync channel, not the legacy `sync/` mechanism | `memory/library/decisions.md` 2026-08-23 ticket 12/13 |
| **meta-tools** | the nine generic voice tools (`service/EngineToolbox.kt`) that do CRUD/query over any record type, replacing most per-feature tools | `memory/library/decisions.md` 2026-08-23 ticket 06 |
| **clerk** | the bounded Flash sub-agent behind the meta-tools that turns a loose voice request into a structured engine call. An executor, not a router | `memory/library/decisions.md` 2026-08-23 ticket 06 |

## Planning and process

| Term | Gloss | Defined in |
|---|---|---|
| **wayfinder map** | a charted effort: one `map.md` plus numbered tickets under `issues/` | `.claude/skills/wayfinder/` |
| **ticket** | one question or one build step inside a map. Has `type`, `status`, `blocked-by` | `vault/Board.md` |
| **fog** | work known to exist but not yet charted as a ticket | `.claude/skills/wayfinder/` |
| **graduated** | a ticket promoted out of fog, or an effort promoted to its own map | ticket `status` values |
| **ready** | open, with every blocker resolved. Computed, so it goes stale without a re-run | CLAUDE.md §12 |
| **L*n*** | a numbered lesson. L24 is "the repo is ahead of its docs" | `memory/library/lessons.md` |
| **assumptions ledger** | the tags every agent report ends with: `built` / `tested` / `traced` / `reasoned` / `on-device` | CLAUDE.md §8 |
| **`locked`** | an ADR status meaning a CLAUDE.md §2 pivot decision. Not reopenable without Kevin | [[adr-index]] |

## Dead vocabulary

These still appear in code, comments, and the frozen library. **Their meaning died with the pivot**,
which makes them more dangerous than terms that were simply deleted.

| Term | What it was | Status |
|---|---|---|
| **Midnight AI** | the predecessor product, a commercial head-unit car launcher | Frozen archive. Read-only |
| **Zero** | the mascot | Dead. [[0021-city-pop-retired]] |
| **city-pop** | the old design language | Dead. Replaced by [[0023-design-language-mission-control]] |
| **Instrument** | the first replacement design language | Superseded. [[0022-design-language-instrument]] |
| **cyberdeck** | the second one. Shipped, then partly superseded | See `.scratch/cyberdeck-ui/map.md` |
| **Moose, Aria, Kaze, Nightrunner, Yoko** | former assistant names | Dead. See the register-band rule |
| **`CarTask`, `PlaceReminder`** | Room entities, still in the schema | Tombstones. Read by nothing new |
| **head unit** | the original target hardware | No longer constrains anything. [[0001-phone-only]] |

The single sharpest trap: **a term that still exists in code but whose meaning died with the pivot.**
When in doubt, the code wins over the comment, and CLAUDE.md wins over both for what a word means
now.
