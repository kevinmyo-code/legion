"""Mirror of `public.household_members`, the Supabase-Auth-era membership
table. Named `LegacyHouseholdMember`, not `HouseholdMember`, so it is never
confused with `household.models.HouseholdMember` in the `household` app -
that is a NEW, Django-owned table (`household_householdmember`) created by
ticket 01 for the same purpose under the new auth system. The two do not
share rows, a schema, or an id space yet; ticket 10 (cutover) is what is
expected to reconcile them, by setting the new `household.User` ids to the
old `auth.uid` values this table's `user_id` already points at.
"""
from __future__ import annotations

from django.db import models


class LegacyHouseholdMember(models.Model):
    """`user_id` is this table's own primary key (one row per member) and a
    foreign key to `auth.users(id) ON DELETE CASCADE` in the live schema
    (confirmed via `pg_constraint`, which resolves cross-schema regardless
    of grants) - Supabase Auth's own schema, not `public`. It is left as a
    plain `UUIDField`, not a `ForeignKey`, because `auth` is not a schema
    Django introspects or owns; `legion_reader` has no grant on it either
    (`information_schema.schemata` does not even list it for that role).
    """

    user_id = models.UUIDField(primary_key=True)
    label = models.TextField(null=True)
    added_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "household_members"
