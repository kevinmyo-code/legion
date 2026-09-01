---
map: dev-aspect
ticket: "03"
title: "The Azure DevOps REST API: auth, WIQL, scopes, limits"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The Azure DevOps REST API: auth, WIQL, scopes, limits

## Question

Establish the facts the Azure sync will be built on, from primary Microsoft documentation, before
anyone writes the client. The charting session asserted the shape below from memory and it is
tagged `reasoned`, not verified.

Claimed at charting, all needing confirmation:

- Base is `https://dev.azure.com/{organization}`, `api-version=7.1` as a required query parameter.
- Auth is a PAT over Basic, with an EMPTY username: `Authorization: Basic base64(":" + PAT)`.
- Work items are queried with WIQL - `POST {org}/{project}/_apis/wit/wiql` - which returns only
  ids, so a second call to `_apis/wit/workitems?ids=...&fields=...` fetches the fields.
- `fields` on that second call can restrict the response to titles and state, which is the
  mechanism ticket 02's titles-only option depends on. **If it cannot, ticket 02's recommended
  option does not exist and that ticket has to be re-decided.**
- PAT scopes `vso.work` (Work Items read) and `vso.code` (Code read) are the minimum.
- `GET {org}/_apis/projects` lists projects; `GET {org}/_apis/git/repositories` lists repos.

## Also find out

1. Rate limits, and what a throttled response looks like (Azure DevOps uses TSTUs; find the actual
   header and status code).
2. Maximum ids per `workitems` batch call.
3. PAT maximum lifetime and whether it can be made non-expiring - this decides whether the sync
   silently dies in ninety days.
4. Whether the WIQL result can be filtered to items changed since a timestamp, so the sync is
   incremental rather than a full pull every run.
5. Whether an organization can disable PAT creation by policy, which would kill the whole approach.
6. Server-side rate or audit visibility: would an employer admin see this traffic, and as what.

## Verification

Findings land in `.scratch/dev-aspect/research/azure-devops-api.md`, every claim carrying a link to
the Microsoft page it came from. Anything that could not be confirmed from primary docs is tagged
`reasoned` and named as unconfirmed, not quietly dropped.
