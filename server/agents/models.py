"""The agent turn's tables (engine-brain ticket 02, ADR 0095 and 0100; plan section 13.5).

Six tables, all in `public`, all born tenanted (`household`, listed in
`household.tenancy.TENANT_TABLES`) and all **private to their member**: `owner_user` is never
null, so `household.tenancy.visible()` hands a member only their own conversations and traces
(ADR 0052), and the owner guard trigger (`household/visibility_sql.py`) refuses an owner from
outside the row's household. Every child row carries its own `household` and `owner_user` rather
than inheriting them through the turn, so each table is guarded by the same one-column rule.

- `agents_conversation`: one conversation of one member on one client.
- `agents_turn`: one turn: what was said, the route, the guards that fired, the final text, the
  turn's own history items (model turns verbatim, tool results trimmed), timings and cost.
- `agents_checkpoint`: the turn state after every graph node (resume and replay).
- `agents_model_call`: numbers only, one row per model round.
- `agents_tool_call`: one row per tool call, arguments redacted, result trimmed.
- `agents_limb` (ticket 04, E1, migration 0002): one row per device attached on the limb contract
  (ADR 0098), keyed by its device token, with its manifest.

**Retention** (ruling 6): turns, traces and tool calls 30 days, checkpoints 7 days, model-call
numbers 90 days (`agents/legion/retention.py`, run at every turn and by the hourly
`prune_demo_visitors`). A demo visit's rows go with the visit household.

**Mail never lands here** (ADR 0099): the redaction hook (`agents/legion/redact.py`) drops a
mail tool's arguments and result before any of these rows is written, so a trace says only that
a mail tool ran. No mail tool exists yet (stage 4); the hook is in place now.

The plan names the member column `member`; it is `owner_user` here because that is the column
`OWNER_PATHS`, `visible()` and the owner guard key on. The plan's sixth table,
`agents_eval_candidate`, is not built in this stage: ticket 02 lists five, and the candidate
export belongs with the trace page (ticket 04).

Additive: 0001 made five tables; 0002 adds `agents_limb`, `agents_turn.pending_relay` and the
`awaiting_limb` status. Unused by any live code path while the turn endpoint is off.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

TURN_RUNNING = "running"
TURN_DONE = "done"
TURN_CANCELLED = "cancelled"
TURN_FAILED = "failed"
TURN_REFUSED = "refused"
# Ticket 04 (E1): a turn paused on a limb's tool (`pending_relay` holds what it waits for). Nothing
# sets it until the relay ships (E2); the constraint is widened first so that ship is code only.
TURN_AWAITING_LIMB = "awaiting_limb"
TURN_STATUSES: tuple[str, ...] = (
    TURN_RUNNING,
    TURN_DONE,
    TURN_CANCELLED,
    TURN_FAILED,
    TURN_REFUSED,
    TURN_AWAITING_LIMB,
)

# ADR 0098: the kinds of device that may attach as a limb, and the two brains a member's turns
# may be answered by. The web app is not a limb yet: it signs in by session, with no device token.
LIMB_KINDS: tuple[str, ...] = ("phone", "headunit")
BRAINS: tuple[str, ...] = ("phone", "engine")

TOOL_OUTCOMES: tuple[str, ...] = ("ok", "failed", "refused", "timed_out")
# Which guard stopped a call, if one did (plan section 14).
GUARDS: tuple[str, ...] = (
    "not_allowed",
    "tainted",
    "needs_confirm",
    "budget",
    "recruiter",
    "cancelled",
)

# Ruling 6: content 30 days, checkpoints 7, numbers 90.
TURN_RETENTION_DAYS = 30
CHECKPOINT_RETENTION_DAYS = 7
METRIC_RETENTION_DAYS = 90


class _Owned(models.Model):
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        abstract = True


class AgentConversation(_Owned):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # phone / web / headunit / scheduled / eval
    client = models.TextField()
    # `agents.legion.graph_version()`: pinned at the first turn (plan section 17).
    graph_version = models.TextField(default="")
    last_turn_at = models.DateTimeField(db_default=Now())
    status = models.TextField(default="open")
    # The write waiting for a yes (plan section 14, confirm before write): the tool, the sha256
    # of its arguments, its read-back and the last turn it may be confirmed in. Null when none.
    pending_confirm = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "agents_conversation"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "last_turn_at"], name="agents_conv_owner_idx"
            )
        ]


class AgentTurn(_Owned):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    conversation = models.ForeignKey(
        AgentConversation, on_delete=models.CASCADE, related_name="turns"
    )
    seq = models.IntegerField()
    transcript = models.TextField()
    route_agents = models.JSONField(default=list)
    route_reason = models.TextField(default="")
    status = models.TextField(default=TURN_RUNNING)
    cancel_requested = models.BooleanField(default=False)
    tainted = models.BooleanField(default=False)
    taint_sources = models.JSONField(default=list)
    writes_ran = models.BooleanField(default=False)
    final_text = models.TextField(default="")
    honesty_flag = models.BooleanField(default=False)
    latency_ms = models.IntegerField(null=True, blank=True)
    first_sentence_ms = models.IntegerField(null=True, blank=True)
    cost_micro_usd = models.BigIntegerField(default=0)
    graph_version = models.TextField(default="")
    # This turn's own history items, model-format (the next turn's context): the user's words,
    # model turns verbatim (thought signatures kept), tool results as the model saw them, a mail
    # tool's replaced by a stub (`redact.history_for_storage`). The per-call trace row is trimmed.
    history = models.JSONField(default=list)
    # Ticket 04 (E1): the limb call a paused turn waits for (call id, tool, deadline). Null when
    # none. Nullable with no Python default, so the revision before it inserts without naming it.
    pending_relay = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "agents_turn"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "created_at"], name="agents_turn_owner_idx"
            )
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "conversation", "seq"],
                name=household_unique_name("agents_turn", ["conversation_id", "seq"]),
            ),
            models.CheckConstraint(
                condition=models.Q(status__in=TURN_STATUSES), name="agents_turn_status_valid"
            ),
        ]


class AgentCheckpoint(_Owned):
    turn = models.ForeignKey(AgentTurn, on_delete=models.CASCADE, related_name="checkpoints")
    step = models.IntegerField()
    node = models.TextField()
    state = models.JSONField()
    events = models.JSONField(default=list)

    class Meta:
        db_table = "agents_checkpoint"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "created_at"], name="agents_ckpt_owner_idx"
            )
        ]


class AgentModelCall(_Owned):
    turn = models.ForeignKey(AgentTurn, on_delete=models.CASCADE, related_name="model_calls")
    node = models.TextField()
    agent = models.TextField()
    model = models.TextField()
    round = models.IntegerField()
    prompt_tokens = models.IntegerField(null=True, blank=True)
    cached_tokens = models.IntegerField(null=True, blank=True)
    output_tokens = models.IntegerField(null=True, blank=True)
    first_token_ms = models.IntegerField(null=True, blank=True)
    total_ms = models.IntegerField(null=True, blank=True)
    cost_micro_usd = models.BigIntegerField(null=True, blank=True)
    finish_reason = models.TextField(default="")

    class Meta:
        db_table = "agents_model_call"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "created_at"], name="agents_mc_owner_idx"
            )
        ]


class AgentToolCall(_Owned):
    turn = models.ForeignKey(AgentTurn, on_delete=models.CASCADE, related_name="tool_calls")
    call_id = models.TextField(default="")
    tool = models.TextField()
    # "engine", or a limb id once the relay exists (stage 3).
    where = models.TextField(default="engine")
    writes = models.BooleanField(default=False)
    args_redacted = models.JSONField(default=dict)
    result_trimmed = models.TextField(default="")
    outcome = models.TextField()
    guard = models.TextField(null=True, blank=True)
    ms = models.IntegerField(null=True, blank=True)

    class Meta:
        db_table = "agents_tool_call"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "created_at"], name="agents_tc_owner_idx"
            )
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(outcome__in=TOOL_OUTCOMES), name="agents_tool_call_outcome_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(guard__isnull=True) | models.Q(guard__in=GUARDS),
                name="agents_tool_call_guard_valid",
            ),
        ]


class Limb(_Owned):
    """One device attached to the engine on the limb contract (ADR 0098; ticket 04, slice E1).

    Keyed by the authenticated device token, never by anything in a request body: one row per
    token, upserted by the manifest upload (`agents/legion/limbs.py`). Private to its member like
    every agent table (`OWNER_PATHS`).

    `brain_choice` is the SERVER's per-member override (phone or engine), set only by an operator;
    a manifest upload never writes it. Null means no override.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    device = models.ForeignKey("household.DeviceToken", on_delete=models.CASCADE, related_name="+")
    kind = models.TextField()
    app_version = models.TextField()
    contract_version = models.IntegerField()
    # The validated manifest: the tools this limb runs for the agents and the streams it sends.
    manifest = models.JSONField(default=dict)
    brain_choice = models.TextField(null=True, blank=True)
    last_seen_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "agents_limb"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "last_seen_at"], name="agents_limb_owner_idx"
            )
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "device"],
                name=household_unique_name("agents_limb", ["device_id"]),
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=LIMB_KINDS), name="agents_limb_kind_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(brain_choice__isnull=True) | models.Q(brain_choice__in=BRAINS),
                name="agents_limb_brain_choice_valid",
            ),
        ]
