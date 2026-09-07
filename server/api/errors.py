"""Documentation-only shapes for the error bodies this API actually
returns. Nothing here is imported by a view for behaviour - every hand-rolled
`APIView` in this codebase already builds `Response({"detail": ...}, status=...)`
itself - these exist so drf-spectacular has a typed `responses=` entry
instead of falling back to "unable to guess serializer" and dropping the
view from the schema entirely.
"""
from __future__ import annotations

from rest_framework import serializers


class DetailErrorSerializer(serializers.Serializer):
    """The one shape every 400/401/404 in this API returns:
    `{"detail": "<human-readable reason>"}`."""

    detail = serializers.CharField()
