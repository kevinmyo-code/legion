"""engine-brain ticket 04, slice E2: the relay through the dark agents door, with a fake limb.

A turn whose model calls a limb tool ends its stream with `tool_request` and `done
{status: awaiting_limb}`; `POST /api/agents/turn/<id>/tool_result` records the limb's result and
the round's last one streams the rest of the turn. Every refusal case of the brief has a test here.
Google is never called (`FakeModel`, `FakeSpeech`). Every device, tool and word is invented.
"""

# ruff: noqa: F811 - the `engine` fixture is imported from the voice turn tests

from __future__ import annotations

import json

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient

from agents import relay_views
from agents.legion import relay, relay_turn
from agents.legion.flags import TURN_ENV
from agents.legion.limbs import CONTRACT_VERSION
from agents.legion.store import TurnStore
from agents.models import AgentToolCall, AgentTurn, Limb
from agents.runtime import model as m
from agents.views import OFF_WORDS
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db

# ---- the fake limb ------------------------------------------------------------------------------

TOOLS = [
    {
        "name": "car_status",
        "tool_class": "vehicle",
        "description": "Read the connected car's live status.",
        "parameters": {"type": "object", "properties": {}},
        "writes": False,
        "read_through": False,
        "actuates": False,
    },
    {
        "name": "set_timer",
        "tool_class": "device",
        "description": "Start a countdown on the device.",
        "parameters": {
            "type": "object",
            "properties": {"seconds": {"type": "integer"}, "label": {"type": "string"}},
        },
        "writes": True,
        "read_through": False,
        "actuates": False,
        "confirm": "limb",
        "deadline_ms": 2000,
    },
    {
        "name": "navigate",
        "tool_class": "place",
        "description": "Start navigation to a place.",
        "parameters": {"type": "object", "properties": {"to": {"type": "string"}}},
        "writes": False,
        "read_through": False,
        "actuates": True,
        "needs": ["screen"],
        "confirm": "limb",
        "deadline_ms": 60_000,
    },
    {
        "name": "garage_door",
        "tool_class": "device",
        "description": "Open or close the garage door.",
        "parameters": {"type": "object", "properties": {"open": {"type": "boolean"}}},
        "writes": True,
        "read_through": False,
        "actuates": True,
        "confirm": "engine",
    },
    {
        "name": "browser",
        "tool_class": "web",
        "description": "Read or open a web page on the device.",
        "parameters": {
            "type": "object",
            "properties": {"action": {"type": "string", "enum": ["read", "open"]}},
        },
        "writes": False,
        "read_through": False,
        "actuates": False,
        "read_through_when": {"action": ["read"]},
    },
    {
        "name": "look_up",
        "tool_class": "web",
        "description": "Look something up on the device.",
        "parameters": {
            "type": "object",
            "properties": {"domain": {"type": "string", "enum": ["places", "ask_mail"]}},
        },
        "writes": False,
        "read_through": False,
        "actuates": False,
        "mail_when": {"domain": ["ask_mail"]},
    },
    {
        "name": "ask_mail",
        "tool_class": "mail",
        "description": "Ask about mail.",
        "parameters": {"type": "object", "properties": {}},
        "writes": False,
        "read_through": True,
        "actuates": False,
    },
    {
        "name": "control_music",
        "tool_class": "device",
        "description": "Play, pause or skip music.",
        "parameters": {"type": "object", "properties": {"action": {"type": "string"}}},
        "writes": False,
        "read_through": False,
        "actuates": True,
        "confirm": "limb",
    },
    {
        "name": "control_volume",
        "tool_class": "device",
        "description": "Set the volume.",
        "parameters": {"type": "object", "properties": {"level": {"type": "integer"}}},
        "writes": False,
        "read_through": False,
        "actuates": True,
        "confirm": "limb",
    },
    {
        "name": "save_parking_spot",
        "tool_class": "place",
        "description": "Save where the car is parked.",
        "parameters": {"type": "object", "properties": {"note": {"type": "string"}}},
        "writes": True,
        "read_through": False,
        "actuates": False,
    },
    {
        "name": "switch_vehicle",
        "tool_class": "vehicle",
        "description": "Switch the active car.",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}},
        "writes": True,
        "read_through": False,
        "actuates": False,
        "confirm": "limb",
    },
]


def manifest(tools=None) -> dict:
    return {
        "contract_version": CONTRACT_VERSION,
        "kind": "phone",
        "app_version": "2026-01-01-test000",
        "tools": tools if tools is not None else TOOLS,
        "streams": [],
    }


def attach(client, tools=None) -> str:
    response = client.post("/api/agents/limb", manifest(tools), format="json")
    assert response.status_code == 200, response.data
    return str(response.data["limb_id"])


def device_client(user) -> APIClient:
    from household.models import DeviceToken

    _token, raw = DeviceToken.issue(user, "Second test device")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


def calls(*pairs) -> list:
    """One model round calling several tools, in order."""
    out = []
    for name, args in pairs:
        out += [m.PartEvent({"functionCall": {"name": name, "args": args}})]
        out += [m.ToolCallEvent("", name, args)]
    return out + [m.Usage(100, 0, 5, 105), m.Finish("STOP")]


def events_of(response) -> list[tuple[str, dict]]:
    assert response.status_code == 200, (response.status_code, getattr(response, "data", None))
    body = b"".join(response.streaming_content).decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


def kinds(events) -> list[str]:
    return [k for k, _ in events]


def of(events, kind) -> list[dict]:
    return [d for k, d in events if k == kind]


def say_turn(client, text="what is the car doing", conversation_id=None):
    body = {"transcript": text}
    if conversation_id:
        body["conversation_id"] = conversation_id
    return events_of(client.post("/api/agents/turn", body, format="json"))


def post_result(client, turn_id, call_id, outcome="ok", words="Done.", **extra):
    body = {"call_id": call_id, "outcome": outcome, "words": words, **extra}
    return client.post(f"/api/agents/turn/{turn_id}/tool_result", body, format="json")


@pytest.fixture
def limb(auth_client, engine):
    cache.clear()
    return attach(auth_client)


def paused(engine, client, *rounds, text="what is the car doing"):
    """A turn whose first stream ends on limb calls: (events, turn id, requests)."""
    engine.rounds = list(rounds)
    events = say_turn(client, text)
    requests = of(events, "tool_request")
    turn_id = events[0][1]["turn_id"]
    return events, turn_id, requests


# ---- dark ---------------------------------------------------------------------------------------


def test_switched_off_the_result_route_answers_404_after_authentication(auth_client, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    response = post_result(auth_client, "00000000-0000-0000-0000-000000000001", "x")
    assert response.status_code == 404 and response.data["detail"] == OFF_WORDS
    stranger = APIClient().post(
        "/api/agents/turn/00000000-0000-0000-0000-000000000001/tool_result", {}, format="json"
    )
    assert stranger.status_code in (401, 403)


def test_switched_off_the_throttle_answers_before_the_switch(auth_client, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    monkeypatch.setattr(relay_views.AgentToolResultThrottle, "rate", "1/min", raising=False)
    cache.clear()
    url = "/api/agents/turn/00000000-0000-0000-0000-000000000001/tool_result"
    assert auth_client.post(url, {}, format="json").status_code == 404
    assert auth_client.post(url, {}, format="json").status_code == 429


def test_a_recruiter_is_denied_the_result_route():
    from household.recruiter_routes import RECRUITER_DENIED

    assert "agents.relay_views.AgentToolResultView" in RECRUITER_DENIED


# ---- the relay, end to end ----------------------------------------------------------------------


def test_a_relayed_read_pauses_the_turn_and_its_result_streams_the_rest(auth_client, limb, engine):
    events, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    assert kinds(events) == ["turn", "tool_request", "done"]
    assert events[0][1]["stream_no"] == 1
    req = requests[0]
    assert req["name"] == "car_status" and req["turn_id"] == turn_id
    assert req["tainted"] is False and req["writes"] is False and req["read_through"] is False
    assert req["deadline_ms"] == 20_000 and req["confirm"] == "engine"
    done = events[-1][1]
    assert done["status"] == "awaiting_limb" and done["calls"] == [req["call_id"]]
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "awaiting_limb" and turn.pending_relay["limb_id"] == limb

    engine.rounds = [m.say("The engine is warm and the doors are locked.")]
    rest = events_of(
        post_result(auth_client, turn_id, req["call_id"], words="Engine warm, doors locked.")
    )
    assert kinds(rest)[0] == "turn" and rest[0][1]["stream_no"] == 2
    assert rest[0][1]["seq"] == events[0][1]["seq"]
    assert of(rest, "tool")[0]["name"] == "car_status" and of(rest, "tool")[0]["ok"]
    assert [s["text"] for s in of(rest, "sentence")] == [
        "The engine is warm and the doors are locked."
    ]
    assert of(rest, "sentence")[0]["line_id"].startswith(f"{turn.seq}.2.")
    assert rest[-1][1]["status"] == "done"
    sent = engine.models[-1].requests[0].contents[-1]["parts"][0]["functionResponse"]
    assert sent["name"] == "car_status" and "Engine warm" in sent["response"]["message"]
    turn.refresh_from_db()
    assert turn.status == "done" and turn.pending_relay is None
    row = AgentToolCall.objects.get(call_id=req["call_id"])
    assert row.where == limb and row.outcome == "ok"


def test_the_limb_tools_are_offered_beside_the_engine_tools_and_mail_never(
    auth_client, limb, engine
):
    paused(engine, auth_client, m.say("Hello."))
    offered = {d["name"]: d for d in engine.models[-1].requests[0].declarations}
    assert {"car_status", "set_timer", "navigate", "browser", "look_up"} <= set(offered)
    assert "ask_mail" not in offered
    assert offered["look_up"]["parameters"]["properties"]["domain"]["enum"] == ["places"]
    assert "read_list" in offered  # the engine's own


def test_a_call_after_a_limb_call_waits_so_the_round_keeps_its_order(auth_client, limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("navigate", {"to": "the invented depot"}), ("read_list", {}))
    )
    assert [r["name"] for r in requests] == ["navigate"]
    assert not AgentToolCall.objects.filter(tool="read_list").exists()
    engine.rounds = [m.say("On the way, and the list is empty.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    assert [t["name"] for t in of(rest, "tool")] == ["navigate", "read_list"]
    assert rest[-1][1]["status"] == "done"


def test_two_limb_calls_go_together_and_the_last_result_resumes(auth_client, limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 60}))
    )
    assert [r["name"] for r in requests] == ["car_status", "set_timer"]
    first = post_result(auth_client, turn_id, requests[0]["call_id"])
    assert first.status_code == 202 and first.data == {"waiting_for": [requests[1]["call_id"]]}
    engine.rounds = [m.say("Timer set for a minute.")]
    rest = events_of(post_result(auth_client, turn_id, requests[1]["call_id"]))
    assert rest[-1][1]["status"] == "done" and rest[-1][1]["writes_ran"] is True


def test_a_repeated_result_is_idempotent(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    engine.rounds = [m.say("All fine.")]
    events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    models_before = len(engine.models)
    again = post_result(auth_client, turn_id, requests[0]["call_id"], words="Something else.")
    assert again.status_code == 200 and again.data["status"] == "late" and again.data["recorded"]
    assert len(engine.models) == models_before
    assert AgentToolCall.objects.filter(call_id=requests[0]["call_id"]).count() == 1


def test_a_result_past_the_deadline_is_told_to_the_model_as_not_confirmed(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 5}))
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 22_000 + relay.GRACE_MS + 1)
    engine.rounds = [m.say("I couldn't confirm the timer.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    sent = engine.models[-1].requests[0].contents[-1]["parts"][0]["functionResponse"]
    assert sent["response"]["success"] is False
    assert sent["response"]["message"] == relay.TIMED_OUT_WORDS
    assert of(rest, "tool")[0]["outcome"] == "timed_out"
    assert rest[-1][1]["writes_ran"] is False


def test_an_open_call_past_its_deadline_times_out_when_the_next_post_comes(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 5}))
    )
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 22_000 + relay.GRACE_MS + 1)
    # The timer's write timed out; the model tries it again in the same turn: refused, not sent.
    engine.rounds = [m.call("set_timer", {"seconds": 6}), m.say("I couldn't confirm it.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    assert not of(rest, "tool_request")
    timer = [t for t in of(rest, "tool") if t["name"] == "set_timer"]
    assert [t["outcome"] for t in timer] == ["timed_out", "refused"]
    row = AgentToolCall.objects.get(call_id=requests[1]["call_id"])
    assert row.outcome == "timed_out"


def test_an_ok_after_a_timed_out_is_the_one_second_result_accepted(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 5}))
    )
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 22_000 + relay.GRACE_MS + 1)
    engine.rounds = [m.say("I couldn't confirm the timer.")]
    events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    late = post_result(auth_client, turn_id, requests[1]["call_id"], words="Timer started.")
    assert late.status_code == 200 and late.data["status"] == "late"
    assert AgentToolCall.objects.get(call_id=requests[1]["call_id"]).outcome == "ok"


def test_a_read_through_call_taints_the_turn_before_the_request_leaves(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    req = requests[0]
    assert req["read_through"] is True and req["tainted"] is False
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.tainted and turn.taint_sources == ["browser"]


def test_a_read_through_call_pauses_alone(auth_client, limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("browser", {"action": "read"}))
    )
    assert [r["name"] for r in requests] == ["car_status"]


def test_no_request_leaves_when_the_taint_mark_cannot_be_saved(
    auth_client, limb, engine, monkeypatch
):
    monkeypatch.setattr(TurnStore, "mark_taint", lambda self, state: False)
    engine.rounds = [m.call("browser", {"action": "read"}), m.say("I couldn't read it.")]
    events = say_turn(auth_client)
    assert not of(events, "tool_request") and events[-1][1]["status"] == "done"
    assert of(events, "tool")[0]["guard"] == "tainted"


def test_a_relayed_write_is_refused_in_a_tainted_turn(auth_client, limb, engine):
    """`guard_search_then_navigate`'s engine half, strict K1: a search, then a device action."""
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    engine.rounds = [m.call("set_timer", {"seconds": 60}), m.say("I can set it next time.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"], words="A page."))
    assert not of(rest, "tool_request")
    timer = [t for t in of(rest, "tool") if t["name"] == "set_timer"][0]
    assert timer["ok"] is False and timer["guard"] == "tainted"
    assert rest[-1][1]["tainted"] is True and "device_actions" not in rest[-1][1]


def test_a_relayed_read_after_outside_text_never_pauses(auth_client, limb, engine):
    """K2: the state holds outside text, so no pause, whatever K1 says."""
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    engine.rounds = [m.call("car_status", {}), m.say("Ask me about the car again.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"], words="A page."))
    assert not of(rest, "tool_request") and rest[-1][1]["status"] == "done"
    assert of(rest, "tool")[-1]["guard"] == "tainted"
    assert AgentTurn.objects.get(pk=turn_id).pending_relay is None


def test_k1_strict_and_permissive_differ_only_on_the_named_tool(monkeypatch):
    from agents.graph.state import TurnState
    from agents.runtime.spec import AgentSpec

    spec = AgentSpec("t", 1, "x", "p", ())
    state = TurnState("t", "c", 1, "h", "m", "phone", "x", taint=["web_search"])
    timer = relay.LimbTool("set_timer", writes=True, confirm="limb", tool_class="device")
    assert relay.decide(state, spec, timer, {}).verdict.guard == "tainted"
    monkeypatch.setattr(relay, "AFTER_OUTSIDE_TEXT", frozenset({"set_timer"}))
    # Tainted (say, carried from the last turn) but no outside text in this state: relayed.
    assert relay.decide(state, spec, timer, {}).go
    state.outside.append("web_search")
    assert relay.decide(state, spec, timer, {}).after_turn
    music = relay.LimbTool("control_music", actuates=True, confirm="limb", tool_class="device")
    assert relay.decide(state, spec, music, {}).verdict.guard == "tainted"


def test_confirm_limb_is_relayed_at_once_and_confirm_engine_waits_for_his_yes(
    auth_client, limb, engine
):
    _e, _turn, requests = paused(engine, auth_client, m.call("navigate", {"to": "the depot"}))
    assert requests[0]["confirm"] == "limb" and requests[0]["needs"] == ["screen"]

    engine.rounds = [m.call("garage_door", {"open": True}), m.say("Open the garage door?")]
    events = say_turn(auth_client, "open the garage")
    assert not of(events, "tool_request")
    assert of(events, "tool")[0]["guard"] == "needs_confirm"
    assert events[-1][1]["pending_confirm"] is True
    conversation = events[0][1]["conversation_id"]
    engine.rounds = [m.call("garage_door", {"open": True})]
    events = say_turn(auth_client, "yes", conversation_id=conversation)
    assert [r["name"] for r in of(events, "tool_request")] == ["garage_door"]


def test_a_cancel_between_hops_records_the_result_and_ends_the_turn(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 60}))
    assert auth_client.post(f"/api/agents/turn/{turn_id}/cancel").status_code == 200
    models_before = len(engine.models)
    after = post_result(auth_client, turn_id, requests[0]["call_id"], words="Timer started.")
    assert after.status_code == 200 and after.data["status"] == "cancelled"
    assert after.data["recorded"] is True
    assert len(engine.models) == models_before
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "cancelled" and turn.writes_ran and "loop" not in turn.pending_relay
    answered = [
        p["functionResponse"] for c in turn.history for p in c["parts"] if "functionResponse" in p
    ]
    assert answered[0]["response"]["success"] is True


def test_a_limb_that_disappears_cannot_post_and_the_next_turn_closes_the_pause(
    auth_client, limb, engine, household_user
):
    from household.models import DeviceToken

    events, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    DeviceToken.objects.filter(
        pk=AgentTurn.objects.get(pk=turn_id).pending_relay["device_id"]
    ).delete()
    assert not Limb.objects.exists()
    assert post_result(auth_client, turn_id, requests[0]["call_id"]).status_code in (401, 403)
    other = device_client(household_user)
    engine.rounds = [m.say("Hello again.")]
    say_turn(other, "hello", conversation_id=events[0][1]["conversation_id"])
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "failed" and "loop" not in turn.pending_relay
    assert AgentToolCall.objects.get(call_id=requests[0]["call_id"]).outcome == "timed_out"


def test_two_limbs_a_turn_offers_its_own_and_only_its_own_device_may_answer(
    auth_client, limb, engine, household_user
):
    other = device_client(household_user)
    attach(other, [dict(TOOLS[0], name="open_trunk")])
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    offered = {d["name"] for d in engine.models[-1].requests[0].declarations}
    assert "open_trunk" not in offered
    refused = post_result(other, turn_id, requests[0]["call_id"])
    assert refused.status_code == 404 and refused.data["code"] == "no_turn"
    assert AgentTurn.objects.get(pk=turn_id).status == "awaiting_limb"


def test_a_stale_pause_is_closed_by_his_next_turn_and_its_history_stays_whole(
    auth_client, limb, engine
):
    events, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    engine.rounds = [m.say("Hello.")]
    say_turn(auth_client, "never mind", conversation_id=events[0][1]["conversation_id"])
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "failed" and "loop" not in turn.pending_relay
    parts = [p for c in turn.history for p in c["parts"]]
    assert sum("functionCall" in p for p in parts) == sum("functionResponse" in p for p in parts)
    # The next turn's model was handed that whole exchange.
    contents = engine.models[-1].requests[0].contents
    assert any("functionResponse" in p for c in contents for p in c["parts"])
    late = post_result(auth_client, turn_id, requests[0]["call_id"])
    assert late.status_code == 200 and late.data["status"] == "late" and late.data["recorded"]


def test_an_unknown_call_id_and_a_wrong_tool_record_nothing(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    unknown = post_result(auth_client, turn_id, "not-a-call")
    assert unknown.status_code == 409 and unknown.data["code"] == "unknown_call"
    wrong = post_result(auth_client, turn_id, requests[0]["call_id"], name="set_timer")
    assert wrong.status_code == 409 and wrong.data["code"] == "wrong_tool"
    assert not AgentToolCall.objects.filter(call_id=requests[0]["call_id"]).exists()
    assert AgentTurn.objects.get(pk=turn_id).pending_relay["results"] == {}


def test_a_turn_that_is_not_his_is_not_found(auth_client, limb, engine, household_a):
    from household.models import HouseholdMember, User

    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    stranger = User.objects.create_user(email="other-member@example.invalid", first_name="Sam")
    HouseholdMember.objects.create(user=stranger, household=household_a)
    response = post_result(device_client(stranger), turn_id, requests[0]["call_id"])
    assert response.status_code == 404 and response.data["code"] == "no_turn"


def test_a_tool_the_manifest_never_offered_is_never_requested(auth_client, limb, engine):
    engine.rounds = [
        calls(("ask_mail", {}), ("look_up", {"domain": "ask_mail"})),
        m.say("I can't read mail that way."),
    ]
    events = say_turn(auth_client, "any mail from the invented shop")
    assert not of(events, "tool_request")
    guards = [t["guard"] for t in of(events, "tool")]
    assert guards == ["not_allowed", "not_allowed"]


def test_mail_then_timer_the_timer_runs_because_no_mail_was_read(auth_client, limb, engine):
    """`guard_mail_then_timer`'s engine half: the mail call is refused by class, so nothing
    outside was read, and the timer is relayed (untainted)."""
    _e, _t, requests = paused(
        engine,
        auth_client,
        calls(("look_up", {"domain": "ask_mail"}), ("set_timer", {"seconds": 30})),
    )
    assert [r["name"] for r in requests] == ["set_timer"] and requests[0]["tainted"] is False


def test_switch_car_through_a_fake_limb(auth_client, limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, m.call("switch_vehicle", {"name": "the invented hatchback"})
    )
    engine.rounds = [m.say("Switched to the hatchback.")]
    rest = events_of(
        post_result(auth_client, turn_id, requests[0]["call_id"], words="Active car: hatchback.")
    )
    assert rest[-1][1]["writes_ran"] is True and rest[-1][1]["honesty_flag"] is False


def test_an_oversize_result_is_refused_and_nothing_is_recorded(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    big = {"rows": ["x" * 1000] * 20}
    response = post_result(auth_client, turn_id, requests[0]["call_id"], result=big)
    assert response.status_code == 413 and response.data["code"] == "result_too_large"
    assert AgentTurn.objects.get(pk=turn_id).pending_relay["results"] == {}


def test_hops_are_bounded_by_the_turns_rounds(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    for stream_no in (2, 3):
        engine.rounds = [m.call("car_status", {})]
        rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
        requests = of(rest, "tool_request")
        assert requests and of(rest, "turn")[0]["stream_no"] == stream_no
    engine.rounds = [m.say("That is all I have.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    # Four rounds in all: the last is tool-free, so the turn ends.
    assert rest[-1][1]["status"] == "done"
    assert not engine.models[-1].requests[0].allow_tools


# ---- composition with the streamed door and the late-answer path --------------------------------


def test_a_turn_begun_on_the_streamed_door_resumes_spoken(auth_client, limb, engine, monkeypatch):
    from agents.legion.flags import STREAM_STT_ENV
    from tests.test_engine_voice_live_stt import DONE, FakeSocket, _final
    from tests.test_engine_voice_stream import utterance

    monkeypatch.setenv(STREAM_STT_ENV, "on")
    socket = FakeSocket(after_end=[_final("How is the car?"), DONE])
    monkeypatch.setattr("agents.voice.streaming.socket_for", lambda key: socket)
    monkeypatch.setattr("agents.voice.speech.voiced_speech", lambda pcm: True)
    monkeypatch.setattr("agents.voice.speech.LOUDNESS_GATE_RMS", 0)
    engine.rounds = [m.call("car_status", {})]
    first = events_of(
        auth_client.post(
            "/api/agents/turn/stream", utterance(), content_type="application/octet-stream"
        )
    )
    assert kinds(first)[-2:] == ["tool_request", "done"]
    assert first[-1][1]["status"] == "awaiting_limb"
    turn_id = of(first, "turn")[0]["turn_id"]
    engine.rounds = [m.say("The car is fine.")]
    rest = events_of(post_result(auth_client, turn_id, of(first, "tool_request")[0]["call_id"]))
    assert of(rest, "audio"), "the continuation is spoken, as its first stream was"
    seq = of(first, "turn")[0]["seq"]
    assert of(rest, "sentence")[0]["line_id"] == f"{seq}.2.1"
    assert rest[-1][1]["contract_version"] == CONTRACT_VERSION


def test_an_owed_late_line_is_said_once_not_again_on_the_continuation(
    auth_client, limb, engine, household_user, monkeypatch
):
    from datetime import timedelta

    from django.utils import timezone

    from agents.legion import late
    from agents.legion.flags import LATE_ANSWERS_ENV
    from agents.models import OwedLine

    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()
    OwedLine.objects.create(
        household=household_user.household,
        owner_user=household_user,
        source="late_task",
        source_id="made-up",
        text="About the invented ferry: it leaves at noon.",
        expires_at=timezone.now() + timedelta(hours=1),
    )
    first, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    assert [s.get("role") for s in of(first, "sentence")] == ["late"]
    engine.rounds = [m.say("The car is fine.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    assert [s["role"] for s in of(rest, "sentence")] == ["answer"]


# ---- nothing changes where no limb is -----------------------------------------------------------


def test_the_late_runner_and_a_browser_have_no_limb_and_ask_nothing_for_one(household_user):
    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    from agents.evals.runner import member_request
    from agents.legion.gateway import LegionGateway

    gateway = LegionGateway(member_request(household_user))
    with CaptureQueriesContext(connection) as queries:
        assert gateway.limb_tools() == {}
    assert not any("agents_limb" in q["sql"] for q in queries.captured_queries)


def test_a_web_turn_with_a_limb_attached_is_unchanged(household_user, monkeypatch):
    """The live web door (cascade) makes exactly its deployed queries and offers no limb tool,
    with the member's phone attached as a limb (`test_late_answers_web`'s measured 62)."""
    import uuid

    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    from agents.runtime.model import FakeModel, say
    from assistant import cascade, views
    from ingest.statements import GEMINI_KEY_ENV
    from tests.test_assistant import browser
    from tests.test_assistant_cascade import KEY, FakeSpeech, _events, _post

    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setattr(cascade, "FILLER_AUDIO", cascade.FillerAudio(spawn=lambda work: None))
    monkeypatch.setenv(GEMINI_KEY_ENV, KEY)
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)
    monkeypatch.delenv("LEGION_LATE_ANSWERS", raising=False)
    phone = device_client(household_user)
    attach(phone)
    model = FakeModel([say("Nothing is on today.")])
    monkeypatch.setattr(views, "_cascade_clients", lambda: (model, FakeSpeech()))
    member = browser(household_user)
    with CaptureQueriesContext(connection) as queries:
        _events(_post(member, text="Anything on today?", turn_id=str(uuid.uuid4())))
    sql = [q["sql"] for q in queries.captured_queries]
    assert not any("agents_limb" in s for s in sql)
    assert len(sql) == 62
    offered = {d["name"] for d in model.requests[0].declarations}
    assert not offered & {t["name"] for t in TOOLS}


# ---- review fixes: a spent yes, the request flag across hops, the resume claim ------------------


def test_a_yes_spent_on_a_relayed_call_is_not_released_again_after_a_stale_pause(
    auth_client, limb, engine
):
    engine.rounds = [m.call("garage_door", {"open": True}), m.say("Open the garage door?")]
    events = say_turn(auth_client, "open the garage")
    conversation = events[0][1]["conversation_id"]
    engine.rounds = [m.call("garage_door", {"open": True})]
    yes = say_turn(auth_client, "yes", conversation_id=conversation)
    assert [r["name"] for r in of(yes, "tool_request")] == ["garage_door"]
    # The device never answers; he says "ok" to something else: the yes was spent.
    engine.rounds = [m.call("garage_door", {"open": True}), m.say("Open it again?")]
    again = say_turn(auth_client, "ok", conversation_id=conversation)
    assert not of(again, "tool_request")
    assert of(again, "tool")[0]["guard"] == "needs_confirm"


def test_after_a_relayed_read_the_continuation_still_refuses_engine_writes(
    auth_client, limb, engine
):
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    engine.rounds = [
        m.call("add_checklist_item", {"checklist": "Errands", "text": "invented stamps"}),
        m.say("I can add that next time."),
    ]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"], words="A page."))
    added = [t for t in of(rest, "tool") if t["name"] == "add_checklist_item"][0]
    assert added["ok"] is False and added["guard"] == "tainted"


def test_a_continuation_arms_the_request_flag_from_the_turns_taint(household_user):
    """A procedure's steps check the request's own flag, not the turn's state: a relay's
    continuation is a new request, so the gateway arms it before any engine tool runs."""
    from agents.evals.runner import member_request
    from agents.graph.state import TurnState
    from agents.legion import specs
    from agents.legion.gateway import LegionGateway
    from agents.runtime.tools import ToolCall
    from assistant import read_through

    request = member_request(household_user)
    state = TurnState("t", "c", 1, "h", "m", "phone", "x", taint=["browser"])
    assert not read_through.read_third_party(request)
    LegionGateway(request).call(state, specs.spec("generalist"), ToolCall("", "read_list", {}), 6)
    assert read_through.read_third_party(request)


def test_two_completing_posts_resume_the_turn_once(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    engine.rounds = [m.say("All fine.")]
    first = post_result(auth_client, turn_id, requests[0]["call_id"])
    assert first.status_code == 200 and first["Content-Type"] == "text/event-stream"
    models_before = len(engine.models)
    second = post_result(auth_client, turn_id, requests[0]["call_id"])
    assert second.status_code == 200 and second.data["status"] == "running"
    assert events_of(first)[-1][1]["status"] == "done"
    assert len(engine.models) == models_before + 1
