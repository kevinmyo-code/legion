"""Registers `DeviceTokenAuthentication` with drf-spectacular.

Without this, every authenticated view in the schema warns "could not
resolve authenticator... There was no OpenApiAuthenticationExtension
registered for that class" and `securitySchemes` comes out empty - a
generated client then has no idea it needs to send a header at all.

The scheme is API-key-in-header, matching `household/authentication.py`
exactly: header `Authorization`, value `Token <key>` (see
`DeviceTokenAuthentication.authenticate`/`authenticate_header`, and
`AUTH_HEADER_PREFIX`).
"""
from __future__ import annotations

from drf_spectacular.extensions import OpenApiAuthenticationExtension


class DeviceTokenScheme(OpenApiAuthenticationExtension):
    target_class = "household.authentication.DeviceTokenAuthentication"
    name = "deviceTokenAuth"

    def get_security_definition(self, auto_schema):
        return {
            "type": "apiKey",
            "in": "header",
            "name": "Authorization",
            "description": (
                "One token per device, never per user - revoking a lost "
                "phone does not sign out any other device. Send as "
                "`Authorization: Token <key>`, the literal word `Token` "
                "followed by a space then the raw key returned by "
                "`POST /api/auth/login`."
            ),
        }
