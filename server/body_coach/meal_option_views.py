"""`/api/body/coach/meal-options/`: what to eat from what's left (body coach 2, 2026-11-06).

| Route | Does |
|---|---|
| `GET meal-options/` | the clock, what is left today, ranked options from saved meals and recent groceries, `words` |
| `POST meal-options/log/` | logs one option by its `option_id`; the figures come from the rows, never the body |

Every figure is an estimate (CLAUDE.md section 4 rule 5). Ranking and words:
`body_coach/suggest.py`.
A recruiter is refused (`household.recruiter_routes`, default deny).
"""  # noqa: E501 (route table rows)

from __future__ import annotations

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.body import MealLogSerializer
from api.schema import DetailSerializer
from body_coach import meal_options

TAGS = ["body-coach"]


class MealOptionsView(APIView):
    @extend_schema(
        operation_id="api_body_coach_meal_options",
        tags=TAGS,
        responses={200: OpenApiResponse(description="What is left and the ranked options.")},
        description="kcal and protein left today (carbs and fat when the meal target sets "
        "them), the household-local time, and up to five options from saved meals and groceries "
        "bought in the last 14 days (as bought, never divided), ranked to fit what is left and "
        "protein per kcal first. Every figure is an estimate. A read; nothing is written.",
    )
    def get(self, request):
        return Response(meal_options.options_body(request))


class MealOptionLogView(APIView):
    @extend_schema(
        operation_id="api_body_coach_meal_options_log",
        tags=TAGS,
        request=None,
        responses={
            201: OpenApiResponse(description="Logged."),
            400: DetailSerializer,
            404: DetailSerializer,
        },
        description="Logs one meal option now by `option_id` (from `GET meal-options/`): one "
        "meal_logs row whose estimated figures are recomputed from the saved meal or groceries. "
        "No figure is taken from the request.",
    )
    def post(self, request):
        data = request.data if isinstance(request.data, dict) else {}
        unknown = sorted(set(data) - {"option_id"})
        if unknown:
            return Response(
                {
                    "detail": (
                        f"Nothing was logged. Only option_id is read; got {', '.join(unknown)}."
                    )
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            row, words = meal_options.log_option(request, data.get("option_id"))
        except meal_options.OptionRefused as exc:
            return Response({"detail": exc.message}, status=exc.code)
        return Response(
            {"meal_log": MealLogSerializer(row).data, "words": words},
            status=status.HTTP_201_CREATED,
        )
