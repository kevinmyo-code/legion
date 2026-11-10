"""`public.watches` and `public.watch_firings`: standing watches (ADR 0083, `.scratch/watches/`).

Kevin, 2026-11-10 ("1 and 2 yes"): *"Alfred, tell me when my paycheck lands / a charge goes over
$X / a grade posts"*, and the things the engine already knows (bank feed, Canvas, car service due)
reaching the phone on their own. One mechanism serves both: a **watch** is a TYPED kind from the
closed registry in `watches/registry.py` with typed params, evaluated deterministically by
`manage.py evaluate_watches`; the "engine signals" are simply built-in watches (`builtin`).
**No model ever decides whether a watch fires** (proactive-mode decisions 8 and 11).

**A watch is private to the member who set it** (ADR 0052): `owner_user` is never null, so
`household.tenancy.visible()` hands each member their own watches and firings and nobody else's,
and the owner guard trigger (`household/visibility_sql.py`) refuses an owner from outside the
row's household. Both tables are born tenanted and listed in `TENANT_TABLES`.

**A firing is one fact, said once.** `fact_key` names the fact (a bank transaction, a Canvas
item at its due time, a service item at its due date), unique per member, so two watches that
match the same charge produce one firing, and the job running again produces none. `facts` is
the sentence the engine wrote from the rows (the model only words the line on the phone);
`sources` names the rows it came from, so every firing is anchored to a record Kevin can look up
(CLAUDE.md section 7 clause a). The phone acks a firing once it was said, posted or silenced;
`baseline` rows are facts that were already true when a watch was armed and are never said.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

KIND_CHARGE_OVER = "charge_over"
KIND_DEPOSIT_OVER = "deposit_over"
KIND_BALANCE_UNDER = "balance_under"
KIND_CANVAS_DUE = "canvas_due"
KIND_GRADE_POSTED = "grade_posted"
KIND_SERVICE_DUE = "service_due"
# Kevin, 2026-11-10: "notify me whenever a company i applied to emails me back". Mail never reaches
# the engine (CLAUDE.md section 7), so this kind is evaluated ON THE PHONE (`registry.PHONE`); the
# engine stores only his own list of companies and never a firing for it.
KIND_MAIL_REPLY = "mail_reply"
KINDS: tuple[str, ...] = (
    KIND_CHARGE_OVER,
    KIND_DEPOSIT_OVER,
    KIND_BALANCE_UNDER,
    KIND_CANVAS_DUE,
    KIND_GRADE_POSTED,
    KIND_SERVICE_DUE,
    KIND_MAIL_REPLY,
)

SOURCE_VOICE = "voice"
SOURCE_SCREEN = "screen"
SOURCE_BUILTIN = "builtin"
SOURCES: tuple[str, ...] = (SOURCE_VOICE, SOURCE_SCREEN, SOURCE_BUILTIN)

OUTCOME_SPOKEN = "spoken"
OUTCOME_NOTIFIED = "notified"
OUTCOME_SILENCED = "silenced"
OUTCOMES: tuple[str, ...] = (OUTCOME_SPOKEN, OUTCOME_NOTIFIED, OUTCOME_SILENCED)

LABEL_MAX_CHARS = 120


class Watch(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    kind = models.TextField()
    params = models.JSONField(default=dict)
    # What the watch is called on the screen and in "why did you say that?". Written by the
    # registry from the kind and params when nobody named it.
    label = models.TextField()
    # False: one-shot, spent after its first firing (`spent_at`).
    repeat = models.BooleanField(default=True)
    paused = models.BooleanField(default=False)
    expires_at = models.DateTimeField(null=True, blank=True)
    # The built-in it is (an engine signal), or null for one the member set.
    builtin = models.TextField(null=True, blank=True)
    source = models.TextField(default=SOURCE_SCREEN)
    # Facts that arrived before this moment never fire (no backfill). Reset on resume.
    armed_at = models.DateTimeField(db_default=Now())
    # grade_posted: when the grades already on file were recorded as baseline.
    baselined_at = models.DateTimeField(null=True, blank=True)
    # balance_under: which accounts are already under, so it fires on the crossing only.
    state = models.JSONField(default=dict)
    spent_at = models.DateTimeField(null=True, blank=True)
    last_fired_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    # A removed watch is kept (its firings point at it) and never evaluated; a removed built-in
    # is never seeded again.
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "watches"
        indexes = [models.Index(fields=["household", "owner_user"], name="watches_owner_idx")]
        constraints = [
            models.CheckConstraint(condition=models.Q(kind__in=KINDS), name="watches_kind_valid"),
            models.CheckConstraint(
                condition=models.Q(source__in=SOURCES), name="watches_source_valid"
            ),
            models.UniqueConstraint(
                fields=["household", "owner_user", "builtin"],
                condition=models.Q(builtin__isnull=False),
                name=household_unique_name("watches", ["owner_user_id", "builtin"]),
            ),
        ]


class WatchFiring(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    watch = models.ForeignKey(Watch, on_delete=models.CASCADE, related_name="firings")
    kind = models.TextField()
    fact_key = models.TextField()
    facts = models.TextField()
    # Structured figures the sentence was written from (money in int cents).
    detail = models.JSONField(default=dict)
    # [{"table": ..., "id": ...}]: the rows this fact came from.
    sources = models.JSONField(default=list)
    baseline = models.BooleanField(default=False)
    fired_at = models.DateTimeField(db_default=Now())
    # After this an unsaid firing is no longer said (a due time passed); it stays as history.
    expires_at = models.DateTimeField()
    acked_at = models.DateTimeField(null=True, blank=True)
    outcome = models.TextField(null=True, blank=True)

    class Meta:
        db_table = "watch_firings"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "acked_at"], name="watch_firings_owner_idx"
            )
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "owner_user", "fact_key"],
                name=household_unique_name("watch_firings", ["owner_user_id", "fact_key"]),
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=KINDS), name="watch_firings_kind_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(outcome__isnull=True) | models.Q(outcome__in=OUTCOMES),
                name="watch_firings_outcome_valid",
            ),
        ]
