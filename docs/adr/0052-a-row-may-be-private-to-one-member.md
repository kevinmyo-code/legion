---
status: accepted
decided: 2026-10-03
decided-by: Kevin
source: "[[decisions#2026-10-03 - web-revamp: one client, two surfaces, private rows, soft Material light]]"
tags: [adr]
---

# 52. A row may be private to one member

## Standing

ACCEPTED, not built (`.scratch/web-revamp/issues/06-private-rows-on-the-engine.md`). Amends
[[0045-households-are-tenants]]: a member sees everything **shared** in their household, plus their
own private rows. Tenancy is still by household; privacy is a filter inside it, not a second tenant.

## Context

ADR 0045 made the household the only boundary: every member saw every row. With Mia on the web,
that put Kevin's Canvas coursework and class schedule into her day. Kevin, 2026-10-03: *"canvas
and my class schedules should be only mine no? shared ones give it a distinct color like pink"*,
then chose server-enforced privacy over a client-side filter.

## Decision

- `events` and `checklists` carry a nullable `owner_user_id`. Null is shared; set is private to that
  user. Items, ticks and event skips inherit their parent's visibility.
- Enforced at the choke point: `household.tenancy.visible()` wraps `scoped()`, and every read and
  write path for those five tables goes through it, MCP included. Another member's private row is a
  404, the same shape as another household's.
- A row made private after a replica saw it reaches that replica as a **redacted tombstone**
  (`id`, `deleted_at`, `updated_at`, `redacted: true`, nothing else).
- On the wire it is `visibility: "shared" | "private"`. No user id ever goes on the wire.
- Imported Canvas rows are private to the member whose Canvas credential the poller used. Anything
  made by hand defaults to shared. Shared rows render with a pink marker and a word, never colour
  alone.
- A shared row may be made private only by its creator (`created_by_id`), or by anyone when the
  creator is unknown. Removing a member tombstones their private rows.

## Consequences

- Still no roles. Privacy is ownership of a row, not a permission level, so ADR 0045's "one role,
  `owner`, for membership only" stands.
- `test_tenancy.py` proves household isolation; `test_visibility.py` must prove member isolation
  inside one household. Neither substitutes for the other.
- The tenancy test's "no `household_id` on the wire" rule extends to `owner_user_id` and
  `created_by_id`.
- Postgres RLS (web-and-households 02b), when it lands, must cover `owner_user_id` too, or it is a
  belt with a hole in it.
