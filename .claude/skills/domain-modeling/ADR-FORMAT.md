# ADR Format (ADAPTED for LEGION)

**REVERSED 2026-08-18 by Kevin.** This file used to say "There is no `docs/adr/` in this repo and
you must not create one." That prohibition is dead. `docs/adr/` exists now. The reason for the
reversal is recorded below, because the original reasoning was sound and you should understand what
changed rather than assume the old rule was wrong.

## Two stores, one boundary

LEGION keeps decisions in two places, and the split is the whole point:

| Store | Answers | Shape |
|---|---|---|
| `docs/adr/NNNN-slug.md` | **What is binding right now?** | One file per standing decision. Status, supersedes/superseded-by, consequences |
| `memory/library/decisions.md` | **What happened, and when?** | Append-only dated log. 198 entries, most of it frozen Midnight AI history |

**An ADR describes present standing. The log describes past events.** That boundary is what keeps
them from becoming competing sources of truth, and it is not optional. Concretely:

- Do **not** write history into an ADR. "We tried X in July, then Y in August" belongs in the log.
- Do **not** treat a log entry as authoritative about what is binding today. Six later entries may
  have amended it. The ADR is where that is resolved.
- An ADR **links back** to its log entry via `source:` frontmatter. The link goes ADR -> log.
- The log is **not** edited to point forward at ADRs. It is append-only and mostly frozen.

## When a decision is made

1. **Append a dated entry to `memory/library/decisions.md`**, via the `librarian` agent (FILE mode)
   so `memory/library/INDEX.md` stays in sync. This still happens for every decision.
2. **Then ask whether it is standing.** If it changes what is binding going forward, write or amend
   an ADR too. Most decisions do not need one. A decision about which font to bundle is a log entry;
   a decision about how ingestion is allowed to trust an LLM is an ADR.
3. **If it changes a CLAUDE.md rule, update CLAUDE.md in the same commit.** Unchanged.
4. **If it touches CLAUDE.md §2's locked pivot decisions, it is not a normal ADR.** Flag it, ask
   Kevin whether he is intentionally reopening it, and only then record it, noting the reopen
   explicitly and its scope. Unchanged, and the most important rule here.

## ADR template

```md
---
status: accepted | amended | superseded | locked | proposed
decided: YYYY-MM-DD
decided-by: Kevin | orchestrator | research
amended: YYYY-MM-DD          # omit if never amended
supersedes: [NNNN-slug]      # omit if it overturned nothing
superseded-by: [NNNN-slug]   # omit unless dead
source: "[[decisions#<heading text>]]"
tags: [adr]
---

# N. Title

## Standing

One line. What is true today. If amended or superseded, say so here first, before anything else.

## Context

What was true that forced the decision. Two or three sentences.

## Decision

What was decided. Two or three sentences.

## Consequences

Only the non-obvious ones. Skip the section if there are none worth naming.
```

**`status: locked`** is LEGION-specific and means a CLAUDE.md §2 pivot decision: not reopenable
without Kevin. It is not one of the standard MADR statuses and that is deliberate.

**Numbering** is a four-digit zero-padded sequence in filename order. Never renumber; supersede.

## When to write an ADR

All three must be true. This test is unchanged and it is the part that keeps the set small.

1. **Hard to reverse** - the cost of changing your mind later is meaningful
2. **Surprising without context** - a future reader will look at the code and wonder "why on earth did they do it this way?"
3. **The result of a real trade-off** - there were genuine alternatives and you picked one for specific reasons

If a decision is easy to reverse, skip it - you'll just reverse it. If it's not surprising, nobody
will wonder why. If there was no real alternative, there's nothing to record beyond "we did the
obvious thing."

### What qualifies

- **Architectural shape.** "Ingestion is deterministic-first with an LLM fallback behind a reconciliation gate."
- **Integration patterns between aspects.** Where fleet, ledger, and pantry share machinery and where they deliberately do not.
- **Technology choices that carry lock-in.** Room, Drive as the only store, PdfBox-Android, Gemini. Not every library - just the ones that would take a quarter to swap out.
- **Boundary and scope decisions.** What an aspect owns and what it only references. The explicit no-s are as valuable as the yes-s.
- **Deliberate deviations from the obvious path.** `Long` cents instead of the `Double` convention used elsewhere in the same database is the canonical example in this repo, and its doc comment exists precisely so nobody "fixes" it.
- **Constraints not visible in the code.** Clone-and-run ruling out Firestore. Drive having no compare-and-swap. A restricted OAuth scope's verification burden.
- **Rejected alternatives when the rejection is non-obvious.** If a Drive access route was considered and dropped for a subtle reason, record it, or it will be suggested again in six months.

### Reversing an existing ADR

Do not edit the old ADR's Decision section to say the new thing. Write a new ADR, set its
`supersedes:`, and set the old one's `superseded-by:` and `status: superseded`. **The old ADR keeps
its original text.** LEGION has real reversals in its history (LLM extraction banned then allowed,
the sentience ban lifted, cyberdeck-ui superseded by mission-control) and their value is that you
can see what was believed before and why it changed.

## Backfill scope

The ADR set was backfilled on 2026-08-18 covering only decisions **still binding on that date**.
It is deliberately not a history of the project. Entries about the dead car-launcher product, the
retired commercial model, city-pop, or head-unit constraints live in `decisions.md` and were not
promoted, except where the death itself is the standing rule ("the commercial model is dead").
