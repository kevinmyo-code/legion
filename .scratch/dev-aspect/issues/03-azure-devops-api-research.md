---
map: dev-aspect
ticket: "03"
title: "The Azure DevOps REST API: auth, WIQL, scopes, limits"
type: research
status: resolved
status-detail: "Resolved 2026-09-01. Findings in research/azure-devops-api.md. fields allowlist confirmed; 11/11 claims confirmed, 8 items unconfirmed. Five findings changed the design."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Resolution (2026-09-01)

Findings: `.scratch/dev-aspect/research/azure-devops-api.md`. 11 of 11 ticket claims confirmed
(one with a correction), 6 of 6 also-find-outs answered, 8 items unconfirmed or contradicted and
named as such.

**The headline answer is yes.** `POST _apis/wit/workitemsbatch` takes a `fields` array and returns
only those fields; Microsoft's own paired samples show `System.Description` present without it and
absent with it. Ticket 02's titles-only option exists.

**Five findings changed the design**, all folded into tickets 02, 06 and 07:

1. `System.History` IS the comment thread, and is an ordinary field name. The restriction must be a
   hardcoded **allowlist**; a denylist cannot be written safely against up to 1024 custom fields.
2. `$expand=all` overrides the restriction and its interaction with `fields` is undocumented.
   Never send it.
3. `System.AssignedTo` returns an IdentityRef carrying a work email.
4. **A throttled request returns HTTP 200 with a `Retry-After` header**, not an error status.
   Branching on the status code alone hammers through the throttle and gets Kevin emailed.
5. **WIQL silently truncates at 20,000 rows.** Any spoken count from an unbounded query can be
   wrong with no error - which is exactly the shape of failure this map exists to prevent.

Also: the scope list in the brief was wrong (`GET _apis/projects` needs `vso.project` on top of
`vso.work` and `vso.code`), a PAT dies on a 30-90 day Entra sign-in-recency clock and surfaces as a
401, comments live on a separate endpoint that the work-item routes never touch, and WIQL itself
returns only ids regardless of what the SELECT names.

**One unresolved contradiction**, tagged `unverified` and carried into ticket 06: two Microsoft
pages disagree on whether `@Me` works in WIQL over REST. Use the literal identity string until a
real call settles it.
