"""Device-token authentication.

Every limb (phone, browser, future robot) authenticates with one token per
device, header `Authorization: Token <key>`. This is deliberately not DRF's
stock `TokenAuthentication`: that model has one token per user, so revoking
a lost phone would also sign out every other device the same user owns.
ADR 0044 rule 3 calls for a token 'revocable alone'.
"""
from __future__ import annotations

from django.utils import timezone
from rest_framework import authentication, exceptions

from household.models import DeviceToken, hash_device_key

WORKER_ROUTE_REFUSAL = (
    "Nothing was read or written. This is the Claude worker's token (scope `claude_worker`): it "
    "reaches only the worker's own routes."
)

AUTH_HEADER_PREFIX = "Token"


class DeviceTokenAuthentication(authentication.BaseAuthentication):
    def authenticate(self, request):
        header = authentication.get_authorization_header(request).split()
        if not header or header[0].decode("latin-1") != AUTH_HEADER_PREFIX:
            return None
        if len(header) != 2:
            raise exceptions.AuthenticationFailed(
                "Authorization header must be 'Token <key>' with no extra parts."
            )
        raw_key = header[1].decode("latin-1")
        user, token = self._authenticate_key(raw_key)
        # Architecture upgrade 2 (2026-11-10): a `claude_worker` token is refused everywhere but
        # the worker's routes, here at the one door every token comes through (REST and `/mcp`).
        if token.scope == DeviceToken.SCOPE_CLAUDE_WORKER:
            from claude_session.tokens import worker_route_allowed

            if not worker_route_allowed(request.path, request.method):
                raise exceptions.PermissionDenied(WORKER_ROUTE_REFUSAL)
        return user, token

    def _authenticate_key(self, raw_key: str):
        key_hash = hash_device_key(raw_key)
        try:
            token = DeviceToken.objects.select_related("user").get(key_hash=key_hash)
        except DeviceToken.DoesNotExist as exc:
            raise exceptions.AuthenticationFailed("Invalid token.") from exc

        if token.is_revoked:
            raise exceptions.AuthenticationFailed("Token has been revoked.")
        # recruiter-demo ticket 02: a token with an `expires_at` dies on its
        # own. Null is "never", so no token minted before the column existed
        # is touched.
        if token.is_expired:
            raise exceptions.AuthenticationFailed(
                "Token has expired. Nothing was read or written; ask for a new one."
            )
        if not token.user.is_active:
            raise exceptions.AuthenticationFailed("User is inactive.")

        token.last_seen_at = timezone.now()
        token.save(update_fields=["last_seen_at"])

        return (token.user, token)

    def authenticate_header(self, request):
        return AUTH_HEADER_PREFIX
