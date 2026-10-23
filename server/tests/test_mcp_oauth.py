"""OAuth 2.1 for `/mcp` (ai-upgrade ticket 03): LEGION's own authorization
server, end to end, with Claude's CIMD shape and Gemini's DCR shape.

The household leak test over the OAuth path is in `tests/test_tenancy.py`
(`test_every_mcp_tool_is_scoped_by_household`, parametrized over device token
and OAuth).
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import secrets
from urllib.parse import parse_qs, urlencode, urlsplit

import pytest
from django.core.cache import cache
from django.core.management import call_command
from django.test import Client
from oauth2_provider.cimd import SafeMetadataFetcher
from oauth2_provider.models import get_access_token_model, get_application_model
from rest_framework.test import APIClient

from engine_mcp.models import McpCall
from engine_mcp.oauth import MCP_SCOPE_WORDS
from tests.test_engine_mcp import call, client_with, rpc

pytestmark = pytest.mark.django_db

RESOURCE = "http://testserver/mcp"
GEMINI_REDIRECT = (
    "https://oauth-redirect.googleusercontent.com/r/user_bound_custom-mcp-123-testserver"
)
CLAUDE_REDIRECT = "https://claude.ai/api/mcp/auth_callback"
CLAUDE_CLIENT_ID = "https://claude.ai/oauth/mcp-oauth-client-metadata"
# Verbatim from the research (curl, 2026-10-22): three grant types, which DOT
# alone would refuse.
CLAUDE_DOCUMENT = {
    "client_id": CLAUDE_CLIENT_ID,
    "client_name": "Claude",
    "client_uri": "https://claude.ai",
    "redirect_uris": [CLAUDE_REDIRECT],
    "grant_types": [
        "authorization_code",
        "refresh_token",
        "urn:ietf:params:oauth:grant-type:jwt-bearer",
    ],
    "response_types": ["code"],
    "token_endpoint_auth_method": "none",
}


@pytest.fixture(autouse=True)
def mcp_on(settings):
    settings.LEGION_MCP = True
    cache.clear()  # CIMD failure backoff is per URL, in the cache.
    yield settings
    cache.clear()


@pytest.fixture
def claude_document(monkeypatch):
    documents = {CLAUDE_CLIENT_ID: CLAUDE_DOCUMENT}

    def fetch(self, client_id):
        if client_id not in documents:
            from oauth2_provider.cimd import CIMDError

            raise CIMDError("no such document")
        return dict(documents[client_id]), 3600

    monkeypatch.setattr(SafeMetadataFetcher, "fetch", fetch)
    return documents


# =============================================================================
# The flow, as a client runs it
# =============================================================================


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    digest = hashlib.sha256(verifier.encode()).digest()
    return verifier, base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def register(redirect: str = GEMINI_REDIRECT, **extra):
    body = {
        "client_name": "Gemini",
        "redirect_uris": [redirect],
        "grant_types": ["authorization_code", "refresh_token"],
        "token_endpoint_auth_method": "none",
        **extra,
    }
    return Client().post("/oauth/register/", json.dumps(body), content_type="application/json")


def authorize_params(client_id, redirect, challenge, **extra) -> dict:
    return {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": "s-123",
        "scope": "mcp",
        "resource": RESOURCE,
        **extra,
    }


def sign_in_and_allow(user, client_id, redirect, **extra) -> tuple[str, str, dict]:
    """GET the consent page as a signed-in member, POST Allow. Returns
    (code, verifier, query of the redirect)."""
    verifier, challenge = pkce()
    params = authorize_params(client_id, redirect, challenge, **extra)
    browser = Client()
    browser.force_login(user)
    page = browser.get("/oauth/authorize/?" + urlencode(params))
    assert page.status_code == 200, page.content
    assert MCP_SCOPE_WORDS in page.content.decode()
    allowed = browser.post("/oauth/authorize/", {**params, "allow": "Authorize"})
    assert allowed.status_code == 302, allowed.content
    location = allowed["Location"]
    assert location.startswith(redirect)
    query = {k: v[0] for k, v in parse_qs(urlsplit(location).query).items()}
    assert "code" in query, location
    return query["code"], verifier, query


def exchange(client_id, redirect, code, verifier, **extra):
    return Client().post(
        "/oauth/token/",
        {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect,
            "client_id": client_id,
            "code_verifier": verifier,
            **extra,
        },
    )


def oauth_tokens(user, client_id=None, redirect=GEMINI_REDIRECT) -> dict:
    """The whole Gemini-shaped flow: register, sign in, allow, exchange."""
    if client_id is None:
        made = register(redirect)
        assert made.status_code == 201, made.content
        client_id = made.json()["client_id"]
    code, verifier, _ = sign_in_and_allow(user, client_id, redirect)
    token = exchange(client_id, redirect, code, verifier)
    assert token.status_code == 200, token.content
    return {**token.json(), "client_id": client_id}


def bearer(access_token: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token}")
    return client


def oauth_mcp_client(user) -> APIClient:
    """An `/mcp` client signed in through OAuth. Used by the tenancy leak test."""
    return bearer(oauth_tokens(user)["access_token"])


def test_end_to_end_dcr_sign_in_token_call_and_refresh(household_user):
    tokens = oauth_tokens(household_user)
    assert tokens["token_type"] == "Bearer"
    assert tokens["scope"] == "mcp"
    assert tokens["expires_in"] == 3600
    client = bearer(tokens["access_token"])

    listed = rpc(client, "tools/list")
    assert listed.status_code == 200, listed.content
    is_error, text, _ = call(client, "add_event", {"fields": {"title": "via oauth"}})
    assert not is_error, text
    events = client_with(household_user).get("/api/events?since=1970-01-01T00:00:00Z").data
    assert "via oauth" in str(events)
    audit = McpCall.objects.filter(tool="add_event").get()
    assert audit.household_id == household_user.household.id
    assert audit.token_id is None and audit.token_name == "OAuth: Gemini"
    assert audit.token_scope == "oauth"

    # Refresh rotates; the old refresh token is then refused.
    refreshed = Client().post(
        "/oauth/token/",
        {
            "grant_type": "refresh_token",
            "refresh_token": tokens["refresh_token"],
            "client_id": tokens["client_id"],
        },
    )
    assert refreshed.status_code == 200, refreshed.content
    new = refreshed.json()
    assert new["refresh_token"] != tokens["refresh_token"]
    assert rpc(bearer(new["access_token"]), "tools/list").status_code == 200
    replay = Client().post(
        "/oauth/token/",
        {
            "grant_type": "refresh_token",
            "refresh_token": tokens["refresh_token"],
            "client_id": tokens["client_id"],
        },
    )
    assert replay.status_code == 400
    assert replay.json()["error"] == "invalid_grant"


def test_the_redirect_carries_state_and_iss(household_user):
    client_id = register().json()["client_id"]
    _code, _verifier, query = sign_in_and_allow(household_user, client_id, GEMINI_REDIRECT)
    assert query["state"] == "s-123"
    assert query["iss"] == "http://testserver"


def test_a_token_without_resource_is_still_bound_to_mcp(household_user):
    client_id = register().json()["client_id"]
    verifier, challenge = pkce()
    params = authorize_params(client_id, GEMINI_REDIRECT, challenge)
    params.pop("resource")
    browser = Client()
    browser.force_login(household_user)
    allowed = browser.post("/oauth/authorize/", {**params, "allow": "Authorize"})
    code = parse_qs(urlsplit(allowed["Location"]).query)["code"][0]
    token = exchange(client_id, GEMINI_REDIRECT, code, verifier)
    assert token.status_code == 200, token.content
    stored = get_access_token_model().objects.get(user=household_user)
    assert stored.resource == [RESOURCE]


def test_offline_access_is_ignored_not_refused(household_user):
    client_id = register().json()["client_id"]
    _code, _verifier, query = sign_in_and_allow(
        household_user, client_id, GEMINI_REDIRECT, scope="mcp offline_access"
    )
    assert "code" in query


def test_another_resource_is_refused_before_consent(household_user):
    client_id = register().json()["client_id"]
    _verifier, challenge = pkce()
    params = authorize_params(
        client_id, GEMINI_REDIRECT, challenge, resource="https://elsewhere.example/api"
    )
    browser = Client()
    browser.force_login(household_user)
    page = browser.get("/oauth/authorize/?" + urlencode(params))
    assert page.status_code == 400
    assert "Nothing was authorized" in page.content.decode()


def test_pkce_is_required_and_plain_is_refused(household_user):
    client_id = register().json()["client_id"]
    browser = Client()
    browser.force_login(household_user)
    base = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": GEMINI_REDIRECT,
        "state": "s",
        "scope": "mcp",
    }
    missing = browser.get("/oauth/authorize/?" + urlencode(base))
    plain = browser.get(
        "/oauth/authorize/?"
        + urlencode({**base, "code_challenge": "x" * 43, "code_challenge_method": "plain"})
    )
    for response in (missing, plain):
        # Either the redirect carries the error or the page refuses; never a code.
        location = response.get("Location", "")
        assert "code=" not in location
        assert response.status_code in (302, 400), response.status_code
        if response.status_code == 302:
            assert "error=" in location


def test_a_wrong_verifier_gets_no_token(household_user):
    client_id = register().json()["client_id"]
    code, _verifier, _ = sign_in_and_allow(household_user, client_id, GEMINI_REDIRECT)
    token = exchange(client_id, GEMINI_REDIRECT, code, "wrong-" + "v" * 50)
    assert token.status_code == 400
    assert token.json()["error"] == "invalid_grant"


def test_signing_in_is_required_and_goes_to_the_web_sign_in(household_user):
    client_id = register().json()["client_id"]
    _verifier, challenge = pkce()
    params = authorize_params(client_id, GEMINI_REDIRECT, challenge)
    response = Client().get("/oauth/authorize/?" + urlencode(params))
    assert response.status_code == 302
    assert response["Location"].startswith("/login?next=/oauth/authorize/")


# =============================================================================
# Registration: the allowlist
# =============================================================================


def test_dcr_refuses_a_redirect_off_the_allowlist():
    for bad in (
        "https://evil.example/callback",
        "http://claude.ai/api/mcp/auth_callback",
        "https://oauth-redirect.googleusercontent.com/x/other",
        "https://oauth-redirect.googleusercontent.com/r/../../evil",
        "https://user@oauth-redirect.googleusercontent.com/r/x",
    ):
        response = register(bad)
        assert response.status_code == 400, (bad, response.content)
        assert response.json()["error"] == "invalid_redirect_uri"
        assert "Nothing was registered" in response.json()["error_description"]
    assert get_application_model().objects.count() == 0


def test_dcr_refuses_when_any_one_redirect_is_off_the_allowlist():
    body = {
        "client_name": "Mixed",
        "redirect_uris": [GEMINI_REDIRECT, "https://evil.example/cb"],
        "token_endpoint_auth_method": "none",
    }
    response = Client().post("/oauth/register/", json.dumps(body), content_type="application/json")
    assert response.status_code == 400
    assert get_application_model().objects.count() == 0


def test_dcr_accepts_claude_and_narrows_extra_grants():
    response = register(
        CLAUDE_REDIRECT,
        grant_types=[
            "authorization_code",
            "refresh_token",
            "urn:ietf:params:oauth:grant-type:jwt-bearer",
        ],
    )
    assert response.status_code == 201, response.content
    assert response.json()["grant_types"] == ["authorization_code", "refresh_token"]


def test_dcr_confidential_client_gets_a_secret_and_can_sign_in(household_user):
    made = register(token_endpoint_auth_method="client_secret_post")
    assert made.status_code == 201, made.content
    body = made.json()
    assert body["client_secret"]
    code, verifier, _ = sign_in_and_allow(household_user, body["client_id"], GEMINI_REDIRECT)
    token = exchange(
        body["client_id"], GEMINI_REDIRECT, code, verifier, client_secret=body["client_secret"]
    )
    assert token.status_code == 200, token.content


# =============================================================================
# CIMD (Claude)
# =============================================================================


def test_claude_cimd_document_signs_in_end_to_end(household_user, claude_document):
    tokens = oauth_tokens(household_user, client_id=CLAUDE_CLIENT_ID, redirect=CLAUDE_REDIRECT)
    app = get_application_model().objects.get(client_id=CLAUDE_CLIENT_ID)
    assert app.registration_source == "cimd"
    assert app.name == "Claude"
    assert rpc(bearer(tokens["access_token"]), "tools/list").status_code == 200


def test_cimd_from_a_host_off_the_allowlist_is_an_unknown_client(household_user, claude_document):
    other = "https://evil.example/client.json"
    claude_document[other] = {**CLAUDE_DOCUMENT, "client_id": other}
    _verifier, challenge = pkce()
    browser = Client()
    browser.force_login(household_user)
    page = browser.get(
        "/oauth/authorize/?" + urlencode(authorize_params(other, CLAUDE_REDIRECT, challenge))
    )
    assert "code=" not in page.get("Location", "")
    assert not get_application_model().objects.filter(client_id=other).exists()


def test_cimd_document_with_a_redirect_off_the_allowlist_is_refused(
    household_user, claude_document, settings
):
    sneaky = "https://claude.ai/oauth/other-client"
    claude_document[sneaky] = {
        **CLAUDE_DOCUMENT,
        "client_id": sneaky,
        "redirect_uris": ["https://evil.example/cb"],
    }
    _verifier, challenge = pkce()
    browser = Client()
    browser.force_login(household_user)
    page = browser.get(
        "/oauth/authorize/?"
        + urlencode(authorize_params(sneaky, "https://evil.example/cb", challenge))
    )
    assert "evil.example" not in page.get("Location", "")
    assert not get_application_model().objects.filter(client_id=sneaky).exists()


# =============================================================================
# Discovery
# =============================================================================


def test_server_metadata_advertises_what_claude_and_gemini_need():
    body = Client().get("/.well-known/oauth-authorization-server").json()
    assert body["issuer"] == "http://testserver"
    assert body["client_id_metadata_document_supported"] is True
    assert "none" in body["token_endpoint_auth_methods_supported"]
    assert body["code_challenge_methods_supported"] == ["S256"]
    assert body["registration_endpoint"] == "http://testserver/oauth/register/"
    assert body["authorization_endpoint"] == "http://testserver/oauth/authorize/"
    assert body["token_endpoint"] == "http://testserver/oauth/token/"
    assert body["authorization_response_iss_parameter_supported"] is True
    assert set(body["grant_types_supported"]) == {"authorization_code", "refresh_token"}
    assert body["response_types_supported"] == ["code"]
    assert body["scopes_supported"] == ["mcp"]
    assert "offline_access" not in body["scopes_supported"]


@pytest.mark.parametrize(
    "path",
    ["/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"],
)
def test_protected_resource_metadata_names_mcp(path):
    body = Client().get(path).json()
    assert body["resource"] == RESOURCE
    assert body["authorization_servers"] == ["http://testserver"]
    assert body["scopes_supported"] == ["mcp"]


def test_mcp_401_points_at_the_resource_metadata():
    response = rpc(APIClient(), "tools/list")
    assert response.status_code == 401
    challenge = response["WWW-Authenticate"]
    assert challenge.startswith("Bearer")
    assert (
        'resource_metadata="http://testserver/.well-known/oauth-protected-resource/mcp"'
        in challenge
    )


def test_everything_is_404_while_mcp_is_off(settings):
    settings.LEGION_MCP = False
    for path in (
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource/mcp",
        "/oauth/authorize/",
        "/oauth/token/",
    ):
        assert Client().get(path).status_code == 404, path
    assert register().status_code == 404


# =============================================================================
# /mcp refuses the wrong tokens
# =============================================================================


def _token_for(user, application, *, scope="mcp", resource=(RESOURCE,)):
    import datetime

    from django.utils import timezone
    from oauth2_provider.models import set_token_value

    AccessToken = get_access_token_model()
    raw = secrets.token_urlsafe(32)
    token = AccessToken(
        user=user,
        application=application,
        scope=scope,
        expires=timezone.now() + datetime.timedelta(hours=1),
        resource=list(resource),
    )
    set_token_value(token, raw)
    token.save()
    return raw


@pytest.fixture
def an_application():
    Application = get_application_model()
    return Application.objects.create(
        name="Test client",
        client_type=Application.CLIENT_PUBLIC,
        authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
        redirect_uris=GEMINI_REDIRECT,
    )


def test_a_token_for_another_audience_is_refused(household_user, an_application):
    raw = _token_for(household_user, an_application, resource=("http://testserver/api",))
    response = rpc(bearer(raw), "tools/list")
    assert response.status_code == 401


def test_a_token_with_no_member_is_refused(an_application):
    raw = _token_for(None, an_application)
    response = rpc(bearer(raw), "tools/list")
    assert response.status_code == 401
    assert "belongs to no member" in response.json()["detail"]


def test_a_token_without_the_mcp_scope_is_refused(household_user, an_application):
    raw = _token_for(household_user, an_application, scope="oauth2_provider:registration")
    assert rpc(bearer(raw), "tools/list").status_code == 401


def test_an_unknown_bearer_is_refused():
    assert rpc(bearer("not-a-token"), "tools/list").status_code == 401


def test_the_device_token_still_works_beside_oauth(household_user):
    assert rpc(client_with(household_user), "tools/list").status_code == 200


def test_oauth_calls_are_throttled_per_client_and_member(household_user, monkeypatch):
    from engine_mcp.views import McpTokenThrottle

    monkeypatch.setattr(McpTokenThrottle, "rate", "2/min")
    client = oauth_mcp_client(household_user)
    assert rpc(client, "tools/list").status_code == 200
    assert rpc(client, "tools/list").status_code == 200
    assert rpc(client, "tools/list").status_code == 429
    # Another member's OAuth client has its own budget.
    from tests.conftest import _member

    other = oauth_mcp_client(_member(household_user.household, "mia@example.com"))
    assert rpc(other, "tools/list").status_code == 200


# =============================================================================
# Listing and revoking
# =============================================================================


def test_clients_are_listed_and_revocable(household_user):
    tokens = oauth_tokens(household_user)
    out = io.StringIO()
    call_command("mcp_clients", stdout=out)
    listed = out.getvalue()
    assert tokens["client_id"] in listed
    assert "Gemini" in listed and household_user.email in listed

    call_command("mcp_clients", "--revoke", tokens["client_id"], stdout=out)
    assert rpc(bearer(tokens["access_token"]), "tools/list").status_code == 401
    assert not get_application_model().objects.filter(client_id=tokens["client_id"]).exists()


def test_a_members_tokens_are_revocable_alone(household_user):
    tokens = oauth_tokens(household_user)
    out = io.StringIO()
    call_command("mcp_clients", "--revoke-member", household_user.email, stdout=out)
    assert "Revoked every OAuth token" in out.getvalue()
    assert rpc(bearer(tokens["access_token"]), "tools/list").status_code == 401
    assert get_application_model().objects.filter(client_id=tokens["client_id"]).exists()
