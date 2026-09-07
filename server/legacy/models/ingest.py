"""`ingested_files`: the one table `ledger` and `pantry` both hang their
statements/receipts off of (`ticket 02`'s table maps it to its own app for
that reason).

This module's doc used to say "this is also where ticket 03 (the gate in
Python) will live once it exists". It does not: the gate landed in its own
`ingest` app (`ingest/gate.py`, `ingest/dedup.py`, `ingest/views.py`), which
keeps the arithmetic in a module with no ORM in it at all. This file is
still only the table mirror - nothing about the gate is built here.

`ingested_files` is the ONE table in this app that a gated write updates
rather than only inserting: `private.forbid_mutation_of_facts` is attached
to `statements`, `ledger_transactions`, `receipts` and `receipt_line_items`
and to nothing else, so the state/reason upsert the commit paths do is
allowed here and would be refused on any of those four.
"""
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
