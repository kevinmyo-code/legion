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
# memory-review ticket 05: a row the daily memory review wrote (a correction). Never a client's.
SOURCE_REVIEW = "review"
SOURCES: tuple[str, ...] = (
    SOURCE_PHONE,
    SOURCE_WEB,
    SOURCE_CLAUDE,
    SOURCE_MANUAL,
    SOURCE_CONSOLIDATION,
    SOURCE_REVIEW,
)
# What a client may say it is when it writes over REST. The other three are the engine's own
# doors (the web assistant, `/mcp`, the consolidation job) and are set by the engine.
CLIENT_SOURCES: tuple[str, ...] = (SOURCE_PHONE, SOURCE_MANUAL)

# Why a row left recall through the memory review (memory-review ticket 05). Null for a row a
# newer fact superseded the ordinary way, and for every live row.
RETIRED_MERGED = "merged"
RETIRED_CONTRADICTED = "contradicted"
RETIRED_EXPIRED = "expired"
RETIRED_RELABELLED = "relabelled"
RETIRED_NOT_ABOUT_PERSON = "not_about_person"
RETIRED_REASONS: tuple[str, ...] = (
    RETIRED_MERGED,
    RETIRED_CONTRADICTED,
    RETIRED_EXPIRED,
    RETIRED_RELABELLED,
    RETIRED_NOT_ABOUT_PERSON,
)

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
    # memory-review ticket 05: the row that replaced this one (a merge, a correction or a
    # relabel), and why it left recall. `forget()` follows `retired_by` as well as `supersedes`,
    # so forgetting a merged row takes every duplicate it absorbed.
    retired_by = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL, related_name="retired_rows"
    )
    retired_reason = models.TextField(null=True, blank=True)
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
            models.CheckConstraint(
                condition=models.Q(retired_reason__isnull=True)
                | models.Q(retired_reason__in=RETIRED_REASONS),
                name="member_memories_retired_reason_valid",
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


# ---------------------------------------------------------------------------------------------
# The daily memory review's ledger (memory-review ticket 05). The review runs on the Claude
# worker on the Alienware; the engine hands it a snapshot (`GET review/context`), gates what it
# sends back (`POST review/changes`), and records both here. Private to the member like the
# memories themselves: `owner_user` is never null and always equals `member` (`OWNER_PATHS`).
# Nothing here stores transcript text (ruling 04): a change keeps a paraphrased `reason` and the
# ids of the conversation records it cites.
# ---------------------------------------------------------------------------------------------

REVIEW_CONTEXT_ONLY = "context_only"
REVIEW_OK = "ok"
REVIEW_FAILED = "failed"
REVIEW_OUTCOMES: tuple[str, ...] = (REVIEW_CONTEXT_ONLY, REVIEW_OK, REVIEW_FAILED)


class MemoryReview(models.Model):
    """One review run for one member: the snapshot it was judged on, and what came of it."""

    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    member = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    # The worker task that ran it. SET_NULL: tasks are pruned at 30 days, the ledger is not.
    task = models.ForeignKey(
        "claude_session.ClaudeTask", null=True, blank=True, on_delete=models.SET_NULL,
        related_name="+",
    )
    context_id = models.UUIDField(default=uuid.uuid4, unique=True, editable=False)
    context_at = models.DateTimeField(db_default=Now())
    submitted_at = models.DateTimeField(null=True, blank=True)
    # `shadow` or `on`, as it applied to this run (the effective mode, after the guards).
    mode = models.TextField(default="shadow")
    machine = models.TextField(blank=True, default="")
    model = models.TextField(blank=True, default="")
    chars_in = models.IntegerField(default=0)
    chars_out = models.IntegerField(default=0)
    rows_seen = models.IntegerField(default=0)
    logs_read = models.IntegerField(default=0)
    # handle -> {"id": memory id, "updated_at": iso}; label -> device_log id; exact-text groups.
    # Ids and stamps only, never text, so a submit maps back to the snapshot it was judged on.
    handles = models.JSONField(default=dict, blank=True)
    logs = models.JSONField(default=dict, blank=True)
    code_groups = models.JSONField(default=list, blank=True)
    applied = models.IntegerField(default=0)
    proposed = models.IntegerField(default=0)
    refused = models.IntegerField(default=0)
    stale = models.IntegerField(default=0)
    counts = models.JSONField(default=dict, blank=True)
    # Token counts from the helper's stream-json, when the worker sent them. Numbers only.
    usage = models.JSONField(default=dict, blank=True)
    # The submit's answer, replayed unchanged for a repeated submit (idempotent).
    answer = models.JSONField(default=dict, blank=True)
    outcome = models.TextField(default=REVIEW_CONTEXT_ONLY)
    # Words only, never memory or transcript text.
    detail = models.TextField(blank=True, default="")

    class Meta:
        db_table = "memory_reviews"
        indexes = [
            models.Index(
                fields=["household", "member", "-context_at"], name="memory_reviews_member_idx"
            ),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(outcome__in=REVIEW_OUTCOMES), name="memory_reviews_outcome_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(owner_user=models.F("member")),
                name="memory_reviews_private_to_its_member",
            ),
        ]

    def __str__(self) -> str:
        return f"memory review {self.id} ({self.outcome})"


ACTION_MERGE = "merge"
ACTION_CORRECT = "correct"
ACTION_EXPIRE = "expire"
ACTION_RELABEL = "relabel"
ACTION_RETIRE = "retire"
ACTIONS: tuple[str, ...] = (
    ACTION_MERGE, ACTION_CORRECT, ACTION_EXPIRE, ACTION_RELABEL, ACTION_RETIRE,
)

CHANGE_APPLIED = "applied"
CHANGE_PENDING = "pending"
CHANGE_ACCEPTED = "accepted"
CHANGE_DISMISSED = "dismissed"
CHANGE_STALE = "stale"
CHANGE_REVERTED = "reverted"
CHANGE_STATUSES: tuple[str, ...] = (
    CHANGE_APPLIED, CHANGE_PENDING, CHANGE_ACCEPTED, CHANGE_DISMISSED, CHANGE_STALE,
    CHANGE_REVERTED,
)
REASON_MAX_CHARS = 200


class MemoryReviewChange(models.Model):
    """One change a review applied or proposed. `proposed` holds the new text and labels of a
    pending change (gate-checked words the helper wrote, never a transcript quote)."""

    id = models.BigAutoField(primary_key=True)
    review = models.ForeignKey(MemoryReview, on_delete=models.CASCADE, related_name="changes")
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    member = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    action = models.TextField()
    status = models.TextField()
    # True for the engine's own exact-text merge (ruling 03): no model judged it.
    by_code = models.BooleanField(default=False)
    # Memory ids and their `updated_at` as judged, for the staleness check.
    targets = models.JSONField(default=list)
    targets_updated_at = models.JSONField(default=list)
    result = models.ForeignKey(
        MemberMemory, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    proposed = models.JSONField(default=dict, blank=True)
    # What an applied change overwrote on a row it kept (a code merge's kept row's source).
    before = models.JSONField(default=dict, blank=True)
    reason = models.TextField(blank=True, default="")
    confidence = models.TextField(blank=True, default="")
    evidence_logs = models.JSONField(default=list, blank=True)
    gate = models.TextField(blank=True, default="")
    cluster = models.IntegerField(default=0)
    created_at = models.DateTimeField(db_default=Now())
    decided_at = models.DateTimeField(null=True, blank=True)
    decided_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )

    class Meta:
        db_table = "memory_review_changes"
        indexes = [
            models.Index(
                fields=["household", "member", "status"], name="memory_review_changes_idx"
            ),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(action__in=ACTIONS), name="memory_review_changes_action_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(status__in=CHANGE_STATUSES),
                name="memory_review_changes_status_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(owner_user=models.F("member")),
                name="memory_review_changes_private_to_its_member",
            ),
        ]

    def __str__(self) -> str:
        return f"memory review change {self.id} ({self.action}, {self.status})"
