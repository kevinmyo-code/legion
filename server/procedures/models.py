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

Runs and their step traces are ticket 13; nothing here runs anything.
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
