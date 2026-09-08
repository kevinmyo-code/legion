---
map: web-and-households
ticket: "02"
title: "household_id on every data table, backfilled, and one Django choke point"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-households-are-tenants]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# household_id on every data table

## Models (`server/household/models.py`)

```python
class Household(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=120)
    created_at = models.DateTimeField(auto_now_add=True)
    created_by = models.ForeignKey(User, null=True, on_delete=models.SET_NULL, related_name="+")

class HouseholdMember(models.Model):
    OWNER, MEMBER = "owner", "member"
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name="household_member")
    household = models.ForeignKey(Household, on_delete=models.CASCADE, related_name="members")
    role = models.CharField(max_length=8, choices=[(OWNER, "owner"), (MEMBER, "member")], default=MEMBER)
    joined_at = models.DateTimeField(auto_now_add=True)
```

`request.user.household_member.household_id` is the scope for every request. Add
`User.household` as a cached property returning it or `None`.

## The migration (`server/household/migrations/00XX_households.py`)

One migration, in this order, each step its own operation so a failure names the step:

1. `CreateModel Household`; insert Kevin's household with a FIXED uuid taken from an env var
   `LEGION_BOOTSTRAP_HOUSEHOLD_ID` (so the live run and a fresh clone get a value the operator
   chose, never a random one nobody wrote down). Name from `LEGION_BOOTSTRAP_HOUSEHOLD_NAME`,
   default `"Home"`.
2. Add `household` + `role` to `HouseholdMember`, defaulting to that household and `owner` for the
   existing two rows; then drop the defaults.
3. `RunSQL` over the 44 `public.*` tables (the checklists app's own migration is the precedent for
   the search_path swap): `ALTER TABLE public.<t> ADD COLUMN household_id uuid NOT NULL DEFAULT
   '<id>' REFERENCES household_household(id)`, then `ALTER COLUMN household_id DROP DEFAULT`, then
   `CREATE INDEX <t>_household_idx ON public.<t>(household_id)`. The table list is imported from
   ONE Python constant, `household.tenancy.TENANT_TABLES`, which `api/registry.py` and the tests
   also read - three copies of a 44-name list is three chances to miss one.
4. The 27 `origin_guid` unique indexes: drop each, recreate as `(household_id, origin_guid)`. Read
   the live names from `pg_indexes` in the migration rather than hardcoding them; the header
   comments in `supabase/migrations/` have been wrong about applied state before.
5. `places`: whatever uniqueness exists on `label` becomes `(household_id, label)`.
6. `obd_samples_natural_key_idx` and `maintenance_schedules`' composite key are vehicle-scoped and
   stay as they are; add `household_id` anyway for uniform RLS in 02b.

`managed=False` legacy models gain `household = models.ForeignKey("household.Household",
db_column="household_id", on_delete=models.DO_NOTHING)` so the ORM knows the column. The
`tests/legacy_test_schema.py` DDL mirror gains the column on every table, or the tests prove nothing.

## The choke point (`server/api/synced.py`)

- `SyncedModelViewSet.get_queryset()` (line ~423 today) and `_lookup()` (line ~395) filter
  `household=request.user.household_member.household`. There is no other read path in the
  generic viewset; if one appears, it is a defect.
- `SyncedSerializer.create()` sets `household` from the request; the field is never on the wire and
  never accepted from a client.
- `api/changes.py`, `api/events.py`, `checklists/views.py`, `ingest/views.py` (the gate writes
  `statements`/`receipts`/`ingested_files`), and the three literal `obd_samples` routes each get the
  same filter/assignment. Grep target: every `objects.` in `server/api`, `server/checklists`,
  `server/ingest` - list them in the report with what changed.
- Admin: `ModelAdmin.get_queryset` scoped for non-superusers; superuser sees all.

## Tests

- `tests/test_tenancy.py`: for EVERY entry in `TENANT_TABLES`, create a row in household A and a
  row in household B, and assert the B token's list, detail, changes feed and (where the route
  exists) PUT/DELETE never see A's row. Generated from the registry so a new table cannot be
  forgotten - the same posture as `test_synced_contract.py`.
- A test that `TENANT_TABLES` equals the set of `public` tables with a `household_id` column, read
  from `information_schema` on the test database.
- `conftest.py`: `household_a`, `household_b`, `token_a`, `token_b` fixtures. Existing tests that
  build a user and token get a household from a fixture default, not a per-test edit.

## Done means

`cd server && uv run pytest`: every pre-existing test green, the new tenancy suite green, 0
skipped beyond the LEGION_PG_URL-gated legacy set. Migration applied to the live database - Supabase today, the VM's Postgres once ticket 13 has
moved it; the dump carries whichever state it finds - with row counts per table unchanged before
and after (the query is in the report). `openapi.yaml`
regenerated and its staleness test green. `household_id` appears on no response body.
