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

import itertools
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
from engine_mcp.views import McpView
from household.models import DeviceToken, Household, HouseholdMember, User, is_recruiter
from household.permissions import (
    DEMO_TOKEN_SESSION_KEY,
    RECRUITER_VISIT_OVER,
    RECRUITER_WRITE_REFUSAL,
    IsHouseholdMember,
)
from household.recruiter_routes import (
    RECRUITER_ALLOWED,
    RECRUITER_DENIED,
    RECRUITER_DENIED_ASPECTS,
    RECRUITER_READ_ONLY,
    WRITE_REFUSAL_HEAD,
    denied_refusal,
    unclassified_refusal,
    view_key,
)
from tests.test_assistant import KEY, _start, _tool, google  # noqa: F401 - fixture

pytestmark = pytest.mark.django_db


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture
def demo_household(db):
    """A visit's own household (ticket 13): flagged, the visitor its only member."""
    return Household.objects.create(name="Demo visit test", is_demo_visit=True)


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


def test_the_allowlist_is_the_member_surface_less_list_tables_and_the_brief():
    """Ticket 13 (ADR 0069): writes over the household's own rows are in; the
    registry tools out are the one that names body and memory tables, and the
    daily brief (procedures ticket 15), which the visit's routes refuse too."""
    assert set(RECRUITER_EXCLUDED) == {"list_tables", "brief_latest"}
    assert (
        RECRUITER_EXCLUDED["brief_latest"]
        == RECRUITER_DENIED["brief.views.BriefLatestView"]
    )
    for tool in TOOLS:
        if tool.writes:
            assert tool.name in RECRUITER_TOOLS, tool.name
    assert "about_kevin" in RECRUITER_TOOLS
    assert "about_kevin" not in surface.WEB_TOOL_NAMES


def test_recruiter_tables_are_the_five_aspects_and_never_body():
    aspects = {READABLE[t].aspect for t in RECRUITER_TABLES}
    assert aspects == {"ledger", "fleet", "pantry", "places", "voice_notes"}
    assert "voice_notes" in RECRUITER_TABLES  # Kevin's ruling 4
    for table in ("bodyweight_logs", "meal_logs", "sleep_logs", "ingested_files", "memories"):
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
    assert "list_tables" not in detail
    for name in RECRUITER_TOOLS:
        assert name in detail


def test_read_records_refuses_a_table_outside_the_five_aspects(recruiter_web, no_run):
    response = _tool(recruiter_web, "read_records", {"table": "bodyweight_logs"})
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read.")


@pytest.mark.parametrize("tool", ["write_record", "delete_record"])
@pytest.mark.parametrize("table", ["bodyweight_logs", "meal_logs", "workout_set_logs"])
def test_the_record_writes_refuse_a_body_table(recruiter_web, no_run, tool, table):
    response = _tool(recruiter_web, tool, {"table": table, "identity": "x", "fields": {}})
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was written or deleted.")


def test_an_allowed_read_runs_inside_the_demo_household(recruiter_web, visitor):
    response = _tool(recruiter_web, "read_records", {"table": "places"})
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False
    assert "found no live rows" in response.data["text"]
    [row] = AssistantCall.objects.all()
    assert row.household_id == visitor.household.id


def test_a_recruiters_write_lands_in_its_own_visit_household(recruiter_web, visitor):
    from legacy.models.dates import Event

    response = _tool(recruiter_web, "add_event", {"fields": {"title": "Visitor's call"}})
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False, response.data["text"]
    [event] = Event.objects.filter(title="Visitor's call")
    assert event.household_id == visitor.household.id


def test_a_write_tool_refuses_a_recruiter_outside_a_demo_visit(recruiter_web, visitor):
    """The second fence: `write_refusal` lets a recruiter write only inside a
    demo-visit household. Flip the flag off under it (as a bug might) and the
    write refuses in words, before it runs."""
    Household.objects.filter(pk=visitor.household.pk).update(is_demo_visit=False)
    from household.permissions import write_refusal

    class _Request:
        user = visitor
        auth = None

    assert write_refusal(_Request()) == RECRUITER_WRITE_REFUSAL


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
    for name in ("write_record", "delete_record"):
        [decl] = [d for d in declarations if d["name"] == name]
        enum = decl["parameters"]["properties"]["table"]["enum"]
        assert enum and set(enum) <= RECRUITER_TABLES, (name, enum)
    assert KEY not in response.content.decode()


def test_an_owner_setup_is_unchanged_by_the_recruiter_role(household_user):
    from assistant.surface import WEB_TOOL_NAMES, tools_for

    assert [t.name for t in tools_for(household_user)] == list(WEB_TOOL_NAMES)
    assert surface.refusal_for("add_event", household_user) is None
    assert not is_recruiter(household_user)


# =============================================================================
# The REST routes: a classified list, walked (ticket 13, ADR 0069)
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
            yield prefix + pattern, entry.callback


def _view_class(callback):
    return getattr(callback, "cls", None) or getattr(callback, "view_class", None)


def _concrete(route: str) -> str | None:
    if route.startswith("^") or "(?" in route:
        return None  # the SPA catch-all regex; it serves the app, never data
    path = re.sub(r"<(?:(\w+):)?\w+>", lambda m: _dummy(m.group(1) or "str"), route)
    return "/" + path


ROUTES = sorted(
    {
        (path, _view_class(cb))
        for route, cb in _routes()
        if (path := _concrete(route)) and not path.startswith("/admin/") and _view_class(cb)
    },
    key=lambda r: r[0],
)
API_ROUTES = sorted({path for path, _cls in ROUTES})


def _guarded(cls) -> bool:
    """Guarded by the household fence (`IsHouseholdMember` or a subclass);
    anonymous doors (login, csrf, the schema, `/enter`) are not."""
    return any(issubclass(p, IsHouseholdMember) for p in getattr(cls, "permission_classes", []))


def test_the_route_walk_found_the_api():
    assert "/api/assistant/tool" in API_ROUTES
    assert "/api/places/" in API_ROUTES
    assert "/mcp" in API_ROUTES
    assert len(API_ROUTES) > 40


def test_every_guarded_route_is_classified_for_recruiters():
    """A route added later fails here until someone puts its view in
    `RECRUITER_ALLOWED`, `RECRUITER_READ_ONLY` or `RECRUITER_DENIED` (with a
    reason). Until then it is refused at run time anyway."""
    tables = (set(RECRUITER_ALLOWED), set(RECRUITER_READ_ONLY), set(RECRUITER_DENIED))
    for a, b in itertools.combinations(tables, 2):
        assert not a & b, a & b
    unclassified = sorted(
        {
            view_key(cls)
            for _path, cls in ROUTES
            if _guarded(cls) and not getattr(cls, "allows_recruiter", False)
        }
        - set().union(*tables)
    )
    assert not unclassified, f"classify for recruiters: {unclassified}"
    routed = {view_key(cls) for _path, cls in ROUTES}
    stale = sorted(set().union(*tables) - routed)
    assert not stale, f"named but not routed: {stale}"
    for reason in (*RECRUITER_READ_ONLY.values(), *RECRUITER_DENIED.values()):
        assert reason.strip()


def test_the_named_outside_account_and_body_routes_are_denied():
    """Kevin's rulings 1 and 3, by route: these must never be open."""
    must_deny = (
        "/api/ingest/plaid",
        "/api/ingest/plaid/link-token",
        "/api/ingest/plaid/exchange",
        "/api/ingest/plaid/sync",
        "/api/ingest/sessions",
        "/api/ingest/statement",
        "/api/ingest/receipt",
        "/api/ingest/files/",
        "/api/device-logs/",
        "/api/auth/devices",
        "/api/auth/password",
        "/api/households/me/invites",
        "/api/body/bodyweight_logs/",
        "/api/body/meal_logs/",
        "/api/body/sleep_logs/",
        "/api/body/workout_plans/",
        "/api/memory/memories/",
        "/api/push/subscriptions",
        "/api/brief/refresh",
        "/mcp",
    )
    denied = {path for path, cls in ROUTES if view_key(cls) in RECRUITER_DENIED}
    assert set(must_deny) <= denied, sorted(set(must_deny) - denied)


def test_the_doors_a_view_opens_to_recruiters_itself_are_named():
    """`allows_recruiter = True` skips the three tables, so the doors it opens are pinned here:
    adding one is a decision someone reads. The conversation door (2026-11-03) takes a visitor's
    OWN conversation record and reads nothing; device logs stay denied."""
    opened = {p for p, c in ROUTES if _guarded(c) and getattr(c, "allows_recruiter", False)}
    assert opened == {
        "/api/assistant/companion",
        "/api/assistant/conversation",
        "/api/assistant/session",
        "/api/assistant/tool",
        "/api/demo/asset/x",
        "/api/demo/content",
        "/api/demo/session",
    }
    assert "/api/device-logs/" not in opened


DENIED_ROUTES = [(p, c) for p, c in ROUTES if view_key(c) in RECRUITER_DENIED]


@pytest.mark.parametrize("credential", ["session", "token"])
def test_every_denied_route_refuses_a_recruiter_in_words(
    credential, recruiter_web, recruiter_token_client, settings
):
    settings.LEGION_MCP = True
    client = recruiter_web if credential == "session" else recruiter_token_client
    wrong = []
    for path, cls in DENIED_ROUTES:
        expected = denied_refusal(RECRUITER_DENIED[view_key(cls)])
        for method in ("get", "post", "put", "patch", "delete"):
            response = getattr(client, method)(path, {}, format="json")
            if response.status_code == 415:  # a multipart-only upload door
                response = getattr(client, method)(path, {}, format="multipart")
            if response.status_code == 405:
                continue
            if response.status_code == 401 and path == "/mcp" and credential == "session":
                continue  # /mcp takes no browser session at all: refused before the fence
            detail = (getattr(response, "data", None) or {}).get("detail")
            if response.status_code != 403 or detail != expected:
                wrong.append((method.upper(), path, response.status_code, detail))
    assert wrong == []


def test_read_only_routes_refuse_a_recruiters_write_in_words(recruiter_web):
    me = recruiter_web.get("/api/auth/me")
    assert me.status_code == 200 and me.data["role"] == "recruiter"
    assert recruiter_web.get("/api/households/me").status_code == 200
    for path, body in (
        ("/api/auth/me", {"name": "Mallory"}),
        ("/api/households/me", {"name": "Renamed"}),
    ):
        response = recruiter_web.patch(path, body, format="json")
        assert response.status_code == 403, (path, response.data)
        assert response.data["detail"].startswith(WRITE_REFUSAL_HEAD), response.data


@pytest.mark.parametrize("credential", ["session", "token"])
def test_no_allowed_route_refuses_a_recruiter_as_a_recruiter(
    credential, recruiter_web, recruiter_token_client
):
    """Every route on the allow list reads for a recruiter (a 403 here would
    be the fence, which these must not hit)."""
    client = recruiter_web if credential == "session" else recruiter_token_client
    fenced = []
    for path, cls in ROUTES:
        if view_key(cls) not in RECRUITER_ALLOWED:
            continue
        response = client.get(path)
        detail = str((getattr(response, "data", None) or {}).get("detail", ""))
        if response.status_code == 403 and "demo visit" in detail:
            fenced.append((path, detail))
    assert fenced == []


def test_a_recruiter_writes_on_an_allowed_route_through_its_session(recruiter_web, visitor):
    from legacy.models.dates import Event

    response = recruiter_web.post(
        "/api/events", {"title": "Recruiter's own event"}, format="json"
    )
    assert response.status_code == 201, response.data
    assert Event.objects.get(title="Recruiter's own event").household_id == visitor.household.id


def test_the_read_scoped_visitor_token_still_cannot_write(recruiter_token_client):
    response = recruiter_token_client.post("/api/events", {"title": "x"}, format="json")
    assert response.status_code == 403
    assert "read-only" in response.data["detail"]


def test_mcp_refuses_a_recruiter_token(recruiter_token_client, settings):
    settings.LEGION_MCP = True
    response = recruiter_token_client.post(
        "/mcp",
        {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
        format="json",
        HTTP_ACCEPT="application/json, text/event-stream",
    )
    assert response.status_code == 403
    assert response.data["detail"] == denied_refusal(RECRUITER_DENIED[view_key(McpView)])


def test_an_unclassified_view_is_refused(recruiter_web, monkeypatch):
    """Run-time default deny: take a view off the allow list and the fence
    refuses it, in words, even though the route exists."""
    from household import recruiter_routes

    monkeypatch.setattr(
        recruiter_routes,
        "RECRUITER_ALLOWED",
        recruiter_routes.RECRUITER_ALLOWED - {"api.places.PlaceViewSet"},
    )
    response = recruiter_web.get("/api/places/")
    assert response.status_code == 403
    assert response.data["detail"] == unclassified_refusal()


def test_changes_feed_refuses_body_by_name_and_omits_it_by_default(recruiter_web):
    named = recruiter_web.get("/api/changes", {"aspects": "body"})
    assert named.status_code == 403
    assert named.data["detail"] == denied_refusal(RECRUITER_DENIED_ASPECTS["body"])
    everything = recruiter_web.get("/api/changes")
    assert everything.status_code == 200
    for table in ("bodyweight_logs", "memories", "ingested_files"):
        assert table not in everything.data
    assert "places" in everything.data and "events" in everything.data


def test_a_recruiter_outside_a_demo_visit_household_is_refused_everywhere(db):
    """A recruiter made before ticket 13 (in the shared demo household) is an
    ended visit: nothing, the assistant included."""
    shared = Household.objects.create(name="Demo")
    user = User.objects.create_user(email="old-visitor@demo.invalid")
    HouseholdMember.objects.create(user=user, household=shared, role=HouseholdMember.RECRUITER)
    token, _raw = _visitor_token(user)
    client = _recruiter_browser(user, token)
    for path in ("/api/places/", "/api/auth/me"):
        response = client.get(path)
        assert response.status_code == 403
        assert response.data["detail"] == RECRUITER_VISIT_OVER
    assert _tool(client, "list_events", {}).status_code == 403
    response = client.post("/api/events", {"title": "x"}, format="json")
    assert response.status_code == 403


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
