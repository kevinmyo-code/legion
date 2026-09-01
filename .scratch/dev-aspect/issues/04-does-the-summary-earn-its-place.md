---
map: dev-aspect
ticket: "04"
title: "Does the prose summary earn its place"
type: grilling
status: resolved
status-detail: "Resolved 2026-09-01. No. There is no summary_text field anywhere. Killed by scope: one GitHub project and Kevin's own solo Azure work need no describing."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Does the prose summary earn its place

## Resolution (Kevin, 2026-09-01)

**No. There is no `summary_text` field anywhere.**

The idea came from Kevin's opening framing on 2026-09-01 - *"a summary md etc. that states the
project state, whats left to do"* - and the scope answers closed it without needing the argument.
On the GitHub side only LEGION matters for now, and LEGION does not need a paragraph explaining
what LEGION is. On the Azure side every project is Kevin's own solo work, which he already knows.

A summary field would exist to describe projects to someone who does not know them. There is no
such reader.

**It also loses on its own merits**, worth recording so it does not creep back by default when the
scope widens. A hand-written or LLM-written per-project description is the same artefact CLAUDE.md
was cut from 778 lines to remove on this very day: prose about a moving thing, wrong within weeks,
nobody's job to update. Read aloud by a voice assistant it is worse than wrong, because speech
carries no timestamp and no hedge unless something puts one there.

**If the scope ever widens** past LEGION and Kevin wants a "what is this project" answer, the
cheapest honest source is the repo's own README first paragraph, fetched read-through at question
time and attributed out loud as "its README says". Not a stored summary. That is a note for a
future ticket, not a plan.

## Verification

- No `summary`, `description` or `notes` field appears in the projects tool surface.
- Consequence for ticket 08: "what is this project" is not a supported question, and the tool
  description says so rather than letting the model improvise an answer.
