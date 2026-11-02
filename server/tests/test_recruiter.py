"""The recruiter role (recruiter-demo ticket 01, ADR 0068): default deny, server-side.

A recruiter visitor is a member of the demo household whose role is
`recruiter`. Its assistant may call `surface.RECRUITER_TOOLS` and nothing else,
it may write nothing by any credential, and every REST route that has not
opted in refuses it. Owners and members are untouched; `test_assistant.py`
still asserts their whole-registry surface.

Google is never called: the mint goes through the same fake transport
`test_assistant.py` uses.
"""

from __future__ import annotations

import json
import re
import uuid
from datetime import timedelta

import pytest
from django.urls import URLPattern, URLResolver, get_resolver
from django.utils import timezone
from rest_framework.test import APIClient

from assistant import surface
from assistant.models import AssistantCall
from assistant.surface import (
    RECRUITER_EXCLUDED,
    RECRUITER_ONLY_TOOLS,
    RECRUITER_TABLES,
    RECRUITER_TOOLS,
)
from engine_mcp.models import Outcome
from engine_mcp.tools import READABLE, TOOLS, TOOLS_BY_NAME
from household.models import DeviceToken, Household, HouseholdMember, User, is_recruiter
from household.permissions import (
    DEMO_TOKEN_SESSION_KEY,
    RECRUITER_ROUTE_REFUSAL,
    RECRUITER_VISIT_OVER,
    RECRUITER_WRITE_REFUSAL,
)
from tests.test_assistant import KEY, _start, _tool, google  # noqa: F401 - fixture

pytestmark = pytest.mark.django_db


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture
def demo_household(db):
    return Household.objects.create(name="Demo")


@pytest.fixture
def visitor(demo_household):
    user = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:12]}@demo.invalid")
    user.set_unusable_password()
    user.save()
    HouseholdMember.objects.create(
        user=user, household=demo_household, role=HouseholdMember.RECRUITER
    )
    return user


def _visitor_token(user, *, expires_in=timedelta(hours=2)):
    return DeviceToken.issue(
        user, "Demo visit", DeviceToken.SCOPE_READ, expires_at=timezone.now() + expires_in
    )


def _recruiter_browser(user, token) -> APIClient:
    """What `POST /api/demo/enter` leaves a browser holding: a session for the
    visitor, tied to its visitor token, with CSRF enforced."""
    client = APIClient(enforce_csrf_checks=True)
    client.force_login(user)
    session = client.session
    session[DEMO_TOKEN_SESSION_KEY] = token.pk
    session.save()
    client.get("/api/auth/csrf")
    client.credentials(HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)
    return client


@pytest.fixture
def recruiter_web(visitor):
    token, _raw = _visitor_token(visitor)
    return _recruiter_browser(visitor, token)


@pytest.fixture
def recruiter_token_client(visitor):
    _token, raw = _visitor_token(visitor)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


@pytest.fixture
def no_run(monkeypatch):
    """Fails the test if the tool door ever reaches `run_tool`."""
    calls = []

    def _never(request, name, args):
        calls.append(name)
        raise AssertionError(f"run_tool ran {name} for a refused call")

    monkeypatch.setattr("assistant.views.run_tool", _never)
    return calls


# =============================================================================
# The allowlist is a classification of the WHOLE registry
# =============================================================================


def test_every_registry_tool_is_classified_for_recruiters():
    """A tool added to the registry fails here until someone puts it in
    `RECRUITER_TOOLS` or `RECRUITER_EXCLUDED` (with its reason)."""
    allowed, excluded = set(RECRUITER_TOOLS), set(RECRUITER_EXCLUDED)
    assert not allowed & excluded, allowed & excluded
    unclassified = set(TOOLS_BY_NAME) - allowed - excluded
    assert not unclassified, f"classify for recruiters: {sorted(unclassified)}"
    # The one exception: a recruiter-only tool kept out of the registry on
    # purpose (`about_kevin`, ticket 10), so /mcp and members never see it.
    only = set(RECRUITER_ONLY_TOOLS)
    assert not only & set(TOOLS_BY_NAME), only & set(TOOLS_BY_NAME)
    assert allowed | excluded == set(TOOLS_BY_NAME) | only, "a name that is not a tool"
    for name, reason in RECRUITER_EXCLUDED.items():
        assert reason.strip(), name


def test_the_allowlist_is_reads_only():
    for name in RECRUITER_TOOLS:
        assert not (RECRUITER_ONLY_TOOLS.get(name) or TOOLS_BY_NAME[name]).writes, name
    for tool in TOOLS:
        if tool.writes:
            assert tool.name in RECRUITER_EXCLUDED, tool.name


def test_recruiter_tables_are_the_four_aspects_read_records_can_reach():
    aspects = {READABLE[t].aspect for t in RECRUITER_TABLES}
    assert aspects == {"ledger", "fleet", "pantry", "places"}
    for table in ("bodyweight_logs", "voice_notes", "ingested_files", "memories"):
        assert table not in RECRUITER_TABLES


@pytest.mark.parametrize("tool", [t.name for t in TOOLS if t.name not in RECRUITER_TOOLS])
def test_every_tool_outside_the_allowlist_refuses_a_recruiter_in_words(
    tool, recruiter_web, visitor, no_run
):
    response = _tool(recruiter_web, tool, {})
    assert response.status_code == 400, response.data
    detail = response.data["detail"]
    assert detail.startswith("Nothing was read or written."), detail
    assert "nothing ran" in detail
    assert response.data["response"] == {"success": False, "message": detail}
    assert no_run == []
    [row] = AssistantCall.objects.all()
    assert (row.user_id, row.tool, row.outcome) == (visitor.pk, tool, Outcome.REFUSED)


def test_an_unknown_tool_refuses_a_recruiter_and_lists_only_its_own(recruiter_web, no_run):
    response = _tool(recruiter_web, "send_mail", {})
    assert response.status_code == 400
    detail = response.data["detail"]
    assert "write_record" not in detail and "list_tables" not in detail
    for name in RECRUITER_TOOLS:
        assert name in detail


def test_read_records_refuses_a_table_outside_the_four_aspects(recruiter_web, no_run):
    response = _tool(recruiter_web, "read_records", {"table": "bodyweight_logs"})
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read.")


def test_an_allowed_read_runs_inside_the_demo_household(recruiter_web, visitor):
    response = _tool(recruiter_web, "read_records", {"table": "places"})
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False
    assert "found no live rows" in response.data["text"]
    [row] = AssistantCall.objects.all()
    assert row.household_id == visitor.household.id


def test_a_write_tool_refuses_even_when_the_allowlist_is_wrong(
    recruiter_web, monkeypatch
):
    """The second fence: a recruiter may write nothing by any credential, so a
    write tool listed by mistake still refuses, in words, before it runs."""
    monkeypatch.setattr(surface, "RECRUITER_TOOLS", (*RECRUITER_TOOLS, "add_event"))
    response = _tool(recruiter_web, "add_event", {"title": "x", "kind": "TASK"})
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is True
    assert response.data["text"] == RECRUITER_WRITE_REFUSAL


# =============================================================================
# The locked Live token's declarations
# =============================================================================


def test_the_recruiter_live_setup_declares_exactly_the_allowlist(recruiter_web, google):  # noqa: F811
    response = _start(recruiter_web)
    assert response.status_code == 200, response.data
    setup = google.sent[0]["body"]["bidiGenerateContentSetup"]
    [tools] = setup["tools"]
    declarations = tools["functionDeclarations"]
    assert [d["name"] for d in declarations] == list(RECRUITER_TOOLS)
    [read] = [d for d in declarations if d["name"] == "read_records"]
    assert read["parameters"]["properties"]["table"]["enum"] == sorted(RECRUITER_TABLES)
    assert KEY not in response.content.decode()


def test_an_owner_setup_is_unchanged_by_the_recruiter_role(household_user):
    from assistant.surface import WEB_TOOL_NAMES, tools_for

    assert [t.name for t in tools_for(household_user)] == list(WEB_TOOL_NAMES)
    assert surface.refusal_for("add_event", household_user) is None
    assert not is_recruiter(household_user)


# =============================================================================
# Every other door refuses a recruiter
# =============================================================================


def _dummy(converter: str) -> str:
    return {"int": "1", "uuid": str(uuid.uuid4())}.get(converter, "x")


def _routes(resolver=None, prefix=""):
    resolver = resolver or get_resolver()
    for entry in resolver.url_patterns:
        pattern = str(entry.pattern)
        if isinstance(entry, URLResolver):
            yield from _routes(entry, prefix + pattern)
        elif isinstance(entry, URLPattern):
            yield prefix + pattern


def _concrete(route: str) -> str | None:
    if route.startswith("^") or "(?" in route:
        return None  # the SPA catch-all regex; covered by the SPA test below
    path = re.sub(r"<(?:(\w+):)?\w+>", lambda m: _dummy(m.group(1) or "str"), route)
    return "/" + path


API_ROUTES = sorted(
    {p for p in (_concrete(r) for r in _routes()) if p and not p.startswith("/admin/")}
)


def test_the_route_walk_found_the_api():
    assert "/api/assistant/tool" in API_ROUTES
    assert "/api/places/" in API_ROUTES
    assert "/mcp" in API_ROUTES
    assert len(API_ROUTES) > 40


@pytest.mark.parametrize("credential", ["session", "token"])
def test_no_route_gives_a_recruiter_more_than_a_stranger(
    credential, recruiter_web, recruiter_token_client, settings
):
    """Every routed path, GET and POST: what a recruiter gets is a refusal, or
    exactly what an anonymous caller gets (health, the schema, csrf). The
    assistant's two POST doors are the only exception, and they are tested
    above."""
    settings.LEGION_MCP = True
    client = recruiter_web if credential == "session" else recruiter_token_client
    stranger = APIClient()
    opened = {"/api/assistant/session", "/api/assistant/tool"}
    # The recruiter page's own reads (tickets 07 and 10), opened on purpose and
    # pinned in `tests/test_recruiter_content.py`: the visit itself, and the
    # page content Kevin loaded for strangers to read.
    opened_reads = {"/api/demo/session", "/api/demo/content", "/api/demo/asset/x"}
    assert opened_reads <= set(API_ROUTES)
    leaks = []
    for path in API_ROUTES:
        for method in ("get", "post"):
            if method == "post" and path in opened:
                continue
            if method == "get" and path in opened_reads:
                continue
            mine = getattr(client, method)(path, {}, format="json") if method == "post" else (
                client.get(path)
            )
            if not 200 <= mine.status_code < 300:
                continue
            theirs = getattr(stranger, method)(path, {}, format="json") if method == "post" else (
                stranger.get(path)
            )
            if not 200 <= theirs.status_code < 300:
                leaks.append((method.upper(), path, mine.status_code))
    assert leaks == []


def test_a_data_route_refuses_a_recruiter_by_name(recruiter_token_client, recruiter_web):
    for client in (recruiter_token_client, recruiter_web):
        response = client.get("/api/places/")
        assert response.status_code == 403
        assert response.data["detail"] == RECRUITER_ROUTE_REFUSAL


def test_mcp_refuses_a_recruiter_token(recruiter_token_client, settings):
    settings.LEGION_MCP = True
    response = recruiter_token_client.post(
        "/mcp",
        {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
        format="json",
        HTTP_ACCEPT="application/json, text/event-stream",
    )
    assert response.status_code == 403
    assert response.data["detail"] == RECRUITER_ROUTE_REFUSAL


def test_a_recruiter_token_cannot_open_the_assistant(recruiter_token_client, google):  # noqa: F811
    """The assistant is session-only (web-assistant ticket 02) for a recruiter
    too: the visitor token is not a voice credential."""
    assert _start(recruiter_token_client).status_code == 403
    assert google.sent == []


# =============================================================================
# Expiry reaches both credentials
# =============================================================================


def test_an_expired_visitor_token_refuses_in_words(visitor):
    _token, raw = _visitor_token(visitor, expires_in=-timedelta(seconds=1))
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    response = client.get("/api/auth/me")
    assert response.status_code == 401
    assert "expired" in str(response.data["detail"])


def test_a_session_dies_with_its_visitor_token(visitor, google):  # noqa: F811
    token, _raw = _visitor_token(visitor)
    client = _recruiter_browser(visitor, token)
    assert _tool(client, "list_events", {}).status_code == 200
    token.expires_at = timezone.now() - timedelta(seconds=1)
    token.save(update_fields=["expires_at"])
    response = _tool(client, "list_events", {})
    assert response.status_code == 403
    assert response.data["detail"] == RECRUITER_VISIT_OVER
    token.expires_at = timezone.now() + timedelta(hours=1)
    token.revoked_at = timezone.now()
    token.save(update_fields=["expires_at", "revoked_at"])
    assert _tool(client, "list_events", {}).status_code == 403


def test_a_recruiter_session_with_no_visitor_token_is_refused(visitor):
    client = APIClient()
    client.force_login(visitor)
    response = client.post("/api/assistant/tool", {"name": "list_events"}, format="json")
    assert response.status_code == 403
    assert response.data["detail"] == RECRUITER_VISIT_OVER


def test_a_token_with_no_expiry_still_works(auth_client):
    assert auth_client.get("/api/auth/me").status_code == 200


def test_setup_json_never_names_an_excluded_tool(recruiter_web, google):  # noqa: F811
    assert _start(recruiter_web).status_code == 200
    body = json.dumps(google.sent[0]["body"])
    for name in RECRUITER_EXCLUDED:
        assert f'"name": "{name}"' not in body, name
