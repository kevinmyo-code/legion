"""Shared, cross-domain views. Ticket 01 puts exactly one thing here: the
health check. DRF routers and shared serializer mixins for the domain API
land in ticket 04 - this file is deliberately thin until then.
"""
from __future__ import annotations

from django.db import connection
from django.db.utils import OperationalError
from drf_spectacular.utils import extend_schema
from rest_framework import serializers
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.response import Response


class HealthzOkSerializer(serializers.Serializer):
    """Documentation-only - see `api/errors.py`'s own doc comment. `healthz`
    builds its `Response(...)` by hand; this just types what it sends."""

    db = serializers.ChoiceField(choices=["ok"])


class HealthzErrorSerializer(serializers.Serializer):
    db = serializers.ChoiceField(choices=["error"])
    detail = serializers.CharField()


@extend_schema(
    request=None,
    responses={200: HealthzOkSerializer, 503: HealthzErrorSerializer},
    auth=[],
)
@api_view(["GET"])
@authentication_classes([])
@permission_classes([])
def healthz(request):
    """`GET /healthz`. Unauthenticated on purpose - a load balancer or an
    uptime check has no device token - and it says exactly what it checked:
    whether the database answered a query, nothing more. Section 7's
    outcome-verb rule applies here too: 'ok' is never returned unless the
    query actually ran.
    """
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
    except OperationalError as exc:
        return Response({"db": "error", "detail": str(exc)}, status=503)
    return Response({"db": "ok"})
