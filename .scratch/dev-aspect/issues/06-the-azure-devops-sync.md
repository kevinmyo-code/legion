---
map: dev-aspect
ticket: "06"
title: "The Azure DevOps read-through client"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The Azure DevOps read-through client

## What changed from charting

Charted as a sync into Supabase. Ticket 02 ruled read-through instead: **queried live when Kevin
asks, never persisted anywhere.** No Edge Function, no table, no cron. An on-device client only.

Ticket 03's research settled the API facts; every claim below is `traced` to a Microsoft page in
`research/azure-devops-api.md` unless tagged otherwise.

## Build

An on-device client, called only from the projects tool surface (ticket 08). Nothing else may call
it, and nothing it returns may be written down.

**Auth.** PAT over Basic with an EMPTY username: `Authorization: Basic base64(":" + PAT)`. Entra
OAuth is not available to a phone app, so this is the only documented option, not a shortcut. PAT
scopes: `vso.work`, `vso.code`, **and `vso.project`** - the last is needed for `_apis/projects` and
was missing from the charting brief. Stored in encrypted preferences, entered by hand, never in the
repo and never sent to Supabase.

**Calls**, all `api-version=7.1`:

1. `GET https://dev.azure.com/{org}/_apis/projects` - the project list.
2. `POST {org}/{project}/_apis/wit/wiql` - open work items. Returns **ids only**, whatever the
   SELECT names, so nothing leaks here.
3. `POST {org}/_apis/wit/workitemsbatch` with an explicit `fields` array - the only call that
   returns text.

**The field allowlist is a hardcoded constant and a denylist is forbidden.** `System.Id`,
`System.Title`, `System.State`, `System.WorkItemType`, `System.TeamProject`, `System.ChangedDate`.
Nothing else. `System.History` is the comment thread and is an ordinary field name among up to 1024
a custom process may define; `System.Description` is the body; `System.AssignedTo` carries a work
email and is worthless on solo projects anyway. **`$expand` does not appear in this client at all** -
`$expand=all` returns every field regardless of `fields`, and their interaction is undocumented.

**Two failure shapes that do not look like failures**, both from the research and both load-bearing:

- **A throttled request returns HTTP 200 with a `Retry-After` header**, not an error status. Branch
  on the header, not the status code. Getting this wrong hammers through the throttle and gets
  Kevin emailed by his employer's Azure DevOps, which is the worst possible way for this to surface.
- **WIQL silently truncates at 20,000 rows with no error.** Bound the query (by project, by state,
  by date) so the cap is never approached, and if a result ever comes back at exactly the cap,
  treat it as unreliable and say so rather than speaking a count. An unbounded count that is
  silently wrong is precisely the failure this map exists to prevent.

**A dead PAT is a 401**, and a PAT dies on a 30-90 day Entra sign-in-recency clock even before its
expiry date. It must produce "I cannot reach Azure DevOps" - never "nothing is pending" (ticket 07).

**`@Me` in WIQL is `unverified`** - two Microsoft pages contradict each other on whether it works
over REST. Use the literal identity string until a real call settles it, then record which was
right in `research/azure-devops-api.md`.

## Verification

- Dump the app database after a real Azure query on the phone. **No work-item text is in it.**
  Inspected, not reasoned. This is the ticket's whole point.
- A unit test asserts the allowlist constant contains no field outside the six, and that the client
  emits no `$expand` parameter.
- Throttle handling tested against a synthetic 200-with-`Retry-After`, since the real one cannot be
  provoked on demand.
- A 401 produces the cannot-see sentence, not the nothing-pending sentence.
- **Before building: confirm the org has not disabled PAT creation.** Entra-backed organisations can
  turn it off outright with a named per-person allowlist, which would make this ticket unbuildable.
