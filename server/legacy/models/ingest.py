"""`ingested_files`: the one table `ledger` and `pantry` both hang their
statements/receipts off of (`ticket 02`'s table maps it to its own app for
that reason). This is also where ticket 03 (the gate in Python) will live
once it exists - nothing about the gate itself is built here."""
from __future__ import annotations

from django.db import models

from legacy.enums import IngestState


class IngestedFile(models.Model):
    id = models.UUIDField(primary_key=True)
    content_sha256 = models.TextField(unique=True)
    source_file_id = models.TextField(null=True)
    display_name = models.TextField(null=True)
    size_bytes = models.BigIntegerField(null=True)
    state = models.TextField(choices=IngestState.choices, default=IngestState.NEW)
    quarantine_reason = models.TextField(null=True)
    first_seen_at = models.DateTimeField()
    last_attempt_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "ingested_files"
