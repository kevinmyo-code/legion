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

**The web assistant's conversations are rows here too** (2026-11-03, Kevin: "recorded to
improve ... and seeing what tool calls etc went wrong"; `devicelogs/web_conversation.py`). One
row per conversation, `device_label` "web", reason `conversation`, `batch_id` the conversation id
the engine handed out at the mint, and the body in the phone's LIVE_AUDIT / LIVE_LATENCY format, so
`manage.py device_logs --reason conversation --render` reads it like a phone's batch.

**A recruiter's visit conversation has NO household** (`demo_visitor`): the visit's household is
deleted whole when the visit ends (`demo/visit_cleanup.py` deletes every tenant-table row by
`household_id`), and Kevin wants these kept 30 days for improving the assistant. The visit is named
by `visit` (SET_NULL: the `DemoVisit` row outlives its household anyway) and `visit_ref`, copied,
and `manage.py prune_demo_visitors` deletes these rows after `VISIT_RETENTION_DAYS`. A NULL
household is visible to no household: every tenant read filters on one.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

REASON_DRIVE_END = "drive_end"
REASON_STARTUP = "startup"
REASON_MANUAL = "manual"
# Every few minutes while the service runs (2026-10-24). The ignition is cut the moment the car
# stops, so a drive-end upload never got the chance to run; this one does not wait for an end.
REASON_PERIODIC = "periodic"
# A conversation batch (2026-10-28, Kevin: "all logs on engine. both headunit and phone"): only
# LIVE_AUDIT and LIVE_LATENCY lines from a device's conversation spool, whatever triggered the
# upload. The reason names the content, so `manage.py device_logs --reason conversation --render`
# reads a conversation without a drive log mixed in.
REASON_CONVERSATION = "conversation"
# The phone's own logcat (2026-11-09, Kevin: "Send everything from the phone to the engine so we can
# audit without adb"): every line the app's process wrote, whatever the tag, redacted on the phone
# before it leaves (`service/phonelog/LogcatRedactor.kt`).
REASON_LOGCAT = "logcat"
# What the phone knows about how its process died: the stack of an uncaught exception, written by
# the dying process, and Android's own exit records (ANR traces, low-memory kills) read at the next
# start.
REASON_CRASH = "crash"
REASONS: tuple[str, ...] = (
    REASON_DRIVE_END,
    REASON_STARTUP,
    REASON_MANUAL,
    REASON_PERIODIC,
    REASON_CONVERSATION,
    REASON_LOGCAT,
    REASON_CRASH,
)

# One upload is refused above this, compressed or decompressed (the second is
# the gzip-bomb guard). The head unit seals batches at about 1 MB, well under.
MAX_UPLOAD_BYTES = 5 * 1024 * 1024
# A tighter decompressed cap for the phone's own logs: it seals a logcat batch at 1 MB, so anything
# much bigger is a phone bug, refused in words rather than stored.
MAX_RAW_BYTES_BY_REASON: dict[str, int] = {
    REASON_LOGCAT: 2 * 1024 * 1024,
    REASON_CRASH: 2 * 1024 * 1024,
}
# Rows older than this are deleted when their household next uploads.
RETENTION_DAYS = 60
# Per-reason exceptions to RETENTION_DAYS (2026-11-09). A raw logcat batch is the bulk of what a
# phone sends and is read within days of a problem, so it goes at 30; conversation and the head
# unit's reasons stay at 60, and a crash batch (small, and the rarest thing to have) at 60 too.
RETENTION_DAYS_BY_REASON: dict[str, int] = {
    REASON_LOGCAT: 30,
}


def retention_days(reason: str) -> int:
    """How long a batch of this reason is kept, in days."""
    return RETENTION_DAYS_BY_REASON.get(reason, RETENTION_DAYS)


def max_raw_bytes(reason: str) -> int:
    """The decompressed cap for one upload of this reason."""
    return MAX_RAW_BYTES_BY_REASON.get(reason, MAX_UPLOAD_BYTES)


# The phone's NAV_AUDIT lines (navigation search, routes, trips) are kept this long (Kevin,
# 2026-11-06, nav-quality decision D7). They ride in `conversation` batches next to LIVE_AUDIT, so a
# batch holding any is not deleted at its reason's retention: its other lines are dropped then and
# only the NAV_AUDIT lines stay, until this many days. A batch with none goes as before.
NAV_AUDIT_RETENTION_DAYS = 90
# A recruiter visit's conversation records (`demo_visitor`): deleted by the hourly
# `manage.py prune_demo_visitors` this long after they arrived, whether or not the visit ended.
VISIT_RETENTION_DAYS = 30
# What the web assistant's conversation rows are labelled.
DEVICE_LABEL_WEB = "web"


class DeviceLog(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # Null only on a recruiter visit's conversation (`demo_visitor`), never otherwise.
    household = models.ForeignKey(
        "household.Household", null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
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
    # A recruiter's demo-visit conversation: no household, kept VISIT_RETENTION_DAYS.
    demo_visitor = models.BooleanField(default=False)
    visit = models.ForeignKey(
        "demo.DemoVisit", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    # Copied from the visit (`/try?ref=acme`), so it outlives the visit row's links.
    visit_ref = models.CharField(max_length=64, blank=True, default="")
    # Web conversations only: server clock minus the browser's, measured at the mint, so the
    # browser's transcript lines and the engine's tool lines share one clock.
    clock_offset_ms = models.BigIntegerField(null=True, blank=True)
    # The phone's process run (2026-11-09): one id per app process start, sent on its logcat, crash
    # and conversation batches alike, so a conversation record joins to the raw lines around it.
    # Blank for anything that sends none (the head unit, the web, an older phone).
    run_id = models.CharField(max_length=64, blank=True, default="")
    # How many NAV_AUDIT lines the body holds, counted at upload. Above zero, the row is kept
    # NAV_AUDIT_RETENTION_DAYS; past its reason's retention its body is cut to these lines (then
    # line_count == nav_audit_lines, so it is cut once).
    nav_audit_lines = models.IntegerField(default=0)

    class Meta:
        db_table = "device_logs"
        indexes = [models.Index(fields=["household", "-received_at"], name="device_logs_household_recv")]
        constraints = [
            models.UniqueConstraint(
                fields=("household", "batch_id"), name="device_logs_household_batch_id_uniq"
            ),
            # Visit rows have no household, and Postgres treats NULLs as distinct.
            models.UniqueConstraint(
                fields=("batch_id",),
                condition=models.Q(household__isnull=True),
                name="device_logs_visit_batch_id_uniq",
            ),
            models.CheckConstraint(
                condition=models.Q(reason__in=REASONS), name="device_logs_reason_valid"
            ),
            # A household, or a recruiter visit's conversation: exactly one.
            models.CheckConstraint(
                condition=(
                    models.Q(demo_visitor=False, household__isnull=False)
                    | models.Q(demo_visitor=True, household__isnull=True)
                ),
                name="device_logs_household_or_visit",
            ),
        ]
