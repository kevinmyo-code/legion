"""OAuth 2.1 for `/mcp`: LEGION's own Django is the authorization server
(ai-upgrade ticket 03, Kevin's rulings 2026-10-22).

django-oauth-toolkit (DOT) 3.4.1 does the protocol: the authorize and token
endpoints, PKCE, the grant and token store, rotating refresh tokens with reuse
protection, RFC 8414 server metadata, RFC 9728 protected-resource metadata,
Dynamic Client Registration (RFC 7591) and Client ID Metadata Documents. This
module is only what LEGION decides on top of it:

- **One resource.** Every token is bound (RFC 8707) to this engine's own
  `/mcp` URL, derived from the request so a test server, the Cloud Run URL and
  a custom domain each mint for themselves. A request naming any other
  `resource` is refused (`invalid_target`); a request naming none is bound to
  `/mcp` anyway (a client that omits it gets an audience-restricted token
  rather than an unrestricted one). `/mcp` then checks the token's audience
  itself (`McpOAuthAuthentication`), not only DOT's prefix check.
- **Redirect allowlist** (`settings.LEGION_OAUTH_REDIRECT_URIS` exact,
  `settings.LEGION_OAUTH_REDIRECT_PREFIXES` by prefix). Every redirect URI a
  client registers, by DCR or in a CIMD document, must be on it, or nothing is
  registered. CIMD is further limited to `CIMD_ALLOWED_HOSTS`.
- **Grant types are narrowed, never refused for extras.** Claude's hosted CIMD
  document declares `authorization_code`, `refresh_token` and a `jwt-bearer`
  grant; DOT allows one non-refresh grant per application, so anything but
  `authorization_code` is dropped before DOT sees it.
- **`offline_access` is ignored**, not refused: Gemini is reported to echo it
  back, and the MCP spec says not to advertise it. Refresh tokens are issued
  regardless.
- **A token is one member.** `/mcp` refuses a token with no user (DCR's own
  registration tokens live in the same table), without the `mcp` scope, or
  for another audience. Household scoping is then the existing choke point:
  the tools run as `request.user`, through `household_of`.
"""

from __future__ import annotations

import json
from urllib.parse import urlsplit

from django.conf import settings
from django.http import HttpResponse, JsonResponse
from django.utils.html import escape
from oauth2_provider.cimd import CIMDError, SafeMetadataFetcher
from oauth2_provider.contrib.rest_framework.authentication import (
    OAuth2ProtectedResourceAuthentication,
)
from oauth2_provider.oauth2_validators import OAuth2Validator
from oauth2_provider.views import (
    AuthorizationView,
    DynamicClientRegistrationManagementView,
    DynamicClientRegistrationView,
    OAuthProtectedResourceMetadataView,
    OAuthServerMetadataView,
    RevokeTokenView,
    TokenView,
)
from oauthlib.oauth2.rfc6749 import errors
from rest_framework import exceptions

MCP_PATH = "/mcp"
MCP_SCOPE = "mcp"
# What the consent page says the client may do, in words (ruling 5).
MCP_SCOPE_WORDS = (
    "Read and write your LEGION records: events, lists, the bought log, places, body, "
    "ledger, pantry and fleet, for your household only."
)
# Scopes a client may ask for that are dropped rather than refused.
IGNORED_SCOPES = frozenset({"offline_access"})
# The only grants a registered client keeps.
KEPT_GRANT_TYPES = ("authorization_code", "refresh_token")
RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource" + MCP_PATH

MCP_OFF_WORDS = (
    "Nothing was authorized. This engine's MCP endpoint is switched off (LEGION_MCP is not on), "
    "so it does not act as an authorization server either."
)


def mcp_enabled() -> bool:
    return bool(getattr(settings, "LEGION_MCP", False))


# =============================================================================
# The one resource
# =============================================================================


def canonical_resource(request) -> str:
    """This engine's `/mcp` URL as the request sees it (scheme from the proxy
    header on Cloud Run)."""
    return request.build_absolute_uri(MCP_PATH)


def _canonical_for_oauthlib(request) -> str:
    """`canonical_resource` from inside the validator, where only the oauthlib
    request exists: its `uri` is a bare path at the authorize step, but its
    headers are the Django request's META (DOT's `extract_headers`), so a
    Django request is rebuilt from them, the way DOT's own `oidc_issuer` does,
    and the scheme taken from DOT's secure marker."""
    from oauth2_provider.settings import _PhonyHttpRequest

    django_request = _PhonyHttpRequest()
    django_request.META = dict(request.headers)
    if request.headers.get("X_DJANGO_OAUTH_TOOLKIT_SECURE"):
        django_request._scheme = "https"
    return django_request.build_absolute_uri(MCP_PATH)


def _same_resource(given: str, canonical: str) -> bool:
    return given.rstrip("/") == canonical


# =============================================================================
# The redirect allowlist
# =============================================================================


def redirect_allowed(uri: str) -> bool:
    """True when `uri` is on the allowlist: equal to an exact entry, or under a
    prefix entry. https only, no userinfo, no fragment, no dot segments, so a
    prefix cannot be escaped with `..` or an `@`."""
    if not isinstance(uri, str):
        return False
    try:
        parts = urlsplit(uri)
    except ValueError:
        return False
    if parts.scheme != "https" or not parts.hostname or parts.fragment:
        return False
    if parts.username or parts.password or "@" in parts.netloc:
        return False
    if any(segment in (".", "..") for segment in parts.path.split("/")):
        return False
    exact = getattr(settings, "LEGION_OAUTH_REDIRECT_URIS", ())
    prefixes = getattr(settings, "LEGION_OAUTH_REDIRECT_PREFIXES", ())
    return uri in exact or any(uri.startswith(prefix) for prefix in prefixes)


def refused_redirects(uris) -> list:
    if not isinstance(uris, list):
        return [uris]
    return [uri for uri in uris if not redirect_allowed(uri)]


def narrowed_grant_types(grant_types) -> list[str]:
    """Only `authorization_code` (and `refresh_token`) survive. A list with
    neither still asks for nothing LEGION will grant, so it becomes the code
    grant: the client's redirect is what is vetted, not its wish list."""
    if not isinstance(grant_types, list):
        return ["authorization_code", "refresh_token"]
    kept = [g for g in grant_types if g in KEPT_GRANT_TYPES]
    if "authorization_code" not in kept:
        kept.insert(0, "authorization_code")
    return kept


# =============================================================================
# CIMD (Claude)
# =============================================================================


def vet_client_metadata(metadata: dict) -> dict:
    """A fetched CIMD document, refused unless every redirect URI is
    allowlisted, with its grant types narrowed. Raises `CIMDError`, which DOT
    turns into "unknown client" and a short backoff."""
    redirects = metadata.get("redirect_uris")
    if not isinstance(redirects, list) or not redirects:
        raise CIMDError("redirect_uris must be a non-empty array")
    refused = refused_redirects(redirects)
    if refused:
        raise CIMDError(f"redirect URIs not on LEGION's allowlist: {refused!r}")
    vetted = dict(metadata)
    vetted["grant_types"] = narrowed_grant_types(metadata.get("grant_types"))
    return vetted


class AllowlistedMetadataFetcher(SafeMetadataFetcher):
    """DOT's SSRF-hardened fetcher, then `vet_client_metadata`."""

    def fetch(self, client_id):
        metadata, max_age = super().fetch(client_id)
        return vet_client_metadata(metadata), max_age


# =============================================================================
# DCR (Gemini)
# =============================================================================


def _dcr_refusal(request):
    """A 400 in RFC 7591's words when the registration body names a redirect
    URI off the allowlist; otherwise None, with the body's grant types
    narrowed in place for DOT to read."""
    try:
        data = json.loads(request.body or b"{}")
    except (ValueError, UnicodeDecodeError):
        return None  # DOT answers malformed JSON itself.
    if not isinstance(data, dict):
        return None
    refused = refused_redirects(data.get("redirect_uris", []))
    if refused:
        return JsonResponse(
            {
                "error": "invalid_redirect_uri",
                "error_description": (
                    "Nothing was registered. This engine only registers clients whose "
                    f"redirect URIs are on its allowlist, and these are not: {refused!r}."
                ),
            },
            status=400,
        )
    data["grant_types"] = narrowed_grant_types(data.get("grant_types"))
    request._body = json.dumps(data).encode()
    return None


class _McpSwitch:
    """Every OAuth view answers 404 in words while `/mcp` is off, the same
    switch the endpoint itself honours (engine-mcp ticket 08)."""

    def dispatch(self, request, *args, **kwargs):
        if not mcp_enabled():
            return JsonResponse({"detail": MCP_OFF_WORDS}, status=404)
        return super().dispatch(request, *args, **kwargs)


class LegionDCRView(_McpSwitch, DynamicClientRegistrationView):
    def post(self, request, *args, **kwargs):
        return _dcr_refusal(request) or super().post(request, *args, **kwargs)


class LegionDCRManagementView(_McpSwitch, DynamicClientRegistrationManagementView):
    def put(self, request, client_id, *args, **kwargs):
        return _dcr_refusal(request) or super().put(request, client_id, *args, **kwargs)


# =============================================================================
# The authorization server
# =============================================================================


class LegionOAuth2Validator(OAuth2Validator):
    def validate_scopes(self, client_id, scopes, client, request, *args, **kwargs):
        kept = [s for s in scopes if s not in IGNORED_SCOPES]
        if not kept:
            kept = [MCP_SCOPE]
        scopes[:] = kept
        if getattr(request, "scopes", None) is not scopes and request is not None:
            request.scopes = kept
        return super().validate_scopes(client_id, kept, client, request, *args, **kwargs)

    def _bind_resource(self, request):
        canonical = _canonical_for_oauthlib(request)
        given = getattr(request, "resource", None)
        if isinstance(given, str):
            given = given.split()
        given = [r for r in (given or []) if r]
        wrong = [r for r in given if not _same_resource(r, canonical)]
        if wrong:
            raise errors.CustomOAuth2Error(
                error="invalid_target",
                description=(
                    f"This server issues tokens for {canonical} only, not for {wrong[0]}."
                ),
                request=request,
            )
        request.resource = [canonical]

    def _create_authorization_code(self, request, code):
        self._bind_resource(request)
        return super()._create_authorization_code(request, code)

    def _check_and_set_request_resource(self, request):
        super()._check_and_set_request_resource(request)
        self._bind_resource(request)


class LegionAuthorizationView(_McpSwitch, AuthorizationView):
    """DOT's consent view with LEGION's page, and a `resource` other than this
    engine's `/mcp` refused before the member is asked anything."""

    template_name = "engine_mcp/authorize.html"

    def get(self, request, *args, **kwargs):
        canonical = canonical_resource(request)
        wrong = [r for r in request.GET.getlist("resource") if not _same_resource(r, canonical)]
        if wrong:
            return HttpResponse(
                f"Nothing was authorized. This engine issues tokens for {escape(canonical)} "
                f"only, and this request asked for {escape(wrong[0])}.",
                status=400,
                content_type="text/plain; charset=utf-8",
            )
        return super().get(request, *args, **kwargs)

    def get_context_data(self, **kwargs):
        context = super().get_context_data(**kwargs)
        context["mcp_scope_words"] = MCP_SCOPE_WORDS
        redirect = context.get("redirect_uri") or ""
        context["redirect_host"] = urlsplit(redirect).hostname or ""
        return context


class LegionTokenView(_McpSwitch, TokenView):
    pass


class LegionRevokeTokenView(_McpSwitch, RevokeTokenView):
    pass


class LegionServerMetadataView(_McpSwitch, OAuthServerMetadataView):
    pass


class LegionResourceMetadataView(_McpSwitch, OAuthProtectedResourceMetadataView):
    """Both the root and the `/mcp` path-component document name the one
    resource there is, so a client reading either binds its token to `/mcp`."""

    def get_resource(self, request):
        return canonical_resource(request)


# =============================================================================
# The resource server: `/mcp`
# =============================================================================


class McpOAuthAuthentication(OAuth2ProtectedResourceAuthentication):
    """A Bearer token from this authorization server, for one member, for
    `/mcp`. Returns None for any other header (a `Token <key>` device token is
    `DeviceTokenAuthentication`'s), so the two coexist on one view. Its
    challenge carries `resource_metadata`, which is how a client finds where to
    sign in."""

    www_authenticate_realm = "legion"

    def get_resource_metadata_url(self, request):
        return request.build_absolute_uri(RESOURCE_METADATA_PATH)

    def authenticate(self, request):
        header = request.META.get("HTTP_AUTHORIZATION", "")
        if not header.lower().startswith("bearer "):
            return None
        result = super().authenticate(request)
        if result is None:
            error = getattr(request, "oauth2_error", {}) or {}
            raise exceptions.AuthenticationFailed(
                error.get("error_description")
                or "Nothing was read or written. This access token is not valid: it is unknown, "
                "expired or revoked. Sign in again from the client."
            )
        user, token = result
        if user is None or token.user_id is None:
            raise exceptions.AuthenticationFailed(
                "Nothing was read or written. This token belongs to no member (it is a client "
                "registration token, not a sign-in), so it cannot reach household data."
            )
        if MCP_SCOPE not in (token.scope or "").split():
            raise exceptions.AuthenticationFailed(
                f"Nothing was read or written. This token was not granted the `{MCP_SCOPE}` scope."
            )
        if canonical_resource(request) not in (token.resource or []):
            raise exceptions.AuthenticationFailed(
                "Nothing was read or written. This token was issued for a different resource "
                "than this engine's MCP endpoint."
            )
        if not user.is_active:
            raise exceptions.AuthenticationFailed("Nothing was read or written. User is inactive.")
        return user, token
