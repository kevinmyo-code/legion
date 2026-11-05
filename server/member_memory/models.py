"""`public.member_memories`: what the assistant remembers about a member (memory v2, ADR 0075).

Kevin, 2026-11-05: *"alfred's memory should stay on a centralized place, like the engine no?"*,
then *"yeah lets do it. centralized memory. and the id vault"*. One store on the engine that the
phone, the web assistant and the Claude worker on the Alienware all read and write.

**A memory is private to the member it is about** (ruling 2). `member` is who it is about and
never changes; `owner_user` is ADR 0052's visibility column, equal to `member` while private and
null when the memory is a shared household fact (an address, a car). A check constraint holds
`owner_user is null or owner_user = member`, so a memory can never be private to someone it is
not about, and the owner guard trigger (`household/visibility_sql.py`) holds that the owner is a
member of the row's household. Every read and write goes through `household.tenancy.visible()`
(`OWNER_PATHS["member_memories"]`), so another member's private memory is a 404 on every surface,
`/mcp` and the web assistant included.

**Forgetting is a hard delete** (ruling 4): the row is removed, with every older version it
superseded (`member_memory.service.forget`). Nothing is hidden, nothing tombstoned. That is why
there is no `deleted_at`, and why a phone cache refreshes from the whole list rather than a
`?since=` feed (map decision F).

**Supersede, do not overwrite** (map decision C). A newer fact that contradicts an older one
names it in `supersedes`; the older row gets `superseded_at` and leaves recall and the default
list, but stays until the newer one is forgotten.

The three legacy tables (`memories`, `companion_memories`, `memory_audit`, `api/memory.py`) are
untouched: they are the phone's replica and stay out of the generic record tools.

`memory_consolidations` is the consolidation job's ledger: one row per conversation record it
has read, so a record is distilled once.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

KIND_FACT = "fact"
KIND_PREFERENCE = "preference"
KIND_EVENT = "event"
KINDS: tuple[str, ...] = (KIND_FACT, KIND_PREFERENCE, KIND_EVENT)

DECAY_DURABLE = "durable"
DECAY_EPISODIC = "episodic"
DECAY_CLASSES: tuple[str, ...] = (DECAY_DURABLE, DECAY_EPISODIC)

SOURCE_PHONE = "phone"
SOURCE_WEB = "web"
SOURCE_CLAUDE = "claude"
SOURCE_MANUAL = "manual"
SOURCE_CONSOLIDATION = "consolidation"
SOURCES: tuple[str, ...] = (
    SOURCE_PHONE,
    SOURCE_WEB,
    SOURCE_CLAUDE,
    SOURCE_MANUAL,
    SOURCE_CONSOLIDATION,
)
# What a client may say it is when it writes over REST. The other three are the engine's own
# doors (the web assistant, `/mcp`, the consolidation job) and are set by the engine.
CLIENT_SOURCES: tuple[str, ...] = (SOURCE_PHONE, SOURCE_MANUAL)

TEXT_MAX_CHARS = 500
IMPORTANCE_MIN, IMPORTANCE_MAX = 1, 10
IMPORTANCE_DEFAULT = 5


class MemberMemory(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # Who it is about. CASCADE: a member's memories go with the member.
    member = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    # ADR 0052: equal to `member` while private, null when shared.
    owner_user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.CASCADE, related_name="+"
    )
    text = models.TextField()
    kind = models.TextField(default=KIND_FACT)
    importance = models.SmallIntegerField(default=IMPORTANCE_DEFAULT)
    decay_class = models.TextField(default=DECAY_DURABLE)
    source = models.TextField()
    # Who wrote it, when a person or a token did. Null for the consolidation job.
    created_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    supersedes = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL, related_name="superseded_by"
    )
    superseded_at = models.DateTimeField(null=True, blank=True)
    last_recalled_at = models.DateTimeField(null=True, blank=True)
    recall_count = models.IntegerField(default=0)
    # The conversation record a consolidated memory came from.
    source_log = models.ForeignKey(
        "devicelogs.DeviceLog", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    # Idempotency for the one-time import (`phone:<store>:<origin_guid>`).
    origin_key = models.TextField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "member_memories"
        indexes = [
            models.Index(fields=["household", "member"], name="member_memories_member_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_key"],
                name=household_unique_name("member_memories", ["origin_key"]),
            ),
            models.CheckConstraint(
                condition=models.Q(owner_user__isnull=True)
                | models.Q(owner_user=models.F("member")),
                name="member_memories_private_to_its_member",
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=KINDS), name="member_memories_kind_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(decay_class__in=DECAY_CLASSES),
                name="member_memories_decay_class_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(source__in=SOURCES), name="member_memories_source_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(importance__gte=IMPORTANCE_MIN)
                & models.Q(importance__lte=IMPORTANCE_MAX),
                name="member_memories_importance_range",
            ),
        ]

    def __str__(self) -> str:
        # Never the text: admin lists and log lines name the row, not what it says.
        return f"memory {self.id}"

    @property
    def shared(self) -> bool:
        return self.owner_user_id is None


OUTCOME_DONE = "done"
OUTCOME_FAILED = "failed"
OUTCOME_SKIPPED = "skipped"
OUTCOMES: tuple[str, ...] = (OUTCOME_DONE, OUTCOME_FAILED, OUTCOME_SKIPPED)
MAX_ATTEMPTS = 3


class MemoryConsolidation(models.Model):
    """One conversation record the consolidation job has read. `failed` with fewer than
    `MAX_ATTEMPTS` attempts is retried on the next run; anything else is never read again."""

    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    device_log = models.OneToOneField(
        "devicelogs.DeviceLog", on_delete=models.CASCADE, related_name="+"
    )
    outcome = models.TextField()
    attempts = models.IntegerField(default=1)
    memories_written = models.IntegerField(default=0)
    # Why it failed or was skipped, in words. Never transcript text.
    detail = models.TextField(blank=True, default="")
    processed_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "memory_consolidations"
        constraints = [
            models.CheckConstraint(
                condition=models.Q(outcome__in=OUTCOMES), name="memory_consolidations_outcome_valid"
            ),
        ]
