---
map: web-and-households
ticket: "01"
title: "Households are tenants: one engine can hold more than one family"
type: decision
status: resolved
status-detail: "Accepted 2026-09-08 by Kevin, as proposed: one user = one household, shared schema with household_id, Django choke point then RLS, invite-only signup, one owner role for membership only. ADR 0045 accepted; CLAUDE.md sections 1 and 7 and the feature-add checklist edited in the same commit; ADR 0044 rule 3 carries the amendment note. Build: tickets 02, 02b, 03."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Households are tenants

**Kevin, 2026-09-08:** *"my parents can also register as users, they create a new household group."*

That sentence reopens two standing rulings, and this ticket exists so the reopen is explicit rather
than slid past (ADR-FORMAT rule 4):

| Ruling | Text today | After |
|---|---|---|
| CLAUDE.md §1 | "no roles, no tenancy, no approval workflows, ever" | **Households are tenants.** No roles inside a household except `owner`, used only to invite and remove. No approval workflows |
| ADR 0044 rule 3 | "Two adults, no roles, no tenancy, one household per server" | One engine, N households, each all-or-nothing to its members |
| `household/models.py` docstring | "There is no signup and no invite flow" | Invite-only signup; open signup behind `LEGION_OPEN_SIGNUP`, default off |

## What is decided by Kevin's words, and what this ticket proposes

**Decided (his goal):** parents sign up, create their own household, see only their own data.

**Proposed, for his ack:**

1. **A user belongs to exactly one household.** `HouseholdMember` stays one-to-one with `User` and
   gains a `household` FK and a `role`. Two households needing the same person is a case nobody has;
   modelling it costs a join on every request.
2. **Shared schema, `household_id` on every data table.** Not a schema per household (44 tables x N
   families is what Django migrations were invented to avoid) and not a database per household
   (the phone would need N `DATABASE_URL`s of knowledge it should not have).
3. **Enforcement is one Django choke point AND SQL.** `SyncedModelViewSet.get_queryset` is where
   every synced read and write already passes (`server/api/synced.py:423`); it filters on the
   request's household. Then Postgres RLS keyed on a session variable Django sets per request, so a
   missed `.filter()` in a future view leaks nothing (ticket 02b). This is CLAUDE.md's checklist rule
   applied: an integrity rule that must hold even if Django has a bug is SQL.
4. **Invite-only.** An owner mints a code. A code either joins the owner's household or, when minted
   with `creates_household`, lets its first user create a new one and later users join that one -
   so Kevin texts ONE code to both parents. Open signup exists as an env switch for a stranger's own
   compose stack and is off on Kevin's engine.
5. **Kevin hosts one engine for his own family.** CLAUDE.md §7's "nothing Kevin-hosted for anyone
   else" was written against a commercial broker; a parent on the same engine is the household
   widened, not a customer. Recorded in ADR 0045 in those words so it is not read as a loosening.

## What it costs

- One migration touching 44 tables (ticket 02), run once against the live Supabase Postgres.
  Reversible: the column has a default, dropping it is one statement.
- Every unique index on `origin_guid` (27 of them) becomes `(household_id, origin_guid)`, and
  `places`' label identity becomes `(household_id, label)` - two families both have "home".
- Every test that creates rows needs a household fixture. `tests/conftest.py` gains one; the suite
  is 589 tests and most go through the API client, so most pick it up from the request.

## Done means

Kevin says yes or amends. Then, in ONE commit: ADR 0045 flips `proposed` -> `accepted`, CLAUDE.md
§1 and §7 are edited to the "After" column above, `docs/adr/0044-*.md` gains a `superseded-by`
note for rule 3 only, and `memory/library/decisions.md` gets the dated entry. Tickets 02 and 03
become `ready`.
