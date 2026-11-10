"""Architecture upgrade 2 (2026-11-10): the Claude worker's own token scope, `claude_worker`.

A token issued with it may write, but only on the worker's routes
(`claude_session.tokens.WORKER_ROUTES`); everything else is refused at
`DeviceTokenAuthentication`, in words. Additive: a worker token issued before it (scope `write`)
keeps every route.
"""
from __future__ import annotations

import io

import pytest
from django.core.management import call_command
from rest_framework.test import APIClient

from claude_session.models import SCOPE_FULL
from claude_session.tokens import FENCE_UNBOUND, worker_fence, worker_route_allowed
from household.authentication import WORKER_ROUTE_REFUSAL
from household.models import DeviceToken

pytestmark = pytest.mark.django_db

API = "/api/claude/"


def _client(user, scope, name="claude-session-Alienware"):
    token, raw = DeviceToken.issue(user, name, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return token, client


def _beat(client, name="Alienware", scope=SCOPE_FULL):
    return client.post(
        f"{API}machines/heartbeat/",
        {"name": name, "hostname": "h", "scope": scope, "state": "idle", "running": [],
         "claude_version": "2.1.316"},
        format="json",
    )


def _refused(response) -> bool:
    return response.status_code == 403 and WORKER_ROUTE_REFUSAL in str(response.data)


def test_a_worker_token_runs_the_worker_routes(household_user):
    token, client = _client(household_user, DeviceToken.SCOPE_CLAUDE_WORKER)
    assert token.can_write
    assert _beat(client).status_code == 200
    assert client.post(f"{API}tasks/claim/", {"name": "Alienware"}, format="json").status_code in (
        200, 204)
    assert client.post(
        f"{API}sessions/",
        {"machine": "Alienware", "session_id": "s1", "project": "p", "state": "working",
         "at": "2026-11-10T10:00:00Z"},
        format="json",
    ).status_code in (200, 201)


def test_a_worker_token_is_refused_everywhere_else_in_words(household_user, settings):
    settings.LEGION_MCP = True
    _token, client = _client(household_user, DeviceToken.SCOPE_CLAUDE_WORKER)
    assert _beat(client).status_code == 200
    for method, path in (
        ("get", "/api/member-memories/"),
        ("post", "/api/member-memories/"),
        ("get", "/api/vault/items"),
        ("get", "/api/households/me"),
        ("get", "/api/books/library"),  # the library is read by the phone, not the worker
        ("get", "/api/checklists/"),
        ("post", "/mcp"),
    ):
        response = getattr(client, method)(path, {}, format="json") if method == "post" else \
            client.get(path)
        assert _refused(response), (method, path, response.status_code)


def test_a_worker_token_reaches_the_memory_review_books_and_releases_doors(household_user,
                                                                             settings):
    settings.LEGION_MEMORY_REVIEW = "shadow"
    _token, client = _client(household_user, DeviceToken.SCOPE_CLAUDE_WORKER)
    assert _beat(client).status_code == 200
    assert client.get("/api/member-memories/review/context").status_code == 200
    for response in (
        client.post("/api/books/library", {}, format="multipart"),
        client.post("/api/books/convert", b"", content_type="application/pdf"),
        client.delete("/api/books/library/999999"),
        client.get("/api/releases/latest?app=phone"),
    ):
        assert not _refused(response), response.status_code


def test_an_old_write_worker_token_keeps_every_route(household_user):
    _token, client = _client(household_user, DeviceToken.SCOPE_WRITE)
    assert _beat(client).status_code == 200
    assert client.get("/api/member-memories/").status_code == 200
    assert client.get("/api/households/me").status_code == 200


def test_the_scope_makes_a_worker_token_whatever_its_name(household_user):
    token, client = _client(household_user, DeviceToken.SCOPE_CLAUDE_WORKER, name="laptop")

    class R:
        auth = token

    assert worker_fence(R()) == FENCE_UNBOUND


def test_route_matching_is_by_whole_segment_and_method():
    assert worker_route_allowed("/api/claude/tasks/claim/", "POST")
    assert worker_route_allowed("/api/member-memories/review/changes", "POST")
    assert not worker_route_allowed("/api/member-memories/", "GET")
    assert not worker_route_allowed("/api/member-memories/recall", "GET")
    assert not worker_route_allowed("/api/claudex/", "GET")
    assert not worker_route_allowed("/api/books/libraryx", "POST")
    assert worker_route_allowed("/api/books/library/12", "DELETE")
    assert not worker_route_allowed("/api/books/library/12/file", "GET")
    assert not worker_route_allowed("/api/books/positions", "GET")
    assert worker_route_allowed("/api/releases/", "POST")
    assert not worker_route_allowed("/mcp", "POST")


def test_issue_device_token_mints_the_scope(household_user):
    out = io.StringIO()
    call_command("issue_device_token", household_user.email, "--name",
                 "claude-session-Alienware", "--scope", "claude_worker", stdout=out)
    assert "claude_worker-scoped" in out.getvalue()
    token = DeviceToken.objects.get(name="claude-session-Alienware")
    assert token.scope == DeviceToken.SCOPE_CLAUDE_WORKER


def test_issue_refuses_an_unknown_scope(household_user):
    with pytest.raises(ValueError):
        DeviceToken.issue(household_user, "x", scope="admin")


def test_signing_in_as_a_claude_session_mints_a_worker_token(household_user):
    """install.ps1 signs in with device_name `claude-session-<Machine>`: that is the re-issue."""
    client = APIClient()
    response = client.post(
        "/api/auth/login",
        {"email": household_user.email, "password": "correct horse battery",
         "device_name": "claude-session-Alienware"},
        format="json",
    )
    assert response.status_code == 200
    assert DeviceToken.objects.get(name="claude-session-Alienware").scope == "claude_worker"
    phone = client.post(
        "/api/auth/login",
        {"email": household_user.email, "password": "correct horse battery",
         "device_name": "Kevin's phone"},
        format="json",
    )
    assert phone.status_code == 200
    assert DeviceToken.objects.get(name="Kevin's phone").scope == "write"
