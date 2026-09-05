"""Shape-only serializers for the auth endpoints. Nothing here does gate or
provenance work - that starts in ticket 03 - this file just describes the
three request/response bodies ticket 01 promises."""
from __future__ import annotations

from rest_framework import serializers


class LoginRequestSerializer(serializers.Serializer):
    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)
    device_name = serializers.CharField(max_length=255)


class LoginResponseSerializer(serializers.Serializer):
    token = serializers.CharField()
    user_id = serializers.UUIDField()


class MeResponseSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    email = serializers.EmailField()
    device_name = serializers.CharField()
