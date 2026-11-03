"""The run inspector and the branch report, read-only (procedures ticket 14, rulings 06 and 07).

Four GET routes under `/api/procedures/`, all scoped through `household.tenancy`:

- `GET /api/procedures/` the procedure versions this household can see, with how often each
  ran here: the branch report's picker.
- `GET /api/procedures/runs` this household's runs, newest first, with filters.
- `GET /api/procedures/runs/<id>` one run: its steps as a timeline, and the steps that did NOT
  run, named.
- `GET /api/procedures/report?name=&version=` the branch report for one version: every step
  and every way out of it, with how many of THIS household's runs took it, failed in it or
  never took it, and when each was last seen.

**Who sees what.** A member sees their household's runs (ADR 0045: a member sees everything in
their household). A recruiter's visit is its own household with the visitor as its only member
(ADR 0069), so tenancy alone already gives "their own visit's runs only" (ruling 07); the views
also narrow a recruiter to their own user, so that holds even if a visit ever had two members.
The report counts only `scoped` rows: a built-in version is shared by every household, and its
runs elsewhere are never counted here.

**Branches** are enumerated the way the runner RECORDS them, not from `definition.validate`'s
edges: a `tool` / `compute` step leaves on `next`, a `check` on `pass` or `fail` (a fail ends the
run with its sentence), a `decide` on its branch names, an `ask` on its answers, and a `say` ends
the run. Built from the stored definition alone, so a read never depends on today's registry or
`procedures/steps.py`.

Nothing here writes, and no body carries a household id.
"""

from __future__ import annotations

import datetime
import json

from django.db.models import Count, Max, Q
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from devicelogs.web_conversation import CAP_TOOL
from household.models import is_recruiter
from household.tenancy import household_of, scoped
from procedures.definition import successors
from procedures.models import (
    RETENTION_DAYS,
    CallerKind,
    ChosenBy,
    ProcedureRun,
    ProcedureStep,
    ProcedureVersion,
    RunStatus,
    StepOutcome,
    visible_to,
)

MAX_LIMIT = 200
DEFAULT_LIMIT = 50

NO_RUN = (
    "There is no such run in this household. Nothing was read. It may have been pruned after "
    f"{RETENTION_DAYS} days, or it belongs to another household."
)
CHOSEN_BY_WORDS = {
    ChosenBy.CODE: "Code chose",
    ChosenBy.MODEL: "A model chose",
    ChosenBy.USER: "The user chose",
}


def _trim(text: str) -> str:
    return text if len(text) <= CAP_TOOL else text[:CAP_TOOL] + "..."


def _json_text(value) -> str | None:
    if value is None or value == {}:
        return None
    return _trim(json.dumps(value, ensure_ascii=False, sort_keys=True, default=str))


def _runs(request):
    """The runs this caller may read: their household's, and a recruiter's own only."""
    runs = scoped(ProcedureRun, request)
    if is_recruiter(request.user):
        runs = runs.filter(user=request.user)
    return runs


def _who(run: ProcedureRun, user) -> str:
    if run.user_id is None:
        return "An account since removed"
    if run.user_id == user.id:
        return "You"
    name = (run.user.first_name or "").strip()
    return name or "Another member"


# =============================================================================
# Serializers
# =============================================================================


class RunSummarySerializer(serializers.Serializer):
    id = serializers.UUIDField()
    procedure = serializers.CharField(help_text="The procedure's name.")
    version = serializers.IntegerField()
    caller_kind = serializers.ChoiceField(choices=CallerKind.choices)
    caller = serializers.CharField(help_text='Which client asked, in words ("Phone").')
    who = serializers.CharField(
        help_text='"You", the member\'s first name, "Another member", or "An account since '
        'removed".'
    )
    status = serializers.ChoiceField(choices=RunStatus.choices)
    status_label = serializers.CharField(help_text='The status in words ("Partial").')
    started_at = serializers.DateTimeField()
    ms = serializers.IntegerField(help_text="Engine-side time across every call of the run.")
    step_count = serializers.IntegerField(help_text="Step rows recorded for the run.")
    conversation_id = serializers.UUIDField(
        allow_null=True, help_text="The caller's conversation record, when it named one."
    )


class RunListSerializer(serializers.Serializer):
    results = RunSummarySerializer(many=True)
    more = serializers.BooleanField(
        help_text="True when more runs match than `limit`; the list holds the newest."
    )
    retention_days = serializers.IntegerField(
        help_text="Runs older than this are pruned, so nothing older is listed."
    )


class RunStepSerializer(serializers.Serializer):
    seq = serializers.IntegerField()
    step_id = serializers.CharField()
    kind = serializers.CharField(help_text="tool, compute, decide, ask, check or say.")
    tool = serializers.CharField(help_text="The engine tool, for a `tool` step; else empty.")
    args = serializers.CharField(
        allow_null=True, help_text="The arguments as sent, as JSON, trimmed; null when none."
    )
    result = serializers.CharField(help_text="What the step said, trimmed.")
    outcome = serializers.ChoiceField(choices=StepOutcome.choices)
    outcome_label = serializers.CharField()
    branch = serializers.CharField(
        help_text="The way out the step took; empty when the run stopped here."
    )
    chosen_by = serializers.ChoiceField(choices=ChosenBy.choices)
    chosen_by_label = serializers.CharField(
        help_text='"Code chose", "A model chose" (a decide step, ruling 02) or "The user chose".'
    )
    started_at = serializers.DateTimeField()
    ms = serializers.IntegerField()
    model = serializers.CharField(help_text="A decide step's model; else empty.")
    prompt_tokens = serializers.IntegerField(allow_null=True)
    output_tokens = serializers.IntegerField(allow_null=True)
    cost_estimate_micro_usd = serializers.IntegerField(
        allow_null=True,
        help_text="An ESTIMATE in micro-dollars from named rate constants, never a billed figure.",
    )


class RunDetailSerializer(RunSummarySerializer):
    writes = serializers.BooleanField(help_text="True when the version has a writing step.")
    inputs = serializers.CharField(allow_null=True, help_text="The caller's inputs, as JSON.")
    result = serializers.CharField(help_text="The answer as returned, trimmed.")
    steps = RunStepSerializer(many=True)
    not_run = serializers.ListField(
        child=serializers.CharField(), help_text="Step ids in the definition that did not run."
    )
    not_run_sentence = serializers.CharField(help_text="The steps that did not run, in words.")


class ProcedureVersionSerializer(serializers.Serializer):
    procedure = serializers.CharField()
    version = serializers.IntegerField()
    description = serializers.CharField()
    writes = serializers.BooleanField()
    is_active = serializers.BooleanField()
    source = serializers.CharField(help_text="builtin, distilled or hand.")
    runs = serializers.IntegerField(help_text="This household's runs of this version.")
    last_run_at = serializers.DateTimeField(allow_null=True)


class ReportBranchSerializer(serializers.Serializer):
    branch = serializers.CharField()
    target = serializers.CharField(
        allow_null=True, help_text="The step it leads to; null when it ends the run."
    )
    taken = serializers.IntegerField(help_text="Runs that took it.")
    by_model = serializers.IntegerField(help_text="Of those, runs where a model chose it.")
    last_taken_at = serializers.DateTimeField(allow_null=True)
    never_taken = serializers.BooleanField()


class ReportStepSerializer(serializers.Serializer):
    step_id = serializers.CharField()
    kind = serializers.CharField()
    tool = serializers.CharField()
    reached = serializers.IntegerField(help_text="Runs in which the step ran.")
    failed = serializers.IntegerField(help_text="Runs in which the step failed.")
    refused = serializers.IntegerField(help_text="Runs in which the step was refused.")
    last_seen_at = serializers.DateTimeField(allow_null=True)
    last_failed_at = serializers.DateTimeField(
        allow_null=True, help_text="Last failure or refusal."
    )
    branches = ReportBranchSerializer(many=True)


class BranchReportSerializer(serializers.Serializer):
    procedure = serializers.CharField()
    version = serializers.IntegerField()
    writes = serializers.BooleanField()
    window_days = serializers.IntegerField(help_text="Runs older than this are pruned.")
    runs = serializers.IntegerField(help_text="This household's runs of this version.")
    last_run_at = serializers.DateTimeField(allow_null=True)
    steps = ReportStepSerializer(many=True)
    never_taken = serializers.ListField(
        child=serializers.CharField(), help_text='"step:branch" for every branch no run took.'
    )
    summary = serializers.CharField(help_text="The report in one sentence.")


class RunFilterSerializer(serializers.Serializer):
    procedure = serializers.CharField(required=False)
    status = serializers.ChoiceField(choices=RunStatus.choices, required=False)
    caller_kind = serializers.ChoiceField(choices=CallerKind.choices, required=False)
    since = serializers.DateTimeField(required=False)
    limit = serializers.IntegerField(min_value=1, max_value=MAX_LIMIT, required=False)


class ReportQuerySerializer(serializers.Serializer):
    name = serializers.CharField()
    version = serializers.IntegerField(min_value=1, required=False)


def _refusal(errors) -> Response:
    parts = []
    for field, messages in errors.items():
        parts.append(f"{field}: {' '.join(str(m) for m in messages)}")
    return Response(
        {"detail": "Nothing was read. The filter was not understood: " + "; ".join(parts)},
        status=status.HTTP_400_BAD_REQUEST,
    )


# =============================================================================
# Payloads
# =============================================================================


def _summary(run: ProcedureRun, user) -> dict:
    return {
        "id": run.id,
        "procedure": run.version.procedure.name,
        "version": run.version.version,
        "caller_kind": run.caller_kind,
        "caller": CallerKind(run.caller_kind).label,
        "who": _who(run, user),
        "status": run.status,
        "status_label": RunStatus(run.status).label,
        "started_at": run.started_at,
        "ms": run.ms,
        "step_count": getattr(run, "step_count", None) or run.steps.count(),
        "conversation_id": run.conversation_id,
    }


def _step(row: ProcedureStep) -> dict:
    return {
        "seq": row.seq,
        "step_id": row.step_id,
        "kind": row.kind,
        "tool": row.tool,
        "args": _json_text(row.args),
        "result": _trim(row.result),
        "outcome": row.outcome,
        "outcome_label": StepOutcome(row.outcome).label,
        "branch": row.branch,
        "chosen_by": row.chosen_by,
        "chosen_by_label": CHOSEN_BY_WORDS[ChosenBy(row.chosen_by)],
        "started_at": row.started_at,
        "ms": row.ms,
        "model": row.model,
        "prompt_tokens": row.prompt_tokens,
        "output_tokens": row.output_tokens,
        "cost_estimate_micro_usd": row.cost_estimate_micro_usd,
    }


def _not_run_sentence(run: ProcedureRun, not_run: list[str]) -> str:
    if not not_run:
        return "Every step in the procedure ran."
    names = ", ".join(not_run)
    if run.status == RunStatus.OK:
        return f"Not on this run's path, so they did not run: {names}."
    if run.status == RunStatus.WAITING:
        return f"Not run yet, waiting for an answer: {names}."
    return f"Did not run, because the run stopped first: {names}."


def exits(step: dict) -> list[tuple[str, str | None]]:
    """(branch, target or None) for every way the runner can record leaving `step`."""
    kind = step.get("kind")
    if kind == "check":
        return [("pass", step.get("next")), ("fail", None)]
    return list(successors(step))


def branch_report(version: ProcedureVersion, runs) -> dict:
    """The report for `version` over `runs` (already scoped to one household)."""
    runs = runs.filter(version=version)
    totals = runs.aggregate(n=Count("id"), last=Max("started_at"))
    rows = ProcedureStep.objects.filter(run__in=runs)
    per_step = {
        r["step_id"]: r
        for r in rows.values("step_id").annotate(
            reached=Count("run", distinct=True),
            failed=Count("run", filter=Q(outcome=StepOutcome.FAILED), distinct=True),
            refused=Count("run", filter=Q(outcome=StepOutcome.REFUSED), distinct=True),
            last_seen=Max("started_at"),
            last_failed=Max(
                "started_at", filter=Q(outcome__in=[StepOutcome.FAILED, StepOutcome.REFUSED])
            ),
        )
    }
    per_branch = {
        (r["step_id"], r["branch"]): r
        for r in rows.exclude(branch="")
        .values("step_id", "branch")
        .annotate(
            taken=Count("run", distinct=True),
            by_model=Count("run", filter=Q(chosen_by=ChosenBy.MODEL), distinct=True),
            last=Max("started_at"),
        )
    }
    steps, never = [], []
    for step in version.definition.get("steps", []):
        sid = step["id"]
        seen = per_step.get(sid, {})
        branches = []
        for branch, target in exits(step):
            hit = per_branch.get((sid, branch), {})
            taken = hit.get("taken", 0)
            if not taken:
                never.append(f"{sid}:{branch}")
            branches.append(
                {
                    "branch": branch,
                    "target": target,
                    "taken": taken,
                    "by_model": hit.get("by_model", 0),
                    "last_taken_at": hit.get("last"),
                    "never_taken": not taken,
                }
            )
        steps.append(
            {
                "step_id": sid,
                "kind": step.get("kind", ""),
                "tool": step.get("tool", ""),
                "reached": seen.get("reached", 0),
                "failed": seen.get("failed", 0),
                "refused": seen.get("refused", 0),
                "last_seen_at": seen.get("last_seen"),
                "last_failed_at": seen.get("last_failed"),
                "branches": branches,
            }
        )
    n = totals["n"]
    name = f"{version.procedure.name} v{version.version}"
    if n == 0:
        summary = (
            f"{name} has not run in this household in the last {RETENTION_DAYS} days, so no "
            "branch has been taken yet."
        )
    elif never:
        summary = (
            f"{name} ran {n} time{'s' if n != 1 else ''} here; {len(never)} "
            f"branch{'es' if len(never) != 1 else ''} never taken: {', '.join(never)}."
        )
    else:
        summary = f"{name} ran {n} time{'s' if n != 1 else ''} here; every branch was taken."
    return {
        "procedure": version.procedure.name,
        "version": version.version,
        "writes": version.writes,
        "window_days": RETENTION_DAYS,
        "runs": n,
        "last_run_at": totals["last"],
        "steps": steps,
        "never_taken": never,
        "summary": summary,
    }


def _versions(household):
    """Every version this household can see: the built-ins and its own, never another's."""
    return ProcedureVersion.objects.filter(procedure__in=visible_to(household)).select_related(
        "procedure"
    )


# =============================================================================
# Views
# =============================================================================


class ProcedureListView(APIView):
    @extend_schema(
        operation_id="api_procedures_list",
        tags=["procedures"],
        responses={
            200: OpenApiResponse(
                response=ProcedureVersionSerializer(many=True),
                description="Versions this household can see that are active or ran here.",
            )
        },
    )
    def get(self, request):
        household = household_of(request)
        counts = {
            r["version"]: r
            for r in _runs(request)
            .values("version")
            .annotate(n=Count("id"), last=Max("started_at"))
        }
        out = []
        for v in _versions(household).order_by("procedure__name", "-version"):
            hit = counts.get(v.id)
            if not v.is_active and not hit:
                continue
            out.append(
                {
                    "procedure": v.procedure.name,
                    "version": v.version,
                    "description": v.definition.get("description", ""),
                    "writes": v.writes,
                    "is_active": v.is_active,
                    "source": v.source,
                    "runs": hit["n"] if hit else 0,
                    "last_run_at": hit["last"] if hit else None,
                }
            )
        return Response(ProcedureVersionSerializer(out, many=True).data)


class RunListView(APIView):
    @extend_schema(
        operation_id="api_procedures_runs_list",
        tags=["procedures"],
        parameters=[
            OpenApiParameter("procedure", str, description="Only runs of this procedure name."),
            OpenApiParameter("status", str, enum=[s.value for s in RunStatus]),
            OpenApiParameter("caller_kind", str, enum=[k.value for k in CallerKind]),
            OpenApiParameter("since", datetime.datetime, description="Started at or after."),
            OpenApiParameter("limit", int, description=f"1 to {MAX_LIMIT}; {DEFAULT_LIMIT}."),
        ],
        responses={
            200: OpenApiResponse(response=RunListSerializer, description="Newest first."),
            400: OpenApiResponse(response=DetailSerializer, description="A filter was refused."),
        },
    )
    def get(self, request):
        query = RunFilterSerializer(data=request.query_params)
        if not query.is_valid():
            return _refusal(query.errors)
        f = query.validated_data
        runs = _runs(request)
        if "procedure" in f:
            runs = runs.filter(version__procedure__name=f["procedure"])
        if "status" in f:
            runs = runs.filter(status=f["status"])
        if "caller_kind" in f:
            runs = runs.filter(caller_kind=f["caller_kind"])
        if "since" in f:
            runs = runs.filter(started_at__gte=f["since"])
        limit = f.get("limit", DEFAULT_LIMIT)
        page = list(
            runs.select_related("version__procedure", "user")
            .annotate(step_count=Count("steps"))
            .order_by("-started_at")[: limit + 1]
        )
        body = {
            "results": [_summary(r, request.user) for r in page[:limit]],
            "more": len(page) > limit,
            "retention_days": RETENTION_DAYS,
        }
        return Response(RunListSerializer(body).data)


class RunDetailView(APIView):
    @extend_schema(
        operation_id="api_procedures_runs_retrieve",
        tags=["procedures"],
        responses={
            200: OpenApiResponse(response=RunDetailSerializer, description="One run, its steps."),
            404: OpenApiResponse(response=DetailSerializer, description="No such run here."),
        },
    )
    def get(self, request, run_id):
        run = (
            _runs(request)
            .select_related("version__procedure", "user")
            .annotate(step_count=Count("steps"))
            .filter(id=run_id)
            .first()
        )
        if run is None:
            return Response({"detail": NO_RUN}, status=status.HTTP_404_NOT_FOUND)
        rows = list(run.steps.order_by("seq"))
        ran = {r.step_id for r in rows}
        not_run = [s["id"] for s in run.version.definition.get("steps", []) if s["id"] not in ran]
        body = {
            **_summary(run, request.user),
            "writes": run.version.writes,
            "inputs": _json_text(run.inputs),
            "result": _trim(run.result),
            "steps": [_step(r) for r in rows],
            "not_run": not_run,
            "not_run_sentence": _not_run_sentence(run, not_run),
        }
        return Response(RunDetailSerializer(body).data)


class BranchReportView(APIView):
    @extend_schema(
        operation_id="api_procedures_report_retrieve",
        tags=["procedures"],
        parameters=[
            OpenApiParameter("name", str, required=True, description="The procedure's name."),
            OpenApiParameter(
                "version", int, description="Defaults to the active version, else the newest."
            ),
        ],
        responses={
            200: OpenApiResponse(response=BranchReportSerializer, description="The report."),
            400: OpenApiResponse(response=DetailSerializer, description="A filter was refused."),
            404: OpenApiResponse(response=DetailSerializer, description="No such procedure."),
        },
    )
    def get(self, request):
        query = ReportQuerySerializer(data=request.query_params)
        if not query.is_valid():
            return _refusal(query.errors)
        name = query.validated_data["name"]
        wanted = query.validated_data.get("version")
        household = household_of(request)
        versions = _versions(household).filter(procedure__name=name)
        if wanted is not None:
            versions = versions.filter(version=wanted)
        # The household's own procedure over a built-in of the same name (`listing.active`),
        # then the active version, then the newest.
        version = (
            versions.order_by("procedure__household", "-is_active", "-version").first()
            if versions.exists()
            else None
        )
        if version is None:
            which = f" v{wanted}" if wanted is not None else ""
            return Response(
                {
                    "detail": f"There is no procedure {name!r}{which} this household can see. "
                    "Nothing was read."
                },
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(BranchReportSerializer(branch_report(version, _runs(request))).data)
