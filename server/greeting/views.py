"""`/api/greeting/`: the facts a greeting or check-in may mention (ADR 0091, alfred-greets ticket
01). The phone builds every greeting from `GET facts` and adds the current place itself; the
engine tool `greeting_facts` serves the same pack (`greeting/facts.py`).

- `GET  /api/greeting/facts`   the signed-in member's pack: five sources, each facts, empty or
                               unreadable (with the reason in words); topics answered or silenced
                               are left out, raised ones listed in `said`
- `POST /api/greeting/topics`  the phone records a topic raised, answered or silenced

Both refuse a recruiter's visit first (and `POST` a Claude worker token), then read and write
through `visible()`: one member's topics are never another's.
"""

from __future__ import annotations

from drf_spectacular.utils import extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from greeting import facts, service
from greeting.models import STATES, TOPIC_KEY_MAX_CHARS

TAGS = ["greeting"]


class SourceSerializer(serializers.Serializer):
    state = serializers.ChoiceField(choices=facts.STATES)
    reason = serializers.CharField(
        allow_null=True, help_text="Why the source could not be read, in words; null otherwise."
    )
    says = serializers.CharField(help_text="The source's state in the engine's own words.")
    note = serializers.CharField(allow_null=True)
    left_out = serializers.IntegerField(help_text="Items left out as answered or silenced.")
    items = serializers.ListField(
        child=serializers.DictField(),
        help_text=(
            "Each carries a stable `topic` key, `raised`, and `says`, the engine's sentence. "
            "Times are ISO 8601 with the household's UTC offset; money is integer cents."
        ),
    )


class SourcesSerializer(serializers.Serializer):
    ended_events = SourceSerializer()
    today_events = SourceSerializer()
    due_soon = SourceSerializer()
    body = SourceSerializer()
    money = SourceSerializer()


class TopicsReadSerializer(serializers.Serializer):
    state = serializers.ChoiceField(choices=facts.TOPICS_READ_STATES)
    reason = serializers.CharField(allow_null=True)


class FactsSerializer(serializers.Serializer):
    generated_at = serializers.DateTimeField()
    today = serializers.DateField(help_text="Today in the household's own clock.")
    utc_offset = serializers.CharField(help_text="Like -05:00. Never a zone id.")
    zone_note = serializers.CharField(
        allow_null=True, help_text="Set when the household has no timezone and times are UTC."
    )
    sources = SourcesSerializer()
    said = serializers.ListField(
        child=serializers.CharField(), help_text="Topic keys already raised: never ask again."
    )
    closed = serializers.ListField(
        child=serializers.CharField(),
        help_text="Topic keys answered or silenced, the phone's own place keys included.",
    )
    topics = TopicsReadSerializer()


class TopicWriteSerializer(serializers.Serializer):
    topic_key = serializers.CharField(max_length=TOPIC_KEY_MAX_CHARS)
    state = serializers.ChoiceField(choices=STATES)


class TopicSerializer(serializers.Serializer):
    topic_key = serializers.CharField()
    state = serializers.ChoiceField(choices=STATES)
    at = serializers.DateTimeField()
    changed = serializers.BooleanField(
        help_text="False when it already was this, or is closed and a raise was ignored."
    )


def _refused(reason):
    return Response({"detail": reason}, status=status.HTTP_403_FORBIDDEN)


class GreetingFactsView(APIView):
    @extend_schema(
        operation_id="api_greeting_facts",
        tags=TAGS,
        responses={200: FactsSerializer, 403: DetailSerializer},
        description=(
            "The facts a greeting or check-in may mention for the signed-in member, and nothing "
            "else: events that ended in the last 24 hours, today's remaining events, open to-dos "
            "and Canvas work due within 24 hours, today's workout and the latest weigh-in, and "
            "each capped budget's pace (the month-end projection is an estimate). Each source is "
            "facts, empty or unreadable."
        ),
    )
    def get(self, request):
        reason = service.refusal(request)
        if reason:
            return _refused(reason)
        return Response(facts.pack(request))


class GreetingTopicView(APIView):
    @extend_schema(
        operation_id="api_greeting_topics_record",
        tags=TAGS,
        request=TopicWriteSerializer,
        responses={200: TopicSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "Records that Alfred raised a topic, or that the member answered or silenced it. "
            "An answered or silenced topic is left out of the facts from then on and is never "
            "reopened by a later raise."
        ),
    )
    def post(self, request):
        reason = service.write_refusal(request)
        if reason:
            return _refused(reason)
        body = TopicWriteSerializer(data=request.data)
        if not body.is_valid():
            errors = "; ".join(
                f"{field}: {' '.join(str(m) for m in messages)}"
                for field, messages in body.errors.items()
            )
            return Response(
                {"detail": f"Nothing was recorded. {errors}"}, status=status.HTTP_400_BAD_REQUEST
            )
        key = service.clean_key(body.validated_data["topic_key"])
        if key is None:
            return Response({"detail": service.BAD_TOPIC_KEY}, status=status.HTTP_400_BAD_REQUEST)
        row, changed = service.record(request, key, body.validated_data["state"])
        return Response({**service.topic_dict(row), "changed": changed})
