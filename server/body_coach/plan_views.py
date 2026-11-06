"""`/api/body/coach/plan/`: the plan, drafted with Alfred, locked in, adjusted (body coach 2).

| Route | Does |
|---|---|
| `GET plan/` | the active plan, a draft, the history and `words`; the first draft is built here when the profile is complete and there is no plan at all |
| `POST plan/draft/` | a draft from the active plan and choices (the programme's, `rate_pct`, `alfred_sets_target`) |
| `POST plan/<id>/lock/` | the draft becomes the active version: target, programme and rate go live |
| `POST plan/<id>/discard/` | the draft goes; nothing changes |

The caller's own plan only (`visible()`); a recruiter is refused (`household.recruiter_routes`).
Every figure is the rules' (`body_coach/plans.py`) and an estimate.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from body_coach import plans

TAGS = ["body-coach"]
_REFUSED = {400: DetailSerializer, 404: DetailSerializer, 409: DetailSerializer}


def _refusal(exc: plans.PlanRefused) -> Response:
    return Response({"detail": exc.message}, status=exc.code)


class PlanView(APIView):
    @extend_schema(
        operation_id="api_body_coach_plan",
        tags=TAGS,
        responses={200: OpenApiResponse(description="The plan, a draft, the history, words.")},
        description="The caller's active plan, any draft waiting to be locked in, the version "
        "history with a reason each, and words. With a complete profile and no plan at all, the "
        "first draft is built here and the words ask to lock it in.",
    )
    def get(self, request):
        return Response(plans.plan_body(request))


class PlanDraftView(APIView):
    @extend_schema(
        operation_id="api_body_coach_plan_draft",
        tags=TAGS,
        request=None,
        responses={201: OpenApiResponse(description="Drafted."), **_REFUSED},
        description="Builds a draft from the active plan and structured choices: the programme's "
        "(sessions_per_week, split, without_equipment, with_equipment, avoid, clear_avoid, "
        "cardio, swap_exercise, swap_to), rate_pct (0.25 to 1.0; 0.5 by rule for lean while "
        "lifting) and alfred_sets_target. Alfred's target is the engine's estimate at the rate. "
        "Nothing goes live until it is locked in.",
    )
    def post(self, request):
        data = request.data if isinstance(request.data, dict) else None
        if data is None:
            return Response(
                {"detail": "Nothing was drafted. The body must be a JSON object."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            row = plans.build_draft(request, data)
        except plans.PlanRefused as exc:
            return _refusal(exc)
        return Response(
            {"draft": plans.plan_dict(row), "words": plans.draft_words(row)},
            status=status.HTTP_201_CREATED,
        )


class PlanLockView(APIView):
    @extend_schema(
        operation_id="api_body_coach_plan_lock",
        tags=TAGS,
        request=None,
        responses={200: OpenApiResponse(description="Locked in."), **_REFUSED},
        description="Locks the draft in: it becomes the active version, today's meal target is "
        "written from it (unless the target is the member's own), the programme takes its state "
        "and the profile its rate. The words say what changed, after it changed.",
    )
    def post(self, request, plan_id):
        try:
            row = plans.lock(request, plan_id)
        except plans.PlanRefused as exc:
            return _refusal(exc)
        return Response({"active": plans.plan_dict(row), "words": plans.lock_words(row)})


class PlanDiscardView(APIView):
    @extend_schema(
        operation_id="api_body_coach_plan_discard",
        tags=TAGS,
        request=None,
        responses={200: OpenApiResponse(description="Discarded."), **_REFUSED},
        description="Discards the draft; the active plan, the target and the programme stay.",
    )
    def post(self, request, plan_id):
        try:
            row = plans.discard(request, plan_id)
        except plans.PlanRefused as exc:
            return _refusal(exc)
        return Response(
            {"draft": plans.plan_dict(row), "words": "Draft discarded; nothing changed."}
        )
