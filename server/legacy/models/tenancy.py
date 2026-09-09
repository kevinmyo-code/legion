"""The `household_id` column, declared once for forty `managed = False`
models.

ADR 0045: every data row belongs to exactly one household. On the legacy
tables that is a `household_id uuid NOT NULL` column added by
`household/migrations/0002_households_are_tenants.py`; this is the ORM's
side of it, so a query can say `filter(household=...)` instead of
`filter(household_id=...)` and so a write through `SyncedSerializer` can
assign the object rather than a raw uuid.

**A factory rather than a module-level field instance.** A Django `Field`
object binds to the model it is declared on (`contribute_to_class` sets
`field.model`), so ONE instance shared across forty models would attach to
whichever imported last and silently mis-resolve everywhere else. Each call
returns a fresh field.

Three choices in it, each load-bearing:

- **`db_column="household_id"`** because these are `inspectdb`-shaped
  mirrors of tables Supabase's own migrations own; Django's default column
  name for a field called `household` on an unmanaged model would be
  `household_id` anyway, but naming it is what makes that a fact rather
  than a coincidence a future rename could break.
- **`on_delete=models.DO_NOTHING`** because `managed = False` means Django
  emits no DDL for these tables and therefore no ON DELETE clause either -
  the real referential action is whatever the SQL migration declared
  (`REFERENCES household_household(id)`, i.e. RESTRICT). Django's own
  CASCADE would be a SECOND, application-level deletion policy running
  beside the database's, and deleting a household would then have Django
  walk forty tables issuing DELETEs. Nothing in this app deletes a
  household; if ticket 03 ever does, it is a deliberate, written decision
  and not a side effect of a field default.
- **`related_name="+"`** because forty reverse accessors named
  `household.bodyweightlog_set` on one model is noise nothing reads, and
  two of these models would collide on a default related name.
"""
from __future__ import annotations

from django.db import models

from household.tenancy_sql import household_unique_name


def household_field() -> models.ForeignKey:
    return models.ForeignKey(
        "household.Household",
        db_column="household_id",
        on_delete=models.DO_NOTHING,
        related_name="+",
    )


def household_unique(table: str, *columns: str) -> models.UniqueConstraint:
    """A unique key that was per-server and is now per-household (ADR 0045).

    Declared on `managed = False` models, so this emits no DDL - the real
    index is created by `household/tenancy_sql.py`, which reads the OLD key
    off the catalog, drops it and rebuilds it with `household_id` prepended.
    Both sides call `household_unique_name` for the name, so the model file
    and the database cannot end up calling one constraint two things.

    **What this replaces, and why it is not merely documentation.** Each of
    these columns used to carry `unique=True` on the field itself. Left
    there, DRF's `ModelSerializer` builds a `UniqueValidator` over
    `Model.objects.all()` - the whole table, every household - and household
    B's first `PUT` of an `origin_guid` household A had already used would be
    refused with "this field must be unique" by a check that had no business
    looking at A's rows at all. A `UniqueConstraint` naming `household`
    instead generates no validator, because DRF skips a unique-together
    validator whose fields are not all on the serializer and `household`
    never is (`api/synced.py`: the column is never on the wire). A genuine
    collision inside ONE household still fails, at the database, and
    `api/sync.save_or_400` turns that into a 400 quoting what Postgres said.
    """
    return models.UniqueConstraint(
        fields=["household", *columns],
        name=household_unique_name(table, columns),
    )
