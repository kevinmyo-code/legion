---
map: procedures
ticket: "14"
title: "Build: branch report and run inspector on the web desk"
type: build
status: built
status-detail: "built 2026-11-03 on feat/procedures-inspector; owes deploy, the Run button (ticket 18) and a look at real runs once ticket 15 ships car_check"
blockers: ["07", "13"]
blocked-by: ["[[07-recruiter-visits]]", "[[13-build-run-traces]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: branch report and run inspector on the web desk

## What

- **Run inspector** (web, desk tree, members only; recruiters per ticket "Recruiter visits"): list
  of runs (procedure, version, caller, status, ms), a run's steps as a vertical timeline with
  args, trimmed results, branch taken, who chose it (code / model / user), and the steps that did
  NOT run, said in words.
- **Branch report** per procedure version over a window: for each branch, runs that took it, runs
  that failed in it, and **branches that never ran**, with the count of runs it covers. Plain
  table, status in icon + word (CLAUDE.md chart rules from recruiter-demo ticket 14).
- **"Run this procedure" button** (ADR 0035 hands path) calling the same `run_procedure` through
  the tool door, ask steps rendered as buttons.
- Reads through REST views scoped by `household_of`; recruiter routes added to
  `household.recruiter_routes` only for their own visit.

## Verify

- vitest per screen state (runs, empty, unreadable, recruiter).
- Screenshots at 1440 light and dark.

## Built (2026-11-03, branch `feat/procedures-inspector`)

- **REST, read-only** (`server/procedures/views.py`, mounted at `/api/procedures/`):
  `GET /api/procedures/` (versions this household can see that are active or ran here, with
  this household's run counts), `GET /api/procedures/runs` (filters `procedure`, `status`,
  `caller_kind`, `since`, `limit` 1-200; newest first; a bad filter is a 400 in words),
  `GET /api/procedures/runs/<id>` (steps in order: kind, tool, args and result trimmed, outcome,
  branch, `chosen_by` with "A model chose" for a decide, ms, model tokens and the labelled cost
  estimate; plus `not_run` and a sentence for it), `GET /api/procedures/report?name=&version=`
  (every step and every way out of it, as the runner records them: `next`; a check's `pass` /
  `fail`; a decide's branches; an ask's answers. Runs that took each, by a model, last taken;
  per step reached / failed / refused / last failed; never-taken listed and said in a sentence).
- **Scoping**: every read through `household.tenancy.scoped`; a recruiter also narrowed to their
  own user. The report counts only this household's runs of a built-in, never another's. The
  four views are in `RECRUITER_READ_ONLY` (route walk green).
- **Web desk**: a "Runs" rail item (desk only; the pages work at phone width by URL),
  `/runs` (runs list, branch-report picker), `/runs/<id>` (step timeline, "Did not run"),
  `/runs/report/<name>?version=` (each step a card, each branch a row; never taken is a chip
  with an icon and the words "Never taken"). Empty and unreadable are different sentences; a
  visit is worded for the visit.
- `openapi.yaml` and `schema.d.ts` regenerated; the step `outcome` collided with the tool door's
  `OutcomeEnum`, so that name is pinned in `ENUM_NAME_OVERRIDES` with the four new ones.

## Verify, accounted for (CLAUDE.md L11)

- vitest per screen state: runs, empty, unreadable, recruiter, plus one run, model choice,
  refused run, the report and an unknown report (`src/routes/-runs.test.tsx`): **done**.
- Screenshots at 1440 light and dark (and 390): `SHOTS_LABEL=procedures-14 npm run shots`,
  in `.scratch/web-revamp/research/shots/procedures-14/`: **done** against the fake engine.
- Tenancy and recruiter isolation over the routes (`tests/test_procedures_inspector.py`,
  `test_tenancy.py`, `test_recruiter_isolation.py`): **done**.
- **"Run this procedure" button** (ADR 0035 hands path, ask steps as buttons): **deferred to
  ticket 18** (web assistant and recruiter use `run_procedure`), which wires the web door's
  call; this ticket stayed read-only.
- **Privacy, RESOLVED (Kevin, 2026-11-03: "agreed on all 3", on narrowing other members' runs
  to structure only)**: built on `fix/procedures-privacy-multicar`. `GET /api/procedures/runs/<id>`
  gives args, results and inputs only for runs the caller started. Another member's run (or a
  removed account's) keeps procedure, version, status, caller kind, timings and each step's
  kind, tool, outcome, branch and who chose it; `inputs` is null, every step's `args` null and
  `result` empty, and the run's `result` is "Started by another member: details are private to
  them." The list never carried content; the report's counts stay household-wide with no
  content. Recruiters unchanged (own visit only). No new field: openapi.yaml and schema.d.ts
  changed only in descriptions. Tests: `test_another_members_run_is_structure_only`,
  `test_a_removed_accounts_run_is_structure_only`,
  `test_the_report_counts_every_members_runs_with_no_content`. **Owed**: the web desk's run page
  shows the sentence as the answer and empty step results as they come; a look at it with a
  second member is owed with the deploy.
- On the deployed engine with real built-ins: **owed** (ticket 15 ships them).
