"""`public.open_loops`, `public.open_loop_runs`, `public.open_loop_settings` (ADR 0085,
`.scratch/open-loops/`).

Kevin, 2026-11-10 ("5 yes", then "sure. ask first."): an **open loop** is something he said he
would do and never closed ("I need to call the dentist"). The nightly memory review (ADR 0067,
the Claude worker on his Alienware) reads his own words and flags them; Alfred **proposes** each
one as a question about tracking it, and only his yes makes it a task.

- **A proposal is private to the member whose words it came from** (ADR 0052): `owner_user` is
  never null, so `household.tenancy.visible()` hands each member their own proposals and nobody
  else's, and the owner guard trigger (`household/visibility_sql.py`) refuses an owner from
  outside the row's household. All three tables are born tenanted and listed in `TENANT_TABLES`.
- **Nothing is tracked without his yes.** `status` starts `pending`; `tracked` only through
  `service.track` (his tap or his voice, never a Claude worker's token); `dismissed` is for good.
- **One row per commitment, forever.** `dedupe_key` is the commitment's content words, sorted
  (`gate.dedupe_key`), unique per member, so an answered one is never proposed again however the
  helper words it next time, as long as the words are his (the gate keeps a proposal to words he
  said).
- **No time on the wire.** `created_at`, `expires_at` and `offered_count` are the engine's own
  bookkeeping and never served, so no surface can say how long ago he said it (compulsion test
  clause c, by construction).
- **`open_loop_runs` is the ledger** of which conversation records were offered for extraction.
  A record a submitted run read is never offered again, so a reworded proposal of the same words
  cannot come back from the same conversation on a later night.
"""

from __future__ import annotations

import uuid

from django.conf import settings
from django.db import models
from django.db.models.functions import Now

STATUS_PENDING = "pending"
STATUS_TRACKED = "tracked"
STATUS_DISMISSED = "dismissed"
STATUSES: tuple[str, ...] = (STATUS_PENDING, STATUS_TRACKED, STATUS_DISMISSED)

# What a yes made: a dated reminder (`public.events`, kind `reminder`, as "remind me to X on
# Friday" makes) or an undated line on his Todo checklist (as a spoken plain to-do lands), or
# nothing new because he had already tracked it himself (`existing`).
TASK_REMINDER = "reminder"
TASK_TODO = "todo"
TASK_EXISTING = "existing"
TASK_KINDS: tuple[str, ...] = (TASK_REMINDER, TASK_TODO, TASK_EXISTING)

TITLE_MAX_CHARS = 120
REASON_MAX_CHARS = 200


class OpenLoop(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # Never null: a proposal is always one member's (ADR 0052, OWNER_PATHS).
    owner_user = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+"
    )
    # The task, in his own words (the gate refuses a word he did not say).
    title = models.TextField()
    # Only a calendar date his own words print (`gate.stated_date`); never inferred.
    due_date = models.DateField(null=True, blank=True)
    status = models.TextField(default=STATUS_PENDING)
    dedupe_key = models.CharField(max_length=64)
    # A paraphrase in the helper's words (never a quote; the gate drops one that quotes him) and
    # the conversation records it came from (CLAUDE.md section 4 rule 8: keep the evidence).
    reason = models.TextField(blank=True, default="")
    evidence_logs = models.JSONField(default=list)
    review_context = models.UUIDField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    # Unanswered past this, it is quietly gone from every surface (no job writes it).
    expires_at = models.DateTimeField()
    # How many spoken briefs carried it (`service.offer`); never served.
    offered_count = models.IntegerField(default=0)
    decided_at = models.DateTimeField(null=True, blank=True)
    decided_by = models.ForeignKey(
        settings.AUTH_USER_MODEL, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    task_kind = models.TextField(blank=True, default="")
    # The events row or checklist item a yes made. Not a foreign key: `events` is a legacy
    # (`managed = False`) table and a checklist item may be deleted by hand later.
    task_id = models.UUIDField(null=True, blank=True)

    class Meta:
        db_table = "open_loops"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "owner_user", "dedupe_key"),
                name="open_loops_household_owner_user_id_dedupe_key_uniq",
            ),
            models.CheckConstraint(
                condition=models.Q(status__in=STATUSES), name="open_loops_status_check"
            ),
        ]
        indexes = [
            models.Index(fields=["household", "owner_user", "status"], name="open_loops_owner_idx"),
        ]


class OpenLoopRun(models.Model):
    """One review context's offer of his own words: which records, under which W labels."""

    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+"
    )
    context_id = models.UUIDField(unique=True)
    # W label -> DeviceLog id, as offered.
    labels = models.JSONField(default=dict)
    created_at = models.DateTimeField(db_default=Now())
    # Set by a real (not dry-run) submit; only then do its records count as read.
    submitted_at = models.DateTimeField(null=True, blank=True)
    # Records a submitted run read, minus those whose proposal waited for a later night's cap.
    read_logs = models.JSONField(default=list)
    proposed = models.IntegerField(default=0)
    refused = models.IntegerField(default=0)

    class Meta:
        db_table = "open_loop_runs"
        indexes = [
            models.Index(fields=["household", "owner_user", "submitted_at"],
                         name="open_loop_runs_owner_idx"),
        ]


class OpenLoopSettings(models.Model):
    """His one switch: "stop proposing tasks" (compulsion test clause d). Missing row = on."""

    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+"
    )
    proposing = models.BooleanField(default=True)
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "open_loop_settings"
        constraints = [
            models.UniqueConstraint(
                fields=("household", "owner_user"),
                name="open_loop_settings_household_owner_user_id_uniq",
            ),
        ]
