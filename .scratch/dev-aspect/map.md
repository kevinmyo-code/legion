---
map: dev-aspect
title: "Map: The dev aspect - projects, repos and what is pending, by voice"
charted: 2026-09-01
charted-by: "Kevin + Opus"
effort: "`.scratch/dev-aspect/`"
tickets: 8
open: 8
status: open
tags: [map]
---
# Map: The dev aspect - projects, repos and what is pending, by voice

## Destination

Kevin asks LEGION what projects he has and what is pending on each, and gets an answer built from
sources he could verify himself: open GitHub issues and PRs, LEGION's own ticket frontmatter, and
his employer's Azure DevOps work items. Not from a prose summary somebody wrote weeks ago.

Kevin, 2026-09-01: *"i wanna be able to query my current projects in my repos etc ... i want to ask
the voice ai what projects i have, whats pending on which project."*

## Notes

**Domain:** LEGION, Android phone app, `com.kevin.legion`. Backend is Supabase (Postgres),
`supabase/migrations/`. Read `CLAUDE.md` for rules, `memory/MEMORY.md` for state.

**Skills:** `/grilling` for the decision tickets, `/research` for the Azure DevOps API facts,
`/domain-modeling` if the record-type vocabulary gets contested.

CLAUDE.md sections 1 (the six aspects and what a seventh costs), 4 rule 5 (anything the source does
not state is an estimate), 7 (third-party content is read-through only; every voice capability has
a hands path) bind everything here.

### Locked at charting (Kevin, 2026-09-01)

1. **Supabase Postgres, not NoSQL.** "It is all text" is not a reason to leave Postgres. Text
   columns, `jsonb` and full-text search cover it; RLS and the existing household model do not
   survive a move.
2. **Scope is all of Kevin's GitHub repos, plus employer work items in Azure DevOps.**
3. **Live sync, not a one-time export.** A one-time summary is stale the following day and the
   assistant then reports finished work as pending. Azure DevOps has a REST API and a PAT; the
   belief that it could not be live-connected was wrong.
4. **What is pending comes from falsifiable sources.** Open issues, open PRs, ticket frontmatter,
   work items. An LLM-written project summary is CLAUDE.md section 4 rule 5's estimate and may never
   be the answer to "what is pending" (ticket 04 decides whether it exists at all).

### The open question that gates the Azure half

Azure DevOps here is the EMPLOYER's. Syncing colleagues' work-item prose into a personal cloud
Postgres is exactly the shape section 7 calls read-through only, and is separately an employer
data-policy question nobody in this repo can answer. Ticket 02 owns it. The Azure build (06) does
not start until it is ruled.

## Tickets

| # | Type | Title |
|---|---|---|
| 01 | grilling | The seventh aspect, and whether it rides the engine |
| 02 | grilling | Azure DevOps and the employer-data boundary |
| 03 | research | The Azure DevOps REST API: auth, WIQL, scopes, limits |
| 04 | grilling | Does the prose summary earn its place |
| 05 | build | The GitHub sync |
| 06 | build | The Azure DevOps sync |
| 07 | build | The staleness contract |
| 08 | build | The voice surface and its hands path |
