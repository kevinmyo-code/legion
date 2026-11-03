"""Procedures: versioned definitions of a multi-step answer (procedures ticket 11, ADR 0070).

A procedure is a named DAG of steps (`procedures/definition.py` says which shapes are allowed).
Two tables, both in `public` and both carrying `household_id`, so both are in
`household.tenancy.TENANT_TABLES`:

- `procedures`: one row per name per household. **A built-in has no household** (its YAML lives
  in the repo, `procedures/builtin/`) and is visible to every household; a hand-made or distilled
  one belongs to exactly one household. `visible_to(household)` is the one read path.
- `procedure_versions`: the definitions. **A version is immutable once written** (a trigger
  refuses an UPDATE of its definition, number, owner, source, hash or computed `writes`); a
  change is a new version. Only `is_active` and the approval columns may change, and at most one
  version per procedure is active, and only an approved one.

`writes` is COMPUTED by the validator, never declared by a definition (ruling 01): a client
stamps BLOCKING from it.

Two more tables hold what RAN (procedures ticket 13, ruling 06): `procedure_runs`, one row per
`run_procedure` call (a resume continues its run's row), and `procedure_steps`, one row per step
that ran. Both carry `household_id` and are tenant tables; a run is always ONE household's, even
of a built-in. They follow the conversation record's posture, not `McpCall`'s: a step's arguments
and its result, trimmed to the record's cap (`devicelogs.web_conversation.CAP_TOOL`). Members'
runs go after `RETENTION_DAYS` (pruned inline at each new run, the way `devicelogs.views.prune`
runs on each upload); a recruiter visit's runs go with the visit household (`demo.visit_cleanup`
reads the foreign keys) and never outlive `VISIT_RETENTION_DAYS` (`manage.py
prune_demo_visitors`). The runner is `procedures/runner.py`.
"""

from __future__ import annotations

import uuid

from django.conf import settings
from django.db import models
from django.db.models import Q
from django.db.models.functions import Now


class Source(models.TextChoices):
    # YAML in the repo, loaded on migrate.
    BUILTIN = "builtin", "Built-in"
    # Proposed from repeated conversations (ticket 20); inactive until Kevin approves.
    DISTILLED = "distilled", "Distilled"
    # Written by hand for one household.
    HAND = "hand", "Hand-written"


class Procedure(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # NULL for a built-in. CASCADE: a household's own procedures go with it.
    household = models.ForeignKey(
        "household.Household", null=True, blank=True, on_delete=models.CASCADE, related_name="+"
    )
    name = models.TextField()
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "procedures"
        constraints = [
            # NULLS NOT DISTINCT (Postgres 15+): two built-ins may not share a name either.
            # Not partial indexes: the tenancy planner would re-key one without household_id.
            models.UniqueConstraint(
                fields=("household", "name"),
                name="procedures_household_name_uniq",
                nulls_distinct=False,
            ),
            models.CheckConstraint(
                condition=Q(name__regex=r"^[a-z][a-z0-9_]{1,62}$"),
                name="procedures_name_shape",
            ),
        ]

    def __str__(self) -> str:
        return self.name

    def active_version(self) -> ProcedureVersion | None:
        return self.versions.filter(is_active=True).first()


class ProcedureVersion(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    procedure = models.ForeignKey(Procedure, on_delete=models.CASCADE, related_name="versions")
    # Always its procedure's household (NULL for a built-in). Carried here so the table is
    # tenant-scoped on its own, like every other tenant table.
    household = models.ForeignKey(
        "household.Household", null=True, blank=True, on_delete=models.CASCADE, related_name="+"
    )
    version = models.PositiveIntegerField()
    definition = models.JSONField()
    source = models.TextField(choices=Source.choices)
    # SHA-256 of the YAML file a built-in came from, or of the canonical JSON otherwise.
    content_sha256 = models.CharField(max_length=64)
    # Computed from the reachable `tool` steps by `definition.validate`.
    writes = models.BooleanField()
    is_active = models.BooleanField(default=False)
    approved_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="+",
    )
    # A built-in is approved by being in the repo: set when loaded, `approved_by` NULL.
    approved_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "procedure_versions"
        constraints = [
            models.UniqueConstraint(
                fields=("procedure", "version"), name="procedure_versions_number_uniq"
            ),
            models.UniqueConstraint(
                fields=("procedure",),
                condition=Q(is_active=True),
                name="procedure_versions_one_active",
            ),
            # Never auto-activated (ruling 08): an active version was approved.
            models.CheckConstraint(
                condition=Q(is_active=False) | Q(approved_at__isnull=False),
                name="procedure_versions_active_is_approved",
            ),
            models.CheckConstraint(
                condition=Q(source__in=[s.value for s in Source]),
                name="procedure_versions_source_valid",
            ),
            models.CheckConstraint(condition=Q(version__gte=1), name="procedure_versions_from_1"),
        ]

    def __str__(self) -> str:
        return f"{self.procedure.name} v{self.version}"


def visible_to(household) -> models.QuerySet[Procedure]:
    """Every procedure a household may see: the built-ins and its own, never another's."""
    return Procedure.objects.filter(Q(household__isnull=True) | Q(household=household))


# =============================================================================
# Runs and their step traces (procedures ticket 13, ruling 06)
# =============================================================================

# Ruling 06: the conversation record's retention, both of them.
RETENTION_DAYS = 60
VISIT_RETENTION_DAYS = 30


class CallerKind(models.TextChoices):
    PHONE = "phone", "Phone"
    HEADUNIT = "headunit", "Head unit"
    WEB = "web", "Web assistant"
    RECRUITER = "recruiter", "Recruiter visit"
    # A device token on `/mcp` that did not say which client it is (Claude Code, a script).
    MCP = "mcp", "MCP client"


class RunStatus(models.TextChoices):
    # Every step on the path ran and the answer was composed.
    OK = "ok", "OK"
    # Stopped part way (a step failed or was refused, or the time budget ran out) after at
    # least one step answered; the result names what ran and what did not.
    PARTIAL = "partial", "Partial"
    # Stopped before any step produced anything usable.
    FAILED = "failed", "Failed"
    # An `ask` step is waiting for the next utterance (ruling 03).
    WAITING = "waiting", "Waiting for an answer"
    # The answer was no, something else, or did not come in time. Nothing after the ask ran.
    CANCELLED = "cancelled", "Cancelled"


class StepOutcome(models.TextChoices):
    OK = "ok", "OK"
    REFUSED = "refused", "Refused"
    FAILED = "failed", "Failed"
    # An `ask` that returned its question; its answer is a later row for the same step.
    ASKED = "asked", "Asked"


class ChosenBy(models.TextChoices):
    # The definition's own edge, or a check's result.
    CODE = "code", "Code"
    # A `decide` step's small model (ruling 02): labelled, never silent.
    MODEL = "model", "A model"
    # The user's answer to an `ask`.
    USER = "user", "The user"


class ProcedureRun(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # Always the caller's household, a built-in's run included. CASCADE (ruling 06): a recruiter
    # visit's runs are deleted with the visit household.
    household = models.ForeignKey("household.Household", on_delete=models.CASCADE, related_name="+")
    # SET_NULL: a trace outlives its account, as a conversation record does.
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="+",
    )
    # PROTECT: a version is history and is never deleted while a run points at it.
    version = models.ForeignKey(ProcedureVersion, on_delete=models.PROTECT, related_name="runs")
    caller_kind = models.TextField(choices=CallerKind.choices)
    # The caller's conversation record (`DeviceLog.batch_id`), when it named one.
    conversation_id = models.UUIDField(null=True, blank=True)
    status = models.TextField(choices=RunStatus.choices)
    # The caller's inputs, as given.
    inputs = models.JSONField(default=dict)
    started_at = models.DateTimeField()
    # Engine-side time across every call of the run (a resume adds its own).
    ms = models.IntegerField(default=0)
    # The answer as returned, trimmed like a step's result.
    result = models.TextField(blank=True, default="")
    # While `waiting` only (ruling 03): which ask, the SHA-256 of the resume token, when it
    # expires, and the step values the rest of the run reads. Cleared the moment the run leaves
    # `waiting`, so untrimmed values live at most `runner.ASK_EXPIRY` (two minutes).
    waiting_step = models.TextField(blank=True, default="")
    resume_token_sha256 = models.CharField(max_length=64, blank=True, default="")
    resume_expires_at = models.DateTimeField(null=True, blank=True)
    resume_state = models.JSONField(null=True, blank=True)

    class Meta:
        db_table = "procedure_runs"
        indexes = [
            models.Index(fields=["started_at"], name="procedure_runs_started_idx"),
            models.Index(fields=["conversation_id"], name="procedure_runs_convo_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=Q(caller_kind__in=[k.value for k in CallerKind]),
                name="procedure_runs_caller_kind_valid",
            ),
            models.CheckConstraint(
                condition=Q(status__in=[s.value for s in RunStatus]),
                name="procedure_runs_status_valid",
            ),
            # Untrimmed values exist only while a run waits.
            models.CheckConstraint(
                condition=Q(status="waiting") | Q(resume_state__isnull=True),
                name="procedure_runs_state_only_while_waiting",
            ),
        ]

    def __str__(self) -> str:
        return f"run {self.id} ({self.status})"


class ProcedureStep(models.Model):
    id = models.BigAutoField(primary_key=True)
    run = models.ForeignKey(ProcedureRun, on_delete=models.CASCADE, related_name="steps")
    # Always its run's household; carried so the table is tenant-scoped on its own.
    household = models.ForeignKey("household.Household", on_delete=models.CASCADE, related_name="+")
    # Order within the run.
    seq = models.PositiveIntegerField()
    step_id = models.TextField()
    kind = models.TextField()
    # `tool` steps only.
    tool = models.TextField(blank=True, default="")
    # The arguments as sent, after `$inputs` / `$steps` were filled in.
    args = models.JSONField(null=True, blank=True)
    # What the step said, trimmed to `devicelogs.web_conversation.CAP_TOOL`.
    result = models.TextField(blank=True, default="")
    outcome = models.TextField(choices=StepOutcome.choices)
    # The edge taken out of the step: "next", a decide branch, an ask answer, a check's
    # "pass" / "fail". Empty when the run stopped here.
    branch = models.TextField(blank=True, default="")
    chosen_by = models.TextField(choices=ChosenBy.choices, default=ChosenBy.CODE)
    started_at = models.DateTimeField()
    ms = models.IntegerField()
    # `decide` only (ruling 02): the model, Gemini's token counts, and an ESTIMATED cost in
    # integer micro-dollars from `brief.news`'s named rate constants, never a billed figure.
    model = models.TextField(blank=True, default="")
    prompt_tokens = models.IntegerField(null=True, blank=True)
    output_tokens = models.IntegerField(null=True, blank=True)
    cost_estimate_micro_usd = models.BigIntegerField(null=True, blank=True)

    class Meta:
        db_table = "procedure_steps"
        ordering = ["run", "seq"]
        constraints = [
            models.UniqueConstraint(fields=("run", "seq"), name="procedure_steps_seq_uniq"),
            models.CheckConstraint(
                condition=Q(outcome__in=[o.value for o in StepOutcome]),
                name="procedure_steps_outcome_valid",
            ),
            models.CheckConstraint(
                condition=Q(chosen_by__in=[c.value for c in ChosenBy]),
                name="procedure_steps_chosen_by_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.step_id} ({self.outcome})"
