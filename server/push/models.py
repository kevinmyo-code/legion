"""Web Push for the household (web-revamp ticket 15, spec D7).

Three tables, all born tenanted like `ingest_runs` (`household` is an
ordinary foreign key in their first migration) and all in `public`, so they
sit in `household.tenancy.TENANT_TABLES` and the tenancy tests hold them to
it. Every unique key leads with `household`, so the tenancy re-keying has
nothing to change on them.

- `PushSubscription`: one browser that asked to be told things. The keys are
  the browser's public encryption keys, not secrets of ours; they are never
  served back.
- `PushPreference`: which kinds a person wants, one row per person.
- `PushSent`: the dedupe ledger `push_dispatch` checks before it sends, so a
  thing is said once (spec D7: unique on user, kind and key).
"""

from __future__ import annotations

import datetime
import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

LIST_CHANGES = "list_changes"
EVENT_REMINDERS = "event_reminders"
TASK_DUE_MORNING = "task_due_morning"
KINDS: tuple[str, ...] = (LIST_CHANGES, EVENT_REMINDERS, TASK_DUE_MORNING)
DEFAULT_MORNING_TIME = datetime.time(7, 30)


class PushSubscription(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    endpoint = models.TextField()
    p256dh = models.TextField()
    auth = models.TextField()
    user_agent = models.TextField(blank=True, default="")
    # The browser's IANA zone: which local day "this morning" and "today"
    # mean for this device. Never handed to a model (CLAUDE.md section 1 is
    # about prompts); it only picks a calendar day here.
    tz = models.TextField(default="UTC")
    created_at = models.DateTimeField(db_default=Now())
    last_ok_at = models.DateTimeField(null=True, blank=True)
    failure_count = models.IntegerField(default=0)

    class Meta:
        db_table = "push_subscriptions"
        constraints = [
            models.UniqueConstraint(
                fields=["household", "endpoint"],
                name=household_unique_name("push_subscriptions", ["endpoint"]),
            ),
        ]


class PushPreference(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    list_changes = models.BooleanField(default=True)
    event_reminders = models.BooleanField(default=True)
    task_due_morning = models.BooleanField(default=True)
    morning_time = models.TimeField(default=DEFAULT_MORNING_TIME)
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "push_preferences"
        constraints = [
            models.UniqueConstraint(
                fields=["household", "user"],
                name=household_unique_name("push_preferences", ["user_id"]),
            ),
        ]


class PushSent(models.Model):
    """One thing said (or decided to be said) to one person. `delivered` is
    false for a morning with nothing due: the day is decided, so a task added
    at 2pm never produces a "morning" message, and nothing reached a phone."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    kind = models.TextField()
    key = models.TextField()
    # For a list batch, the newest item it covered: the next batch for the
    # same list and creator starts after it.
    watermark = models.DateTimeField(null=True, blank=True)
    delivered = models.BooleanField(default=True)
    sent_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "push_sent"
        constraints = [
            models.UniqueConstraint(
                fields=["household", "user", "kind", "key"],
                name=household_unique_name("push_sent", ["user_id", "kind", "key"]),
            ),
            models.CheckConstraint(condition=models.Q(kind__in=KINDS), name="push_sent_kind_valid"),
        ]
