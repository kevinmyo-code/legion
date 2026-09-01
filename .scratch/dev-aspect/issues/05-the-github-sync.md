---
map: dev-aspect
ticket: "05"
title: "The GitHub sync"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-seventh-aspect-on-the-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The GitHub sync

## Build

A Supabase Edge Function, on a cron, that populates the dev aspect from GitHub for every repo Kevin
owns.

**Sources, all falsifiable:**

- repos: `GET /user/repos` - name, default branch, archived flag, pushed_at
- open issues: `GET /repos/{owner}/{repo}/issues?state=open` (note: this endpoint returns PRs too,
  and they must be separated by the `pull_request` key or every PR is double-counted as an issue)
- open PRs, with draft state
- for LEGION only: `.scratch/*/issues/*.md` frontmatter - `status`, `ready`, `blocked-by`.
  `tools/pending_wiki.py` already performs exactly this parse; reuse its logic rather than writing
  a second parser that disagrees with the board.

**Provenance.** Every `project_items` row carries `source` - `github_issue`, `github_pr`,
`legion_ticket`. This is the same posture as `IngestMethod`: a row's origin is recorded, so a later
question about why the assistant said something is answerable.

**Deletes are the load-bearing part.** An issue closed on GitHub must vanish from `project_items`,
or the assistant reports finished work as pending with full confidence. That is the exact failure
this whole map exists to avoid, so the sync does a **full replace per repo per source**, inside a
transaction, not an upsert-only pass. A partial or failed fetch must leave the previous rows intact
rather than committing an empty replace - an API error is not the same sentence as "no open work,"
and rendering the first as the second is the shape CLAUDE.md section 1 calls out for calendars.

## Verification

- A repo with an issue closed between two sync runs no longer returns it. Tested, not reasoned.
- A sync run against an unreachable API leaves the prior rows and the prior `last_synced_at`
  untouched, and records the failure.
- PRs are not counted as issues.
- Archived repos are excluded from "what projects do I have" but their rows are not deleted.
- Rate-limit response handled; the function does not partially commit when it runs out of budget
  halfway through the repo list.
