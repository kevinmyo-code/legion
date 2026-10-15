"""`public.device_logs`: logs a device uploads about itself (Kevin, 2026-10-15).

The head unit has no ADB and Crashlytics breadcrumbs only ride along with a
crash, so after every drive the head unit uploads its own rolling log here and
Claude reads it with `manage.py device_logs`. One row per uploaded batch.

Born tenanted, in `public`, like `push_*` and `purchases`: `household` is an
ordinary foreign key, the table is in `household.tenancy.TENANT_TABLES`, and
every read goes through the request's household (`household_of`).

**The body is stored gzipped, as received** (`body_gz`), because a log is read
rarely and written often; decompressing happens in `manage.py device_logs`.
`raw_bytes` is the decompressed size, kept so a listing can say how big a batch
is without inflating it.

**The window timestamps are the DEVICE's clock, which is untrusted**: the head
unit's clock has been seen resetting itself. `received_at` is the database
clock and is what retention and ordering use.

**`batch_id` is the client's idempotency key.** An upload whose 2xx was lost on
the way back is retried with the same id and answered 200 without a second row.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

REASON_DRIVE_END = "drive_end"
REASON_STARTUP = "startup"
REASON_MANUAL = "manual"
REASONS: tuple[str, ...] = (REASON_DRIVE_END, REASON_STARTUP, REASON_MANUAL)

# One upload is refused above this, compressed or decompressed (the second is
# the gzip-bomb guard). The head unit seals batches at about 1 MB, well under.
MAX_UPLOAD_BYTES = 5 * 1024 * 1024
# Rows older than this are deleted when their household next uploads.
RETENTION_DAYS = 60


class DeviceLog(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The member whose token uploaded it. SET_NULL: a log outlives its account.
    user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    batch_id = models.UUIDField()
    device_label = models.TextField()
    app_version_name = models.TextField(blank=True, default="")
    app_version_code = models.IntegerField(null=True, blank=True)
    reason = models.TextField()
    # The device's clock. Null when the device sent none.
    window_start = models.DateTimeField(null=True, blank=True)
    window_end = models.DateTimeField(null=True, blank=True)
    line_count = models.IntegerField(default=0)
    raw_bytes = models.IntegerField()
    body_gz = models.BinaryField()
    received_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "device_logs"
        indexes = [models.Index(fields=["household", "-received_at"], name="device_logs_household_recv")]
        constraints = [
            models.UniqueConstraint(
                fields=("household", "batch_id"), name="device_logs_household_batch_id_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(reason__in=REASONS), name="device_logs_reason_valid"
            ),
        ]
