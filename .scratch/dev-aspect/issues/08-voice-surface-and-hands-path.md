---
map: dev-aspect
ticket: "08"
title: "The projects tool surface, and its hands path"
type: build
status: open
status-detail: ""
blockers: ["05", "06", "07"]
blocked-by: ["[[05-the-github-sync]]", "[[06-the-azure-devops-sync]]", "[[07-the-staleness-contract]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The projects tool surface, and its hands path

## Build

The thing Kevin asked for: *"i want to ask the voice ai what projects i have, whats pending on
which project."* Ticket 01 ruled this a **read-only tool surface named projects**, not an aspect -
so no CRUD tools, no engine record type, no widget.

**Two sources, one surface.** `docs/board.json` over HTTPS for LEGION (ticket 05), the on-device
read-through client for Azure (ticket 06). The model sees one set of tools and does not care which
answered.

**Questions it supports:**

- what projects do I have
- what is pending on {project}
- what is pending across everything

**Questions it does NOT support**, stated in the tool description so the model does not improvise:
what a project IS (ticket 04 killed the summary field), who is assigned to something (the assignee
field is off the allowlist), and anything needing a work item's body or comments.

## The noise problem, which is most of this ticket

LEGION has ~106 open tickets across ~31 maps and no GitHub issues. A faithful answer to "what is
pending on LEGION" is 106 items, which is useless spoken aloud. Grill question 3, still open.

**The rollup lives here, at the speaking end, not in the feed** - baking one summarisation into
`board.json` would lose the detail the hands path needs.

Decide during build, and write the choice into the tool description rather than leaving it to the
model:

1. Default to **ready** tickets only, not all open? Ready means "no decision left, go build", which
   is the closest thing to what a person means by "pending".
2. Roll up by map with counts, then offer to go deeper? *"Eleven maps have ready work. The biggest
   is aspect-engine with six."*
3. A hard cap on how many items are ever enumerated aloud, with the remainder given as a count.

## ADR 0035: every voice capability has a hands path

Binding, and it calls the **same controller** - not a second implementation, because two
implementations of one capability drift into disagreeing.

The hands path also carries what voice structurally cannot:

- the item URLs, tappable
- the full list where voice gives a rollup
- **sync and reachability status** (ticket 07's three states, visible rather than only spoken)
- the PAT entry field, and a manual retry

## Verification

- Every voice query has a hands equivalent, listed one by one, reaching the same controller. Not
  asserted in general.
- Ticket 07's three-states tests pass through this surface, not just in isolation.
- No assistant name hardcoded in any copy (CLAUDE.md section 1).
- **Run on the real phone**: ask all three questions, confirm the rollup is usable aloud, confirm a
  missing PAT says "I cannot see it" rather than "nothing pending". Until that run happens this
  ticket is `built`, not `resolved`, and the ledger says so plainly.
