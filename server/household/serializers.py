"""Shape-only serializers for the auth endpoints. Nothing here does gate or
provenance work - that starts in ticket 03 - this file just describes the
request/response bodies the auth endpoints promise.

**2026-09-10 (web-and-households ticket 03 narrow slice):** added the
session-login request shape and a `household` field shared by `/me` and
session login, so a browser can tell which family it landed in without a
second round trip. Device-token login and `/me`'s `device_name` are
unchanged - a session has no device to name, so it sends `""`."""
from __future__ import annotations

from rest_framework import serializers


class LoginRequestSerializer(serializers.Serializer):
    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)
    device_name = serializers.CharField(max_length=255)


class LoginResponseSerializer(serializers.Serializer):
    token = serializers.CharField()
    user_id = serializers.UUIDField()


class SessionLoginRequestSerializer(serializers.Serializer):
    """`POST /api/auth/session/login`. No `device_name` - a browser session
    is not a device token (ADR 0044 rule 3: different credentials, different
    revocation stories) and mints none."""

    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)


class HouseholdSummarySerializer(serializers.Serializer):
    id = serializers.UUIDField()
    name = serializers.CharField()
    role = serializers.CharField()


class MeResponseSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    email = serializers.EmailField()
    device_name = serializers.CharField()
    household = HouseholdSummarySerializer(allow_null=True)
