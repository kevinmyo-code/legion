"""REST doors for the four engine homes (engine-brain ticket 03). The phone's Room tables become
the offline replica of these rows; the agent tools (`homes/tools.py`) read and write the same rows.

Each table has the generic synced shape (`api/synced.SyncedModelViewSet`), so the phone's
`EngineSyncedTable` reads and writes it with no new loop:

    /api/body/goals/              GET ?since= ?after= ?active=1     PUT/DELETE <origin_guid>/
    /api/advisor/advice/          (same)                            private to the member
    /api/ledger/pending/          (same)                            shared in the household
    /api/proactive/raises/        GET ?since= ?after=               PUT <origin_guid>/, no DELETE

and one more route per table, `POST <prefix>batch/`, for the one-time backfill: up to
`BATCH_MAX` rows in one request, upserted by `origin_guid`, answered with counts in words
(`batch_upsert`). A retry, or a backfill that stopped halfway and runs again, lands on the rows
it already wrote: nothing is doubled, and an unchanged row is counted as unchanged, not rewritten.

**Not in `api/registry.py`, on purpose.** The registry feeds `/api/changes`, which is unpaged and
has no member privacy, and the generic `read_records` / `write_record` tools, which read by
household only: a private advice or raise row would be served to the other member. Routed here,
each table reaches the database only through `queryset()`, which is `visible()` for the private two.
"""

from __future__ import annotations

import uuid

from django.db import transaction
from django.db.models.functions import Now
from django.urls import path
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.sync import save_or_400
from api.synced import (
    SyncedModelViewSet,
    SyncedSerializer,
    choice_error,
    synced_paths,
)
from homes.models import (
    CURRENCIES,
    DELIVERIES,
    AdvisorAdvice,
    PendingTransaction,
    ProactiveRaise,
)
from household.tenancy import visible
from legacy.models.body import Goal

BATCH_MAX = 500
# The phone's own guard against a misheard number (`ledger/LedgerPendingLog.kt`).
PENDING_AMOUNT_MAX_CENTS = 100_000_000


def _blank_ok(*fields: str) -> dict:
    """The phone's Room columns are free TEXT, so a blank value is a value: refusing it would leave
    that row off the engine and its table never cleanly backfilled."""
    return {field: {"allow_blank": True} for field in fields}


class _ManagedSerializer(SyncedSerializer):
    """A Django-managed home: no `touch_updated_at` trigger, so an update stamps `updated_at`
    itself, and a private table's new row belongs to the member who wrote it."""

    def create(self, validated_data: dict):
        owner = self.context.get("owner")
        if owner is not None:
            validated_data["owner_user"] = owner
        return super().create(validated_data)

    def update(self, instance, validated_data: dict):
        validated_data["updated_at"] = Now()
        return super().update(instance, validated_data)


# -- serializers ---------------------------------------------------------------------------------


class GoalSerializer(SyncedSerializer):
    # No choice check: a goal's status is free TEXT on the phone too (`Goal.status`).
    status = serializers.CharField(required=False, default="active", allow_blank=True)

    class Meta:
        model = Goal
        fields = [
            "id",
            "lineage_id",
            "aspect",
            "statement",
            "target_value",
            "unit",
            "metric_key",
            "deadline_epoch",
            "status",
            "supersedes_guid",
            "closed_at",
            "created_at_client",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("aspect", "statement", "unit", "metric_key", "supersedes_guid")


class AdvisorAdviceSerializer(_ManagedSerializer):
    class Meta:
        model = AdvisorAdvice
        fields = [
            "id",
            "origin_guid",
            "aspect",
            "question_text",
            "gist",
            "advice_text",
            "proposal_json",
            "outcome",
            "given_at",
            "resolved_at",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok(
            "aspect", "question_text", "gist", "advice_text", "proposal_json", "outcome"
        )


class PendingTransactionSerializer(_ManagedSerializer):
    amount_cents = serializers.IntegerField(
        help_text=(
            "Signed integer cents, negative for money out. His own spoken word, never verified "
            "by a bank: every surface says so."
        )
    )

    class Meta:
        model = PendingTransaction
        fields = [
            "id",
            "origin_guid",
            "account_id",
            "currency",
            "txn_date",
            "description",
            "amount_cents",
            "logged_at",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("account_id", "description")

    def validate_currency(self, value: str) -> str:
        if value not in CURRENCIES:
            raise choice_error("currency", value, CURRENCIES)
        return value

    def validate_amount_cents(self, value: int) -> int:
        if value == 0 or abs(value) > PENDING_AMOUNT_MAX_CENTS:
            raise serializers.ValidationError(
                f"Nothing was written. amount_cents must be non-zero and at most "
                f"{PENDING_AMOUNT_MAX_CENTS} either way; {value} is not."
            )
        return value


class ProactiveRaiseSerializer(_ManagedSerializer):
    class Meta:
        model = ProactiveRaise
        fields = [
            "id",
            "origin_guid",
            "rule_id",
            "category",
            "reason",
            "spoken_at",
            "declined",
            "delivery",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at"]
        extra_kwargs = _blank_ok("rule_id", "category", "reason")

    def validate_delivery(self, value: str) -> str:
        if value not in DELIVERIES:
            raise choice_error("delivery", value, DELIVERIES)
        return value


# -- viewsets ------------------------------------------------------------------------------------


class ManagedTombstoneViewSet(SyncedModelViewSet):
    """A Django-managed home has no `touch_updated_at` trigger, so the generic DELETE (which saves
    only `deleted_at`) would leave `updated_at` alone and the `?since=` feed would never carry the
    removal to another client. This DELETE stamps both. Idempotent: a second DELETE is still 204.
    (Found building ticket 15; stage 2's advice and pending tables had the gap.)"""

    def destroy(self, request, identity):
        """Sets the tombstone AND stamps `updated_at` (no trigger on a Django-managed home), so
        the `?since=` feed carries the removal. Idempotent: a second DELETE is still 204."""
        instance = self._lookup(identity)
        if instance is None:
            return self._not_found(identity)
        if instance.deleted_at is None:
            instance.deleted_at = Now()
            instance.updated_at = Now()
            _saved, error = save_or_400(
                lambda: instance.save(update_fields=["deleted_at", "updated_at"])
            )
            if error is not None:
                return error
        return Response(status=status.HTTP_204_NO_CONTENT)


class _PrivateViewSet(ManagedTombstoneViewSet):
    """Every read through `visible()` (ADR 0052): a member sees only their own rows."""

    def queryset(self):
        return self.decorate(visible(self.model(), self.request))

    def serializer_context(self) -> dict:
        context = super().serializer_context()
        context["owner"] = self.request.user
        return context


class GoalViewSet(SyncedModelViewSet):
    aspect = "body"
    table = "goals"
    serializer_class = GoalSerializer


class AdvisorAdviceViewSet(_PrivateViewSet):
    aspect = "advisor"
    table = "advisor_advice"
    url_segment = "advice"
    serializer_class = AdvisorAdviceSerializer


class PendingTransactionViewSet(ManagedTombstoneViewSet):
    aspect = "ledger"
    table = "pending_transactions"
    url_segment = "pending"
    serializer_class = PendingTransactionSerializer


class ProactiveRaiseViewSet(_PrivateViewSet):
    aspect = "proactive"
    table = "proactive_raises"
    url_segment = "raises"
    serializer_class = ProactiveRaiseSerializer
    has_tombstones = False
    allow_delete = False
    no_delete_reason = "A raise is a record of what was said."


HOME_VIEWSETS: tuple[type[SyncedModelViewSet], ...] = (
    GoalViewSet,
    AdvisorAdviceViewSet,
    PendingTransactionViewSet,
    ProactiveRaiseViewSet,
)


# -- the backfill door ---------------------------------------------------------------------------


def _unchanged(instance, validated: dict) -> bool:
    return all(getattr(instance, field) == value for field, value in validated.items())


def batch_upsert(viewset: SyncedModelViewSet, rows) -> tuple[dict, int]:
    """Upserts `rows` by `origin_guid` in one transaction and a fixed number of queries.

    Returns (body, status). A row that does not validate is named in `failed` with the reason
    and nothing of it is written; every other row is written. The body says in words what moved.
    """
    table = viewset.table
    if not isinstance(rows, list):
        return {"detail": 'Nothing was written. Send {"rows": [...]}, a list of rows.'}, 400
    if len(rows) > BATCH_MAX:
        return {
            "detail": (
                f"Nothing was written. {len(rows)} rows is more than the {BATCH_MAX} one batch "
                f"takes; send them in pages."
            )
        }, 400
    identity = viewset.identity_field
    latest: dict[str, dict] = {}
    failed: list[dict] = []
    for row in rows:
        guid = row.get(identity) if isinstance(row, dict) else None
        if not isinstance(guid, str) or not guid.strip():
            failed.append({identity: None, "detail": f"A row without {identity} was skipped."})
            continue
        latest[guid] = row  # a guid sent twice: the later row wins, as two PUTs would

    queryset = viewset.queryset()
    existing = {
        getattr(o, identity): o for o in queryset.filter(**{f"{identity}__in": list(latest)})
    }
    context = viewset.serializer_context()
    to_create, to_update, unchanged = [], [], 0
    update_fields: set[str] = set()
    model = viewset.model()
    field_names = {f.name for f in model._meta.get_fields()}
    now = timezone.now()
    for guid, row in latest.items():
        instance = existing.get(guid)
        serializer = viewset.serializer_class(instance, data=row, context=context)
        if not serializer.is_valid():
            failed.append({identity: guid, "detail": serializer.errors})
            continue
        data = dict(serializer.validated_data)
        if instance is None:
            obj = model(**data)
            if "id" in field_names:
                obj.id = uuid.uuid4()
            obj.household = context["household"]
            if context.get("owner") is not None:
                obj.owner_user = context["owner"]
            obj.created_at = now
            obj.updated_at = now
            if "provenance" in field_names:
                obj.provenance = viewset.serializer_class.default_provenance
            to_create.append(obj)
        elif _unchanged(instance, data):
            unchanged += 1
        else:
            for field, value in data.items():
                setattr(instance, field, value)
            instance.updated_at = now
            update_fields.update(data)
            update_fields.add("updated_at")
            to_update.append(instance)
    try:
        with transaction.atomic():
            if to_create and "provenance" in field_names:
                # `goals.provenance` is the Postgres enum `public.provenance`, and a bulk insert
                # sends a typed text array it will not cast. Goals are few; one insert each.
                for obj in to_create:
                    obj.save(force_insert=True)
            elif to_create:
                model.objects.bulk_create(to_create)
            if to_update:
                model.objects.bulk_update(to_update, sorted(update_fields))
    except Exception as exc:  # noqa: BLE001 - the answer must say nothing was written
        return {
            "detail": (
                f"Nothing in this batch was written to {table}: the database refused it "
                f"({type(exc).__name__}). Send it again; rows already on the engine are not "
                f"doubled."
            )
        }, 400
    created, updated = len(to_create), len(to_update)
    held = viewset.queryset().count()
    says = (
        f"{table}: received {len(rows)}, created {created}, updated {updated}, "
        f"unchanged {unchanged}, failed {len(failed)}. The engine now holds {held} "
        f"{table} rows you can see."
    )
    return {
        "received": len(rows),
        "created": created,
        "updated": updated,
        "unchanged": unchanged,
        "failed": failed,
        "engine_count": held,
        "says": says,
    }, 200


BATCH_RESPONSE = inline_serializer(
    name="HomesBatchResult",
    fields={
        "received": serializers.IntegerField(),
        "created": serializers.IntegerField(),
        "updated": serializers.IntegerField(),
        "unchanged": serializers.IntegerField(),
        "failed": serializers.ListField(child=serializers.DictField()),
        "engine_count": serializers.IntegerField(),
        "says": serializers.CharField(),
    },
)


def _batch_schema(viewset_cls: type[SyncedModelViewSet]):
    return extend_schema(
        tags=[viewset_cls.aspect],
        operation_id=f"{viewset_cls.route_name().replace('-', '_')}_batch",
        request=inline_serializer(
            name=f"{viewset_cls.__name__.removesuffix('ViewSet')}Batch",
            fields={"rows": serializers.ListField(child=viewset_cls.serializer_class())},
        ),
        responses={
            200: BATCH_RESPONSE,
            400: OpenApiResponse(description="Nothing was written; the reason in words."),
        },
        description=(
            f"Upsert up to {BATCH_MAX} {viewset_cls.table} rows by origin_guid in one request "
            f"(the phone's one-time backfill). Idempotent: a retry doubles nothing."
        ),
    )


class _BatchView(APIView):
    viewset_cls: type[SyncedModelViewSet]

    def _run(self, request):
        viewset = self.viewset_cls()
        viewset.request = request
        viewset.format_kwarg = None
        body, code = batch_upsert(viewset, request.data.get("rows"))
        return Response(body, status=code)


class GoalBatchView(_BatchView):
    viewset_cls = GoalViewSet

    @_batch_schema(GoalViewSet)
    def post(self, request):
        return self._run(request)


class AdvisorAdviceBatchView(_BatchView):
    viewset_cls = AdvisorAdviceViewSet

    @_batch_schema(AdvisorAdviceViewSet)
    def post(self, request):
        return self._run(request)


class PendingTransactionBatchView(_BatchView):
    viewset_cls = PendingTransactionViewSet

    @_batch_schema(PendingTransactionViewSet)
    def post(self, request):
        return self._run(request)


class ProactiveRaiseBatchView(_BatchView):
    viewset_cls = ProactiveRaiseViewSet

    @_batch_schema(ProactiveRaiseViewSet)
    def post(self, request):
        return self._run(request)


BATCH_VIEWS: tuple[type[_BatchView], ...] = (
    GoalBatchView,
    AdvisorAdviceBatchView,
    PendingTransactionBatchView,
    ProactiveRaiseBatchView,
)


def home_paths() -> list:
    """The batch route comes first: `<str:identity>/` would otherwise take `batch/` as an id.
    Ticket 15's user-aspect records (`homes/records.py`) are routed the same way."""
    from homes.records import RECORD_BATCH_VIEWS

    out = []
    for batch in (*BATCH_VIEWS, *RECORD_BATCH_VIEWS):
        viewset = batch.viewset_cls
        out.append(
            path(
                f"{viewset.route_prefix()}batch/",
                batch.as_view(),
                name=f"{viewset.route_name()}-batch",
            )
        )
        out.extend(synced_paths(viewset))
    return out
