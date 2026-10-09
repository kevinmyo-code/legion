"""Tagged places. One table, kept as-is per the 2026-07-31 carry-over
ruling (CLAUDE.md section 1)."""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.tenancy import household_field, household_unique


class Place(models.Model):
    """No `origin_guid` - confirmed absent from the live schema, unlike
    almost every other table in this app."""

    id = models.UUIDField(primary_key=True)
    label = models.TextField()
    latitude = models.FloatField()
    longitude = models.FloatField()
    # The human address (2026-10-09). Added by `ingest/migrations/0017`
    # through `api/place_columns.py`; null on every place saved before it.
    address = models.TextField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "places"
        constraints = [
            household_unique("places", "label"),
        ]
