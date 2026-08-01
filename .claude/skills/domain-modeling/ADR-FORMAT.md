# ADR Format (ADAPTED for LEGION)

**There is no `docs/adr/` in this repo and you must not create one.** Upstream writes one numbered
file per ADR; this project keeps a single append-only decision log at `memory/library/decisions.md`,
with dated entries. Write there instead, and write via the `librarian` agent (FILE mode) so the card
catalog at `memory/library/INDEX.md` stays in sync.

Note that `decisions.md` is an **inherited** shelf: most of its volume is Midnight AI strategy
history from before the 2026-07-30/31 pivot, and it carries a status banner saying so. New entries
append to the end, after the 2026-07-31 pivot entries. Do not edit the historical body.

Three rules from CLAUDE.md sit on top of this one:

1. **If the decision changes a CLAUDE.md rule, file it to `decisions.md` AND update CLAUDE.md in the same commit.**
2. **If it touches CLAUDE.md §2's locked pivot decisions, it is not a normal ADR.** Flag it, ask Kevin whether he is intentionally reopening it, and only then record it, noting the reopen explicitly and its scope.
3. **File it when it is made, not when the effort ends.** `.scratch/` is gitignored and a whole wayfinder map has already been lost that way. If the only copy of a decision is in `.scratch/`, it is not saved.

## Template

Entries are dated and appended, matching what is already in `decisions.md`:

```md
## YYYY-MM-DD {Short title of the decision}

{1-3 sentences: what's the context, what did we decide, and why.}
```

That's it. An entry can be a single paragraph. The value is in recording *that* a decision was made
and *why* - not in filling out sections.

## Optional sections

Only include these when they add genuine value. Most ADRs won't need them.

- **Status** frontmatter (`proposed | accepted | deprecated | superseded by ...`) - useful when decisions are revisited
- **Considered Options** - only when the rejected alternatives are worth remembering
- **Consequences** - only when non-obvious downstream effects need to be called out

## Numbering

Not applicable here. Upstream numbers one file per ADR; this repo appends dated entries to
`memory/library/decisions.md`, so there is no number to increment. Date the entry and append it.

## When to offer an ADR

All three of these must be true:

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
