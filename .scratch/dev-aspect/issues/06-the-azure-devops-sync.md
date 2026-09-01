---
map: dev-aspect
ticket: "06"
title: "The Azure DevOps sync"
type: build
status: open
status-detail: ""
blockers: ["01", "02", "03", "05"]
blocked-by: ["[[01-seventh-aspect-on-the-engine]]", "[[02-azure-devops-employer-boundary]]", "[[03-azure-devops-api-research]]", "[[05-the-github-sync]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# The Azure DevOps sync

## Build

The employer half, built to whatever ticket 02 ruled and on the facts ticket 03 verified. It writes
into the same `project_items` shape as the GitHub sync with `source = 'azure_work_item'`, so the
voice surface has one query rather than two.

**Do not start this before 02 is resolved.** The technical work is small and the boundary question
is the whole ticket; building first would settle by default a question that is Kevin's to rule.

Everything in ticket 05's delete discipline applies unchanged: full replace per project, inside a
transaction, prior rows survive a failed fetch.

**If 02 ruled titles-only**, the field restriction is enforced by requesting only those fields from
the API AND by the column simply not existing in the table. A column that exists and is left empty
is one careless change away from being populated.

## Verification

- The `fields` restriction actually restricts what comes back - inspected on a real response, not
  assumed from the docs.
- No description or comment text reaches Postgres. Checked by dumping a synced row, not by reading
  the code that was supposed to exclude it.
- PAT expiry: the sync reports a dead credential in words rather than silently syncing nothing and
  leaving stale rows to read as current.
- Ticket 02's revocation path works: there is a way to delete every Azure-sourced row, and it has
  been run once.
