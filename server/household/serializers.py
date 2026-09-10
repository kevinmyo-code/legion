"""Shape-only serializers for the auth endpoints. Nothing here does gate or
provenance work - that starts in ticket 03 - this file just describes the
request/response bodies the auth endpoints promise.

**2026-09-10 (web-and-households ticket 03 narrow slice):** added the
session-login request shape, reusing `MeResponseSerializer` for its
response body - same shape `/me` already returns. Device-token login and
`/me`'s `device_name` are unchanged - a session has no device to name, so
it sends `""`. **No `household` field was added here**:
`tests/test_tenancy.py::test_no_openapi_component_declares_household_id`
is a standing rule that no OpenAPI component may expose `household` or
`household_id` at all - a client cannot honestly read or choose its own
tenancy, ADR 0045's "one user, one household" makes the id redundant to
ask for, and ticket 03's own design puts household info on its own
`GET /api/households/me` endpoint (not yet built), not on every response
that happens to authenticate someone."""
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


class MeResponseSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    email = serializers.EmailField()
    device_name = serializers.CharField()
