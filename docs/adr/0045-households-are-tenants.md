---
status: proposed
decided: 2026-09-08
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-09-08 - One engine, many households: the web-and-households map]]"
tags: [adr]
---

# 45. Households are tenants

## Standing

**PROPOSED, awaiting Kevin's ack on `.scratch/web-and-households/issues/01-*.md`.** One Django
engine holds more than one household. Every data row belongs to exactly one household; every user
belongs to exactly one household; a member sees everything in their household and nothing outside
it. There are no roles inside a household except `owner`, which exists only to invite and remove
members. There are no approval workflows: an invite is a code, not a request.

This amends [[0044-django-is-the-engine]] rule 3 ("one household per server") and CLAUDE.md §1's
"no roles, no tenancy, no approval workflows, ever". The "no approval workflows" half survives
intact; "no tenancy" becomes "tenancy by household, nothing finer"; "no roles" becomes "one role,
for membership only".

Kevin, 2026-09-08: *"my parents can also register as users, they create a new household group."*

## Context

The engine was built for two adults on one server, and the trust model followed: membership was a
flag, authorization was "member or not", and no table carried an owner. Kevin's parents want to use
it. The alternatives were a second engine and a second database per family - which is
"nothing Kevin-hosted for anyone else" read as a prohibition on hosting for his own parents, and
which doubles every deploy forever - or one engine that knows which family a row belongs to.

## Decision

1. **Shared schema, one column.** Every data table gains `household_id uuid NOT NULL` referencing
   `household_household`. Unique keys that were per-server (`origin_guid`, `places.label`) become
   per-household.
2. **One choke point, then SQL.** `SyncedModelViewSet` filters every read and write on the
   request's household. Postgres row-level security keyed on a session variable Django sets per
   request makes a missed filter return nothing rather than another family's rows. The worker uses
   a role that bypasses it.
3. **Invite-only signup.** An owner mints a code; a code joins the owner's household or, when
   minted to create one, lets its first user found a new household and later users join it. Open
   signup is an environment switch, off by default, for a stranger's own compose stack.
4. **Kevin's engine may hold Kevin's family.** CLAUDE.md §7's hosting rule was written against a
   commercial broker serving strangers. A parent on the same engine is the household widened, not a
   customer, and it is recorded here in those words so it cannot be read as a loosening.

## Consequences

- CLAUDE.md §1 and §7 are edited in the same commit this ADR is accepted. `household/models.py`'s
  "there is no signup and no invite flow" docstring is rewritten.
- Clone-and-run is unchanged: a fresh compose stack has one household, made by
  `manage.py create_household`.
- The phone changes almost nothing: its device token identifies a user, the user identifies a
  household, and the API scopes by it. Signup and household management are web hands paths.
- Everything the §4 gate, provenance and the outbox already guarantee is per-household now and
  guaranteed exactly as before.
