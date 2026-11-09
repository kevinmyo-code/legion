"""`/api/member-memories/review/`: the daily memory review (memory-review tickets 06, 08, 15).

- `GET context` and `POST changes` are the Claude worker's (the Alienware, Kevin's own token).
- `GET changes`, `POST changes/<id>/accept|dismiss|revert` and `POST clusters/<review.n>/accept`
  are the Memory screen's (ruling 02).
- `POST run` queues one review on the caller's own Alienware (ticket 12).

Every route runs `service.refusal` first (recruiters, the work laptop), and only ever reads or
changes the caller's own private memories. **Nothing here logs.** No logger is imported, no
memory text goes in a URL or query string, and every refusal is words about the change.
"""

from __future__ import annotations

from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers
from rest_framework.parsers import JSONParser
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from member_memory import review
from member_memory.models import CHANGE_STATUSES

TAGS = ["member-memories"]


def _refused(exc: review.ReviewRefused) -> Response:
    return Response({"detail": exc.message}, status=exc.status)


class ReviewCountsSerializer(serializers.Serializer):
    applied = serializers.IntegerField()
    proposed = serializers.IntegerField()
    refused = serializers.IntegerField()
    stale = serializers.IntegerField()


class ReviewVerdictSerializer(serializers.Serializer):
    index = serializers.IntegerField()
    action = serializers.CharField()
    handles = serializers.ListField(child=serializers.CharField())
    status = serializers.CharField()
    gate = serializers.CharField()
    cluster = serializers.IntegerField()


class ReviewSubmitSerializer(serializers.Serializer):
    context_id = serializers.UUIDField()
    changes = serializers.ListField(child=serializers.DictField())
    dry_run = serializers.BooleanField(required=False, default=False)
    usage = serializers.DictField(required=False, help_text="Token counts only.")
    model = serializers.CharField(required=False)


class ReviewAnswerSerializer(serializers.Serializer):
    context_id = serializers.UUIDField()
    dry_run = serializers.BooleanField()
    mode = serializers.CharField()
    code_merged = serializers.IntegerField()
    counts = ReviewCountsSerializer()
    by_action = serializers.DictField(child=serializers.IntegerField())
    words = serializers.CharField()
    verdicts = ReviewVerdictSerializer(many=True)


class ReviewBeforeSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    text = serializers.CharField(allow_null=True)
    kind = serializers.CharField(allow_null=True)
    durable = serializers.BooleanField(allow_null=True)


class ReviewChangeSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    action = serializers.CharField()
    status = serializers.ChoiceField(choices=CHANGE_STATUSES)
    by_code = serializers.BooleanField()
    cluster = serializers.CharField(help_text="`<review id>.<n>`; Accept all takes it.")
    before = ReviewBeforeSerializer(many=True)
    after = serializers.DictField(allow_null=True)
    after_words = serializers.CharField()
    reason = serializers.CharField()
    action_words = serializers.CharField()
    confidence = serializers.CharField()
    gate = serializers.CharField()
    evidence_date = serializers.DateField(allow_null=True)
    created_at = serializers.DateTimeField()
    result = serializers.UUIDField(allow_null=True)


class ReviewChangeListSerializer(serializers.Serializer):
    results = ReviewChangeSerializer(many=True)


class ReviewDecisionSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    status = serializers.CharField()
    words = serializers.CharField()


class ReviewClusterSerializer(serializers.Serializer):
    accepted = serializers.IntegerField()
    stale = serializers.IntegerField()
    words = serializers.CharField()


class ReviewRunSerializer(serializers.Serializer):
    task = serializers.IntegerField()
    words = serializers.CharField()


class ReviewContextView(APIView):
    @extend_schema(
        operation_id="api_member_memories_review_context",
        tags=TAGS,
        parameters=[OpenApiParameter("task", int, description="The worker's task id, if any.")],
        responses={200: OpenApiTypes.OBJECT, 403: DetailSerializer},
        description=(
            "The snapshot the Claude worker's review judges: the caller's private live memories "
            "with handles, household facts about them as context only, the last 7 days of their "
            "own words (tool and mail turns dropped on the engine), and the exact duplicates the "
            "engine merges itself. Refused while LEGION_MEMORY_REVIEW is off."
        ),
    )
    def get(self, request):
        from claude_session.models import ClaudeTask
        from household.tenancy import scoped

        task_id = None
        raw = request.query_params.get("task")
        if raw and raw.isdigit():
            task = scoped(ClaudeTask, request).filter(pk=int(raw), user=request.user).first()
            task_id = task.pk if task else None
        machine = ""
        token = getattr(request, "auth", None)
        if token is not None and hasattr(token, "claude_machines"):
            first = token.claude_machines.order_by("-last_seen_at").first()
            machine = first.name if first else ""
        try:
            return Response(review.build_context(request, task_id=task_id, machine=machine))
        except review.ReviewRefused as exc:
            return _refused(exc)


class ReviewChangesView(APIView):
    parser_classes = [JSONParser]

    @extend_schema(
        operation_id="api_member_memories_review_changes_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "status", str, enum=[*CHANGE_STATUSES, "all"],
                description="Default pending.",
            )
        ],
        responses={200: ReviewChangeListSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description="The review's changes about the caller, newest first: pending by default.",
    )
    def get(self, request):
        wanted = (request.query_params.get("status") or "pending").strip()
        if wanted != "all" and wanted not in CHANGE_STATUSES:
            return Response(
                {"detail": f"Nothing was read. status must be one of "
                           f"{', '.join(CHANGE_STATUSES)} or all."},
                status=400,
            )
        try:
            rows = review.changes_listing(request, None if wanted == "all" else wanted)
        except review.ReviewRefused as exc:
            return _refused(exc)
        return Response({"results": rows})

    @extend_schema(
        operation_id="api_member_memories_review_changes_submit",
        tags=TAGS,
        request=ReviewSubmitSerializer,
        responses={
            200: ReviewAnswerSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
            409: DetailSerializer,
            410: DetailSerializer,
        },
        description=(
            "The worker's change list for one context. Exact duplicates merge by code; every "
            "other change passes its gate or becomes a proposal. One transaction. A repeated "
            "submit answers the first result. `dry_run` writes nothing. The answer carries "
            "counts and verdicts by handle, never memory text."
        ),
    )
    def post(self, request):
        try:
            return Response(review.submit(request, request.data))
        except review.ReviewRefused as exc:
            return _refused(exc)


class ReviewChangeDecisionView(APIView):
    verb = ""

    def post(self, request, change_id: int):
        action = {"accept": review.accept, "dismiss": review.dismiss, "revert": review.revert}
        try:
            return Response(action[self.verb](request, change_id))
        except review.ReviewRefused as exc:
            return _refused(exc)


_DECISION_RESPONSES = {
    200: ReviewDecisionSerializer,
    403: DetailSerializer,
    404: DetailSerializer,
    409: DetailSerializer,
}


class ReviewAcceptView(ReviewChangeDecisionView):
    verb = "accept"

    @extend_schema(
        operation_id="api_member_memories_review_accept",
        tags=TAGS,
        request=None,
        responses=_DECISION_RESPONSES,
        description="Applies a pending change. 409 and `stale` when its memories moved since.",
    )
    def post(self, request, change_id: int):
        return super().post(request, change_id)


class ReviewDismissView(ReviewChangeDecisionView):
    verb = "dismiss"

    @extend_schema(
        operation_id="api_member_memories_review_dismiss",
        tags=TAGS,
        request=None,
        responses=_DECISION_RESPONSES,
        description="Dismisses a pending change. Nothing about the memories changes.",
    )
    def post(self, request, change_id: int):
        return super().post(request, change_id)


class ReviewRevertView(ReviewChangeDecisionView):
    verb = "revert"

    @extend_schema(
        operation_id="api_member_memories_review_revert",
        tags=TAGS,
        request=None,
        responses=_DECISION_RESPONSES,
        description=(
            "Undoes an applied or accepted change: restores the rows it retired and deletes only "
            "the row the review wrote."
        ),
    )
    def post(self, request, change_id: int):
        return super().post(request, change_id)


class ReviewClusterAcceptView(APIView):
    @extend_schema(
        operation_id="api_member_memories_review_cluster_accept",
        tags=TAGS,
        request=None,
        responses={200: ReviewClusterSerializer, 403: DetailSerializer, 404: DetailSerializer,
                   409: DetailSerializer},
        description="Accept all: every pending change in one group, each checked for staleness.",
    )
    def post(self, request, cluster: str):
        try:
            return Response(review.accept_cluster(request, cluster))
        except review.ReviewRefused as exc:
            return _refused(exc)


class ReviewRunView(APIView):
    @extend_schema(
        operation_id="api_member_memories_review_run",
        tags=TAGS,
        request=None,
        responses={200: ReviewRunSerializer, 403: DetailSerializer, 404: DetailSerializer,
                   409: DetailSerializer},
        description="Queues one memory review on the caller's own Alienware now.",
    )
    def post(self, request):
        try:
            return Response(review.run_now(request))
        except review.ReviewRefused as exc:
            return _refused(exc)
