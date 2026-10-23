"""`GET /api/brief/latest` and `POST /api/brief/refresh` (ai-upgrade ticket 02,
ruling 6).

Both answer for the signed-in member only: the brief is per member because
its Canvas section honours ADR 0052 privacy (`brief/models.py`). Reads go
through `household.tenancy.scoped`, then narrow to the member. Neither body
carries a user id or a household id.

Refresh spends the household's Gemini key (one News call), so it has its own
throttle scope, per member. It runs through `ingest.jobs.run_job`, so it never
overlaps the 05:00 build: when that holds the lock the answer is 409 in words.
"""

from __future__ import annotations

from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from brief.build import build_for_household, payload
from brief.models import SECTION_KEYS, SECTION_STATUSES, Brief, Trigger
from household.tenancy import household_of, scoped
from ingest.jobs import run_job
from ingest.models import Outcome, Source

NO_BRIEF = (
    "No brief has been built for you yet. One is built at 05:00 each day, or press Refresh to "
    "build one now. Nothing was read."
)
LOCKED = (
    "A brief is being built for this household right now, so this refresh did not run and "
    "nothing changed. Try again in a minute."
)


class BriefLinkSerializer(serializers.Serializer):
    title = serializers.CharField(help_text="The source's name, usually its domain.")
    url = serializers.CharField(help_text="The source link Gemini's grounding returned.")


class BriefLineSerializer(serializers.Serializer):
    text = serializers.CharField(help_text="One sentence, composed by the engine. Show as is.")
    unverified = serializers.BooleanField(
        help_text="True when a figure in `text` rests on bank rows no statement gate checked. "
        "The sentence already says so; a client may also mark it."
    )
    links = BriefLinkSerializer(many=True, help_text="Sources. Every News item has at least one.")


class BriefSectionSerializer(serializers.Serializer):
    key = serializers.ChoiceField(choices=list(SECTION_KEYS))
    title = serializers.CharField()
    status = serializers.ChoiceField(
        choices=list(SECTION_STATUSES),
        help_text="`empty`: the source has nothing, and a line says so in words. `failed`: the "
        "section could not be built, and `note` says why.",
    )
    lines = BriefLineSerializer(many=True)
    note = serializers.CharField(
        allow_null=True,
        help_text="A sentence about the section itself: Canvas freshness, why News failed.",
    )


class BriefSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    brief_date = serializers.DateField(help_text="The household-local date it was built for.")
    built_at = serializers.DateTimeField()
    trigger = serializers.ChoiceField(choices=Trigger.choices)
    age_sentence = serializers.CharField(
        help_text='How old the brief is, in words ("Built 3 hours ago, by the 05:00 build."). '
        "A client showing a cached copy says its own age instead."
    )
    news_ok = serializers.BooleanField()
    notes = serializers.ListField(
        child=serializers.CharField(), help_text="Sentences about the whole brief."
    )
    sections = BriefSectionSerializer(
        many=True, help_text="Canvas, Money, Car, News, in that order."
    )


class BriefLatestView(APIView):
    @extend_schema(
        operation_id="api_brief_latest_retrieve",
        tags=["brief"],
        responses={
            200: OpenApiResponse(
                response=BriefSerializer,
                description="The signed-in member's most recent brief.",
            ),
            404: OpenApiResponse(
                response=DetailSerializer, description="No brief has been built for this member."
            ),
        },
    )
    def get(self, request):
        brief = scoped(Brief, request).filter(user=request.user).order_by("-built_at").first()
        if brief is None:
            return Response({"detail": NO_BRIEF}, status=status.HTTP_404_NOT_FOUND)
        return Response(BriefSerializer(payload(brief, timezone.now())).data)


class BriefRefreshView(APIView):
    throttle_scope = "brief_refresh"

    @extend_schema(
        operation_id="api_brief_refresh_create",
        tags=["brief"],
        request=None,
        responses={
            201: OpenApiResponse(
                response=BriefSerializer,
                description="A new brief, built now for the signed-in member. Makes one Gemini "
                "call on the household key for News.",
            ),
            409: OpenApiResponse(
                response=DetailSerializer,
                description="The household's brief is being built already. Nothing changed.",
            ),
            503: OpenApiResponse(
                response=DetailSerializer,
                description="The brief could not be built. The previous brief is unchanged.",
            ),
        },
    )
    def post(self, request):
        household = household_of(request)
        built: list[Brief] = []

        def job(run):
            built.extend(build_for_household(household, Trigger.REFRESH, members=[request.user]))
            run.rows_written = len(built)

        run = run_job(Source.BRIEF, household, job)
        if run.outcome == Outcome.SKIPPED_LOCKED:
            return Response({"detail": LOCKED}, status=status.HTTP_409_CONFLICT)
        if run.outcome != Outcome.OK or not built:
            return Response(
                {
                    "detail": "The brief could not be built, so nothing new was stored and the "
                    f"previous brief is unchanged. {run.error or ''}".strip()
                },
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        return Response(
            BriefSerializer(payload(built[0], timezone.now())).data,
            status=status.HTTP_201_CREATED,
        )
