---
status: accepted
decided: 2026-11-02
decided-by: Kevin
source: "[[decisions#2026-11-02 - Each recruiter visit gets its own household; gated rows deletable only there (Kevin)]]"
tags: [adr]
---

# 69. Each recruiter visit gets its own household, and only there may a gated row be deleted

## Standing

**Every recruiter visit has its own throwaway copy of the demo household**, flagged
`Household.is_demo_visit`, and the visitor is its only member. The recruiter uses the normal web
app and assistant on that copy, writes included. **The database lets a gated money row be deleted
only when its household carries that flag**, so a visit can be deleted whole when it ends. This
amends [[0068-recruiter-role-narrows-only]] ("no writes by any credential") and narrows the scope of
the gated-row immutability [[0042-business-rules-live-in-postgres]] keeps in SQL; it does not change
what a gated row means anywhere else.

## Context

Kevin, 2026-11-02: *"everything i can do on the phone save the phone only functions i should be able
to do on the web legion page and by extension the recruiter on the demo mode"*, then option C: each
visitor gets a private copy. A shared read-only demo household cannot show writes, and writes in a
shared household would leak between visitors. A per-visit copy has to be cleaned up, and the
`forbid_mutation` triggers refuse to delete a verified statement, transaction, receipt or line item
anywhere (post a reversal instead), so without an exemption no visit could ever be removed. Kevin
approved the exemption the same day: *"ok"*.

## Decision

- **One household per visit.** `POST /api/demo/enter` creates `Demo visit <id>`, sets
  `is_demo_visit = true`, seeds it through the same gate and serializers as the shared demo household
  (`household.demo_seed.seed_household`), and makes the visitor its only member, role `recruiter`,
  all in one transaction. The shared "Demo" household is no longer joined by anyone.
- **The flag is set by that door and nothing else.** `editable=False`, named by no serializer, no
  admin form, no view. Kevin's household can never carry it.
- **The exemption is by household, checked last, DELETE only.** `public.gated_row_guard()`
  (statements, receipts, receipt line items) and `public.ledger_transactions_guard()` keep every
  existing rule (UPDATE refused everywhere; DELETE only for `UNRECONCILED`, and for the ledger
  ADR 0057's `BANK_API` and supersede cases) and add one last case: DELETE is allowed when the row's
  household has `is_demo_visit`. Shipped by `household/migrations/0008`, SQL in
  `household/demo_visit_sql.py`, nothing in `private`.
- **Prune deletes the visit whole, and nothing else.** `manage.py prune_demo_visitors` deletes every
  tenant-table row of an expired visit's household in foreign-key order, then the visitor, then the
  household, and refuses any household without the flag.
- **The recruiter fence moves, and still only narrows.** Routes: `household/recruiter_routes.py`
  names every guarded view as allowed, read-only or denied with a reason (outside accounts, devices,
  body, memory, settings, `/mcp` denied); a view in none of them is refused. Tools: the member web
  surface less `list_tables`, record tools narrowed to ledger, fleet, pantry, places and voice notes
  (body out, Kevin's ruling 3; voice notes in, ruling 4). A recruiter writes only inside a
  demo-visit household (`write_refusal`), and only through its browser session: the visitor token
  stays `read`.

## Consequences

- A bug that set the flag on a real household would make its gated rows deletable. The flag has one
  writer, the trigger reads it live, and `delete_visit_household` refuses an unflagged household, so
  that bug needs two independent mistakes. `tests/test_demo_visit.py` pins each fence.
- Seeding a visit runs the whole demo seed inside the request (measured about 0.2 s on a local
  Postgres with fsync off; Cloud Run will be slower).
- A recruiter made before this (a member of the shared household) is treated as an ended visit
  everywhere.
