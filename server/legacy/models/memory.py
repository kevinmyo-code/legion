"""The companion's memory: durable facts (`Memory`), a per-write/delete/
recall audit trail (`MemoryAudit`), the companion's own reflections about
the driver (`CompanionMemory`), and a raw conversation transcript audit
(`ConversationAudit`). CLAUDE.md section 4 rule 5: memory is safe to the
degree it is anchored to external, falsifiable reality - nothing about
that rule is enforced by these read-only mirrors, it lives in the writer.
"""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance


class Memory(models.Model):
    id = models.UUIDField(primary_key=True)
    text = models.TextField()
    logged_at = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "memories"


class MemoryAudit(models.Model):
    """`vehicle_id` here is a plain text label, not a foreign key to
    `legacy.models.fleet.Vehicle` - confirmed against `pg_constraint`
    (no FK constraint exists on this column at all), unlike every `vehicle_id`
    in `legacy/models/fleet.py`, which are real UUID foreign keys."""

    id = models.UUIDField(primary_key=True)
    event = models.TextField()  # CHECK: written | deleted | recall | recalled | spoken
    store = models.TextField()  # CHECK: memories | companion_memories | speech
    detail = models.TextField()
    ref_id = models.BigIntegerField(null=True)
    vehicle_id = models.TextField(null=True)
    logged_at = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "memory_audit"


class CompanionMemory(models.Model):
    """`vehicle_id` is text here too, for the same reason as `MemoryAudit`
    above - not a foreign key."""

    id = models.UUIDField(primary_key=True)
    vehicle_id = models.TextField()
    text = models.TextField()
    category = models.TextField()  # CHECK: car_anchored | driver | relationship
    source = models.TextField()  # CHECK: consolidated | reflection | stated
    importance = models.IntegerField()  # CHECK: 1-10, DB default 5
    logged_at = models.DateTimeField()
    last_accessed_at = models.DateTimeField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "companion_memories"


class ConversationAudit(models.Model):
    """No `provenance`, no `updated_at`, no `deleted_at`, no `origin_guid` -
    confirmed absent from the live schema. A raw turn-by-turn audit log,
    append-only like `ObdSample` in `legacy/models/fleet.py`."""

    id = models.UUIDField(primary_key=True)
    device_id = models.TextField()
    local_id = models.BigIntegerField()
    turn_seq = models.BigIntegerField()
    kind = models.TextField()  # CHECK: user | companion | tool_result
    tool_name = models.TextField()
    args = models.TextField()
    content = models.TextField()
    redacted = models.BooleanField()
    vehicle_id = models.TextField()
    recorded_at = models.DateTimeField()
    created_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "conversation_audit"
        unique_together = (("device_id", "local_id"),)
