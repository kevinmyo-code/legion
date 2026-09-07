"""Shared, cross-domain views. Ticket 01 puts exactly one thing here: the
health check. DRF routers and shared serializer mixins for the domain API
land in ticket 04 - this file is deliberately thin until then.
"""
from __future__ import annotations

from django.db import connection
from django.db.utils import OperationalError
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.response import Response


class HealthSerializer(serializers.Serializer):
    """The 200 body. `db` is the only key, and it is only ever `"ok"` -
    anything else took the 503 branch below."""

    db = serializers.CharField(help_text='Always "ok" on a 200.')


class HealthErrorSerializer(serializers.Serializer):
    """The 503 body. `detail` is psycopg's own message, verbatim - the
    check says what it actually found rather than a generic failure."""

    db = serializers.CharField(help_text='Always "error" on a 503.')
    detail = serializers.CharField()


@extend_schema(
    # No `operation_id` here on purpose: `legion/urls.py` mounts this ONE
    # view on both `/healthz` and `/health` (Cloud Run's frontend reserves
    # the first string - see that file's comment), and a fixed operation id
    # would give two paths the same one, which is an invalid document.
    # Leaving it path-derived yields `healthz_retrieve` and
    # `health_retrieve`, which is correct and distinct.
    tags=["health"],
    responses={
        200: OpenApiResponse(
            response=HealthSerializer,
            description="The database answered `SELECT 1`.",
        ),
        503: OpenApiResponse(
            response=HealthErrorSerializer,
            description="The database did not answer. The body carries psycopg's own message.",
        ),
    },
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
