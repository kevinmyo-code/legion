"""engine-brain ticket 02: the dark turn endpoint and ticket 01's SSE probe.

- Off by default: after authentication, `POST /api/agents/turn` answers a member 404 "switched
  off", so with LEGION_AGENTS_TURN unset nothing is read, written or sent to a model.
- On: one turn streams as Server-Sent Events, as the member, under their own rows.
- A recruiter is refused whatever the switch says.
"""

from __future__ import annotations

import json

import pytest
from rest_framework.test import APIClient

from agents.legion.flags import TURN_ENV
from agents.models import AgentTurn
from agents.runtime import model as m
from agents.views import OFF_WORDS

pytestmark = pytest.mark.django_db


def _events(response) -> list[tuple[str, dict]]:
    body = b"".join(response.streaming_content).decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


@pytest.fixture
def switched_on(monkeypatch):
    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")


@pytest.fixture
def fake_model(monkeypatch):
    holder = {}

    def factory(key):
        holder["model"] = m.FakeModel(holder.pop("rounds"))
        return holder["model"]

    monkeypatch.setattr("agents.views.GeminiClient", factory)
    return holder


def test_a_stranger_is_refused_at_authentication_like_any_route(monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    response = APIClient().post("/api/agents/turn", {"transcript": "hi"}, format="json")
    assert response.status_code in (401, 403)
    assert not AgentTurn.objects.exists()


def test_the_turn_is_off_for_a_member_too_and_nothing_is_written(auth_client, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    response = auth_client.post("/api/agents/turn", {"transcript": "what's due"}, format="json")
    assert response.status_code == 404 and response.data["detail"] == OFF_WORDS
    assert not AgentTurn.objects.exists()


def test_any_value_but_on_keeps_it_off(auth_client, monkeypatch):
    monkeypatch.setenv(TURN_ENV, "true")
    assert (
        auth_client.post("/api/agents/turn", {"transcript": "x"}, format="json").status_code == 404
    )


def test_switched_on_a_turn_streams_as_server_sent_events(auth_client, switched_on, fake_model):
    fake_model["rounds"] = [m.call("read_list", {}), m.say("You have no reminders. Nothing else.")]
    response = auth_client.post(
        "/api/agents/turn", {"transcript": "what reminders do I have"}, format="json"
    )
    assert response.status_code == 200 and response["Content-Type"] == "text/event-stream"
    events = _events(response)
    kinds = [kind for kind, _ in events]
    assert kinds[0] == "turn" and kinds[-1] == "done" and "tool" in kinds
    assert [d["text"] for k, d in events if k == "sentence"] == [
        "You have no reminders.",
        "Nothing else.",
    ]
    turn = AgentTurn.objects.get()
    assert turn.status == "done" and turn.final_text.startswith("You have no reminders")
    # The key never rides in a request URL.
    assert "test-key-not-real" not in json.dumps(events)


def test_no_key_refuses_in_words(auth_client, monkeypatch):
    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    response = auth_client.post("/api/agents/turn", {"transcript": "x"}, format="json")
    assert response.status_code == 503 and "LEGION_GEMINI_KEY" in response.data["detail"]


def test_a_model_failure_ends_the_stream_in_words(auth_client, switched_on, fake_model):
    fake_model["rounds"] = []
    events = _events(auth_client.post("/api/agents/turn", {"transcript": "x"}, format="json"))
    assert events[-1][0] == "done" and events[-1][1]["status"] == "failed"
    assert any(k == "sentence" and "couldn't reach the model" in d["text"] for k, d in events)


def test_a_recruiter_is_refused_whatever_the_switch_says(switched_on, fake_model):
    """A visitor who came in through `/api/demo/enter` (its own seeded visit household): the
    route is in `RECRUITER_DENIED`, so the household fence refuses it before the view runs."""
    from demo.models import DemoCode

    _row, code = DemoCode.rotate()
    browser = APIClient(enforce_csrf_checks=True)
    browser.get("/api/auth/csrf")
    entered = browser.post("/api/demo/enter", {"code": code, "ref": "agents"}, format="json")
    assert entered.status_code == 201, entered.data
    browser.credentials(HTTP_X_CSRFTOKEN=browser.cookies["csrftoken"].value)
    fake_model["rounds"] = [m.say("never")]
    response = browser.post("/api/agents/turn", {"transcript": "what's due"}, format="json")
    assert response.status_code == 403
    assert not AgentTurn.objects.exists()
    assert browser.get("/api/agents/probe/sse").status_code == 403


def test_a_member_cancels_only_their_own_turn(auth_client, switched_on, fake_model, household_a):
    from tests.conftest import _client_for, _member

    fake_model["rounds"] = [m.say("Hi.")]
    events = _events(auth_client.post("/api/agents/turn", {"transcript": "hi"}, format="json"))
    turn_id = events[0][1]["turn_id"]
    other = _client_for(_member(household_a, "mia-cancel@example.com"))
    assert other.post(f"/api/agents/turn/{turn_id}/cancel").status_code == 404
    assert auth_client.post(f"/api/agents/turn/{turn_id}/cancel").status_code == 200
    assert AgentTurn.objects.get(pk=turn_id).cancel_requested


def test_the_sse_probe_sends_five_events_about_200_ms_apart(auth_client, monkeypatch):
    monkeypatch.setattr("agents.views.PROBE_GAP_S", 0.01)
    response = auth_client.get("/api/agents/probe/sse")
    events = _events(response)
    assert [d["i"] for _, d in events] == [0, 1, 2, 3, 4]
    assert response["X-Accel-Buffering"] == "no"


def test_the_sse_probe_needs_a_member():
    assert APIClient().get("/api/agents/probe/sse").status_code in (401, 403)
