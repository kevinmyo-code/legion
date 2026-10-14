# ADR Format (ADAPTED for MIDNIGHT AI)

**There is no `docs/adr/` in this repo and you must not create one.** Upstream writes one numbered
file per ADR; this project keeps a single append-only decision log at `memory/library/decisions.md`,
with dated entries. Write there instead, and write via the `librarian` agent (FILE mode) so the
card catalog at `memory/library/INDEX.md` stays in sync.

Two rules from CLAUDE.md sit on top of this one:

1. **If the decision changes a CLAUDE.md rule, file it to `decisions.md` AND update CLAUDE.md in the same commit.**
2. **If it touches the §8 frozen-decisions table, it is not a normal ADR.** Flag it, ask Kevin whether he is intentionally reopening it, and only then record it, noting the reopen explicitly and its scope. Several §8 rows are already scoped reopens (Mapbox, the entitlement broker, BYO-cloud sync) and each records exactly what was reopened and what stayed shut. Match that shape.

## Template

Entries are dated and appended, matching what is already in `decisions.md`:

```md
## YYYY-MM-DD {Short title of the decision}

{1-3 sentences: what's the context, what did we decide, and why.}
```

That's it. An entry can be a single paragraph. The value is in recording *that* a decision was made and *why* — not in filling out sections.

## Optional sections

Only include these when they add genuine value. Most ADRs won't need them.

- **Status** frontmatter (`proposed | accepted | deprecated | superseded by ADR-NNNN`) — useful when decisions are revisited
- **Considered Options** — only when the rejected alternatives are worth remembering
- **Consequences** — only when non-obvious downstream effects need to be called out

## Numbering

Not applicable here. Upstream numbers one file per ADR; this repo appends dated entries to
`memory/library/decisions.md`, so there is no number to increment. Date the entry and append it.

## When to offer an ADR

All three of these must be true:

1. **Hard to reverse** — the cost of changing your mind later is meaningful
2. **Surprising without context** — a future reader will look at the code and wonder "why on earth did they do it this way?"
3. **The result of a real trade-off** — there were genuine alternatives and you picked one for specific reasons

If a decision is easy to reverse, skip it — you'll just reverse it. If it's not surprising, nobody will wonder why. If there was no real alternative, there's nothing to record beyond "we did the obvious thing."

### What qualifies

- **Architectural shape.** "We're using a monorepo." "The write model is event-sourced, the read model is projected into Postgres."
- **Integration patterns between contexts.** "Ordering and Billing communicate via domain events, not synchronous HTTP."
- **Technology choices that carry lock-in.** Database, message bus, auth provider, deployment target. Not every library — just the ones that would take a quarter to swap out.
- **Boundary and scope decisions.** "Customer data is owned by the Customer context; other contexts reference it by ID only." The explicit no-s are as valuable as the yes-s.
- **Deliberate deviations from the obvious path.** "We're using manual SQL instead of an ORM because X." Anything where a reasonable reader would assume the opposite. These stop the next engineer from "fixing" something that was deliberate.
- **Constraints not visible in the code.** "We can't use AWS because of compliance requirements." "Response times must be under 200ms because of the partner API contract."
- **Rejected alternatives when the rejection is non-obvious.** If you considered GraphQL and picked REST for subtle reasons, record it — otherwise someone will suggest GraphQL again in six months.
