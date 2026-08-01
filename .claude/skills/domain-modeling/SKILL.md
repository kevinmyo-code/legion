---
name: domain-modeling
description: Build and sharpen a project's domain model. Use when the user wants to pin down domain terminology or a ubiquitous language, record an architectural decision, or when another skill needs to maintain the domain model.
---

# Domain Modeling

Actively build and sharpen the project's domain model as you design. This is the *active* discipline - challenging terms, inventing edge-case scenarios, and writing the glossary and decisions down the moment they crystallise. (Merely *reading* CLAUDE.md for vocabulary is not this skill - that's a one-line habit any skill can do. This skill is for when you're changing the model, not just consuming it.)

## File structure (ADAPTED for LEGION)

Upstream keeps the glossary in `CONTEXT.md` and one ADR per file under `docs/adr/`. **This repo has
neither, and you must not create them.** CLAUDE.md is the declared "single source of truth" and
MEMORY.md is the state dashboard; a second glossary file would be a competing source of truth, which
is exactly the failure this project's read order (MEMORY.md, then CLAUDE.md, then the library) exists
to prevent.

Map the two upstream artifacts onto what is already here:

| Upstream | Here | Notes |
|---|---|---|
| `CONTEXT.md` (glossary) | **CLAUDE.md** | §1 identity and the aspects, §4 the reconciliation gate, §5 the Room tables, §6 the codebase map. This is the vocabulary. It is a rules file, not a scratch pad. |
| `docs/adr/NNNN-slug.md` | **`memory/library/decisions.md`** | One append-only decision log, not one file per decision. Dated entries. |
| (none) | **CLAUDE.md §2** | The locked pivot decisions. Some decisions are not merely recorded, they are locked. |

Rules that follow from that mapping:

- **Never create `CONTEXT.md`, `CONTEXT-MAP.md`, or `docs/adr/`.** If you catch yourself about to, stop: the target is CLAUDE.md or `decisions.md`.
- **Do not bulk-read the library.** Per CLAUDE.md, dispatch the `librarian` agent (RETRIEVE) against `memory/library/` instead of reading shelves into context. Card catalog: `memory/library/INDEX.md`.
- **Most of the library is FROZEN Midnight AI history**, carrying a status banner. Vocabulary from a frozen shelf is not this project's vocabulary. Check the banner before importing a term.
- **Write decisions via the librarian (FILE mode)**, not by hand-editing shelves, so the index stays correct. Verify what it writes - it has fabricated detail before.
- **If a decision changes a CLAUDE.md rule, file it to `decisions.md` AND update CLAUDE.md in the same commit.** That is CLAUDE.md's own rule, not this skill's.
- **Check §2 first.** If the term or decision under discussion touches a locked pivot decision (phone-only, no commercial model, no backend, Drive-BYO, clone-and-run, one global identity, city-pop dead, LLM-behind-a-gate), say so and ask whether Kevin is intentionally reopening it, before recording anything.

## During the session

### Challenge against the glossary

When Kevin uses a term that conflicts with the existing language in CLAUDE.md, call it out immediately. "CLAUDE.md §5 calls that `ledger_transactions`, but you seem to mean the ingested-file record - which is it?" **This project has a lot of renamed and retired history** (Moose, Aria, Kaze, Nightrunner, Yoko, Zero, `com.kevin.aria` to `com.kevin.midnightai` to `com.kevin.legion`, and a whole dead car-launcher product), so stale vocabulary is a live hazard rather than a theoretical one. The single sharpest trap: a term that still exists in code but whose *meaning* died with the pivot.

Live vocabulary worth guarding, because each has a precise meaning here:

- **aspect** - fleet, ledger, or pantry. Not "module", not "feature".
- **the gate** - the reconciliation check (CLAUDE.md §4). Not a permission gate, not a paywall.
- **quarantine** - a document that failed the gate and was written nowhere. Not "error", not "partial import".
- **estimate** - a value the source document never stated (pantry macros, a cost projection). Never presented as fact.
- **`DETERMINISTIC` / `LLM_RECONCILED`** - provenance, not a trust discount. Both passed the same gate.

### Sharpen fuzzy language

When the user uses vague or overloaded terms, propose a precise canonical term. "You're saying 'account' - do you mean a bank account on a statement, or a Google account for Drive? Those are different things here."

### Discuss concrete scenarios

When domain relationships are being discussed, stress-test them with specific scenarios. Invent scenarios that probe edge cases and force the user to be precise about the boundaries between concepts. In this project the productive scenarios are usually about **money that appears twice or not at all**: overlapping statements, reissued documents, two devices ingesting one folder.

### Cross-reference with code

When the user states how something works, check whether the code agrees. If you find a contradiction, surface it. Be aware that in this repo a doc comment may describe Midnight AI behavior that no longer exists, so the code wins over the comment.

### Capture resolved terms inline (ADAPTED)

When a term is resolved, capture it right there - don't batch them up. Where it lands depends on what it is:

- **A naming or vocabulary fix that belongs to the locked architecture** (a table name, a tool name, an aspect boundary) -> CLAUDE.md, in the section that already owns that vocabulary (§4, §5, §6). Keep it terse; CLAUDE.md is a rules file.
- **Anything else** -> hand it to the `librarian` (FILE) for the right shelf.

Do not invent a glossary section in CLAUDE.md. The vocabulary is already distributed across the sections that own each domain, and that is deliberate.

CLAUDE.md carries rules, not implementation detail and not scratch notes. If what you're about to write is a spec, a work log, or a half-formed idea, it goes to `.scratch/` or the library instead - and remember `.scratch/` is gitignored and has already been lost once.

### Offer ADRs sparingly

Only offer to create an ADR when all three are true:

1. **Hard to reverse** - the cost of changing your mind later is meaningful
2. **Surprising without context** - a future reader will wonder "why did they do it this way?"
3. **The result of a real trade-off** - there were genuine alternatives and you picked one for specific reasons

If any of the three is missing, skip the ADR. Use the format in [ADR-FORMAT.md](./ADR-FORMAT.md).
