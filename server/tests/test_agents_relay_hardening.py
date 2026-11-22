"""engine-brain ticket 04, slice E2: the relay's before-switch-on list (blind review 2026-11-21).

A dropped continuation and a dead process; per-call deadlines in a round run one at a time; a
result after a cancel; the engine's own yes for a limb write with no gate of its own; deny by
default for limb tools and the engine's own outside-text classes; the result route's body
limits and the no-key / daily-allowance gates; the hop cap; `GET /api/agents/turn/<id>`; K1's
device actions end to end; two completing posts at once on real row locks.

Google is never called (`FakeModel`). Every device, tool and word is invented.
"""

# ruff: noqa: F811 - fixtures are imported from the relay API tests

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from agents.legion import relay, relay_turn
from agents.models import AgentToolCall, AgentTurn
from agents.runtime import model as m
from tests.test_agents_relay_api import (  # noqa: F401
    TOOLS,
    attach,
    calls,
    device_client,
    events_of,
    limb,
    of,
    paused,
    post_result,
    say_turn,
)
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db


def _responses(turn) -> list[dict]:
    return [
        p["functionResponse"]
        for c in turn.history or []
        for p in c.get("parts") or []
        if "functionResponse" in p
    ]


# ---- a continuation that stops -------------------------------------------------------------------


def test_a_dropped_continuation_closes_the_turn_and_the_next_turn_sees_the_device_write(
    auth_client, limb, engine
):
    events, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 60}))
    engine.rounds = [m.say("Timer set.")]
    response = post_result(auth_client, turn_id, requests[0]["call_id"], words="Timer started.")
    stream = iter(response.streaming_content)
    assert b"event: turn" in next(stream)
    # He hung up after the first event: the test client's own wrapper closes the stream.
    response._iterator.close()
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "failed" and turn.writes_ran
    timer = [r for r in _responses(turn) if r["name"] == "set_timer"][0]["response"]
    assert timer["success"] is True and "Timer started" in timer["message"]
    engine.rounds = [m.say("Yes, it is running.")]
    say_turn(auth_client, "is my timer on", conversation_id=events[0][1]["conversation_id"])
    assert "Timer started" in json.dumps(engine.models[-1].requests[0].contents)


def test_a_continuation_whose_process_died_is_closed_on_his_next_request(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 60}))
    post_result(auth_client, turn_id, requests[0]["call_id"], words="Timer started.")
    # The stream is never read: as if the process died right after the claim.
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "running" and turn.writes_ran
    resumed = turn.pending_relay["resumed_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: resumed + relay_turn.RUNNING_STALE_MS + 1)
    engine.rounds = [m.say("Hello.")]
    say_turn(auth_client, "hello")  # another conversation: the member-wide sweep closes it
    turn.refresh_from_db()
    assert turn.status == "failed" and turn.writes_ran
    assert "Timer started" in json.dumps(turn.history)


def test_a_paused_turn_past_the_idle_close_is_closed(auth_client, limb, engine, monkeypatch):
    _e, turn_id, _r = paused(engine, auth_client, m.call("car_status", {}))
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + relay_turn.IDLE_CLOSE_MS + 1)
    assert auth_client.get(f"/api/agents/turn/{turn_id}").data["status"] == "failed"


# ---- deadlines, cancel ---------------------------------------------------------------------------


def test_each_call_is_timed_from_when_the_device_can_start_it(
    auth_client, limb, engine, monkeypatch
):
    """A 60 s confirm screen on call 1 does not make call 2 (2 s) late: call 2's deadline is
    asked + 60 s + 2 s + the grace."""
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("navigate", {"to": "the depot"}), ("set_timer", {"seconds": 9}))
    )
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 58_000)
    assert post_result(auth_client, turn_id, requests[0]["call_id"]).status_code == 202
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 66_000)
    engine.rounds = [m.say("On the way, timer set.")]
    rest = events_of(post_result(auth_client, turn_id, requests[1]["call_id"]))
    assert [t["outcome"] for t in of(rest, "tool")] == ["ok", "ok"]
    assert rest[-1][1]["writes_ran"] is True


def test_after_a_cancel_a_later_result_for_another_call_is_recorded_for_the_next_turn(
    auth_client, limb, engine
):
    events, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 60}))
    )
    auth_client.post(f"/api/agents/turn/{turn_id}/cancel")
    first = post_result(auth_client, turn_id, requests[0]["call_id"], words="All fine.")
    assert first.data["status"] == "cancelled" and first.data["recorded"] is True
    later = post_result(auth_client, turn_id, requests[1]["call_id"], words="Timer started.")
    assert later.status_code == 200 and later.data["status"] == "cancelled"
    assert "recorded" in later.data["detail"]
    assert AgentToolCall.objects.get(call_id=requests[1]["call_id"]).outcome == "ok"
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.writes_ran
    timer = [r for r in _responses(turn) if r["name"] == "set_timer"][0]["response"]
    assert timer["success"] is True
    engine.rounds = [m.say("It is running.")]
    say_turn(auth_client, "is the timer on", conversation_id=events[0][1]["conversation_id"])
    assert "Timer started" in json.dumps(engine.models[-1].requests[0].contents)


def test_a_call_not_run_after_a_cancel_is_recorded_as_refused(auth_client, limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 60}))
    )
    auth_client.post(f"/api/agents/turn/{turn_id}/cancel")
    post_result(auth_client, turn_id, requests[0]["call_id"])
    post_result(auth_client, turn_id, requests[1]["call_id"], "not_run", words="Not started.")
    assert AgentToolCall.objects.get(call_id=requests[1]["call_id"]).outcome == "refused"
    assert not AgentTurn.objects.get(pk=turn_id).writes_ran


# ---- who says yes, which tools, which results taint ---------------------------------------------


def test_a_limb_write_with_no_gate_of_its_own_waits_for_his_yes(auth_client, limb, engine):
    engine.rounds = [m.call("save_parking_spot", {"note": "level 2"}), m.say("Save it?")]
    events = say_turn(auth_client, "remember where I parked")
    assert not of(events, "tool_request")
    assert of(events, "tool")[0]["guard"] == "needs_confirm"
    engine.rounds = [m.call("save_parking_spot", {"note": "level 2"})]
    yes = say_turn(auth_client, "yes", conversation_id=events[0][1]["conversation_id"])
    assert [r["name"] for r in of(yes, "tool_request")] == ["save_parking_spot"]


@pytest.mark.parametrize(
    "tool",
    [
        {"name": "read_sms", "tool_class": "device"},
        {"name": "read_sms", "tool_class": "messages"},
        {"name": "messages_summary", "tool_class": "household"},
        {"name": "read_notifications", "tool_class": "device"},
        {"name": "phone_widget"},
        {"name": "phone_widget", "tool_class": "gizmo"},
    ],
    ids=[
        "sms-as-device",
        "messages-class",
        "messages-name",
        "under-declared",
        "no-class",
        "unknown",
    ],
)
def test_deny_by_default_a_new_tool_is_never_offered(auth_client, engine, tool):
    cache.clear()
    entry = {
        "description": "Invented.",
        "parameters": {"type": "object", "properties": {}},
        "writes": False,
        "read_through": False,
        "actuates": False,
        **tool,
    }
    attach(auth_client, [*TOOLS, entry])
    engine.rounds = [m.call(tool["name"], {}), m.say("I can't do that here.")]
    events = say_turn(auth_client, "do the invented thing")
    offered = {d["name"] for d in engine.models[-1].requests[0].declarations}
    assert tool["name"] not in offered and "car_status" in offered
    assert not of(events, "tool_request")


def test_an_outside_class_taints_whatever_the_manifest_flag_says(auth_client, engine):
    cache.clear()
    clip = {
        "name": "clipboard_read",
        "tool_class": "clipboard",
        "description": "Read the clipboard.",
        "parameters": {"type": "object", "properties": {}},
        "writes": False,
        "read_through": False,
        "actuates": False,
    }
    attach(auth_client, [*TOOLS, clip])
    _e, turn_id, requests = paused(engine, auth_client, m.call("clipboard_read", {}))
    assert requests[0]["read_through"] is True
    assert AgentTurn.objects.get(pk=turn_id).taint_sources == ["clipboard_read"]


def test_the_engine_classes_pin_what_taints():
    plain = relay.LimbTool("car_status", tool_class="vehicle")
    assert relay.relayable(plain) and not relay.reads_outside(plain, {})
    for cls in relay.OUTSIDE_CLASSES:
        tool = relay.LimbTool("reader", tool_class=cls)
        assert relay.relayable(tool) and relay.reads_outside(tool, {})
    # A flag can only add taint.
    flagged = relay.LimbTool("car_status", tool_class="vehicle", read_through=True)
    assert relay.reads_outside(flagged, {})


def test_relay_hops_are_capped_per_turn(auth_client, limb, engine, monkeypatch):
    monkeypatch.setattr(relay, "MAX_RELAY_HOPS", 1)
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    engine.rounds = [m.call("car_status", {}), m.say("That is all.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    assert not of(rest, "tool_request")
    assert of(rest, "tool")[-1]["guard"] == "budget"


# ---- the result route's limits and gates ---------------------------------------------------------


def test_the_result_route_needs_a_length_and_refuses_deep_json(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    url = f"/api/agents/turn/{turn_id}/tool_result"
    body = json.dumps({"call_id": requests[0]["call_id"], "outcome": "ok", "words": "x"})
    no_length = auth_client.generic("POST", url, body, "application/json", CONTENT_LENGTH="")
    assert no_length.status_code == 411 and no_length.data["code"] == "length_required"
    deep = '{"call_id": "x", "outcome": "ok", "words": "x", "result": {"a": ' + "[" * 5000
    deep += "]" * 5000 + "}}"
    refused = auth_client.generic("POST", url, deep, "application/json")
    assert refused.status_code == 400
    assert AgentTurn.objects.get(pk=turn_id).pending_relay["results"] == {}


def test_with_no_key_the_result_is_recorded_and_a_re_post_resumes(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 5}))
    monkeypatch.delenv("LEGION_GEMINI_KEY")
    refused = post_result(auth_client, turn_id, requests[0]["call_id"], words="Timer started.")
    assert refused.status_code == 503 and refused.data["recorded"] is True
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "awaiting_limb" and turn.writes_ran
    assert AgentToolCall.objects.get(call_id=requests[0]["call_id"]).outcome == "ok"
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    engine.rounds = [m.say("Timer set.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    assert rest[-1][1]["status"] == "done"


def test_over_the_daily_allowance_the_result_is_recorded_and_the_turn_closes(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 5}))
    models_before = len(engine.models)
    monkeypatch.setattr("agents.relay_views.allowance.over_cap", lambda user: True)
    refused = post_result(auth_client, turn_id, requests[0]["call_id"], words="Timer started.")
    assert refused.status_code == 429 and refused.data["code"] == "member_cap"
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.status == "failed" and turn.writes_ran
    assert len(engine.models) == models_before


def test_a_limb_that_lost_the_stream_learns_its_pending_calls_again(
    auth_client, limb, engine, household_user
):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 5}))
    )
    got = auth_client.get(f"/api/agents/turn/{turn_id}")
    assert got.status_code == 200 and got.data["status"] == "awaiting_limb"
    assert got.data["waiting_for"] == [r["call_id"] for r in requests]
    assert got.data["calls"] == requests
    post_result(auth_client, turn_id, requests[0]["call_id"])
    again = auth_client.get(f"/api/agents/turn/{turn_id}").data
    assert again["waiting_for"] == [requests[1]["call_id"]]
    assert again["recorded"] == [requests[0]["call_id"]]
    assert device_client(household_user).get(f"/api/agents/turn/{turn_id}").status_code == 404


# ---- K1 groundwork: device actions after the reply -----------------------------------------------


def test_k1_empty_a_device_action_after_outside_text_is_refused(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    engine.rounds = [m.call("control_music", {"action": "play"}), m.say("Ask me again.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"], words="A page."))
    assert "device_actions" not in rest[-1][1]
    assert of(rest, "tool")[-1]["guard"] == "tainted"


@pytest.mark.parametrize(
    ("name", "args", "writes"),
    [
        ("set_timer", {"seconds": 60}, True),
        ("control_music", {"action": "play"}, False),
        ("control_volume", {"level": 4}, False),
    ],
)
def test_k1_timer_music_volume_run_after_the_reply_with_a_call_id_and_a_result(
    auth_client, limb, engine, monkeypatch, name, args, writes
):
    monkeypatch.setattr(
        relay, "AFTER_OUTSIDE_TEXT", frozenset({"set_timer", "control_music", "control_volume"})
    )
    _e, turn_id, requests = paused(engine, auth_client, m.call("browser", {"action": "read"}))
    engine.rounds = [m.call(name, args), m.say("That starts after this.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"], words="A page."))
    assert not of(rest, "tool_request")
    (action,) = rest[-1][1]["device_actions"]
    assert action["name"] == name and action["args"] == args and action["after_reply"] is True
    assert of(rest, "tool")[-1]["outcome"] == "after_reply"
    assert not AgentToolCall.objects.filter(call_id=action["call_id"]).exists()
    done = post_result(auth_client, turn_id, action["call_id"], words="Done on the device.")
    assert done.status_code == 200 and done.data["status"] == "late"
    again = post_result(auth_client, turn_id, action["call_id"], words="Done again.")
    assert again.status_code == 200
    row = AgentToolCall.objects.get(call_id=action["call_id"])
    assert row.outcome == "ok" and row.where == limb
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.writes_ran is writes
    patched = [r for r in _responses(turn) if r["name"] == name][0]["response"]
    assert patched["success"] is True and patched["call_id"] == action["call_id"]


# ---- two completing posts at once, on real row locks --------------------------------------------


@pytest.mark.django_db(transaction=True)
def test_two_completing_posts_at_once_resume_once(auth_client, limb, engine, monkeypatch):
    import threading

    from django.db import connection

    _e, turn_id, requests = paused(engine, auth_client, m.call("car_status", {}))
    engine.rounds = [m.say("All fine.")]
    models_before = len(engine.models)
    barrier = threading.Barrier(2)
    real_owned = relay_turn._owned

    def owned(request, pk):
        barrier.wait(timeout=10)  # both posts reach the row lock together
        return real_owned(request, pk)

    monkeypatch.setattr(relay_turn, "_owned", owned)
    answers = []

    def post():
        from rest_framework.test import APIClient

        client = APIClient()
        client.credentials(**auth_client._credentials)
        try:
            response = post_result(client, turn_id, requests[0]["call_id"])
            if response.get("Content-Type") == "text/event-stream":
                answers.append(("stream", events_of(response)[-1][1]["status"]))
            else:
                answers.append(("json", response.data["status"]))
        finally:
            connection.close()

    threads = [threading.Thread(target=post) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    assert len(answers) == 2
    kinds = sorted(k for k, _s in answers)
    assert kinds == ["json", "stream"], answers
    assert len(engine.models) == models_before + 1


# ---- re-check 1121: idle close vs deadlines, calls per round, late-result window, classes -------


def test_a_round_gets_at_most_four_device_calls(auth_client, limb, engine):
    _e, _t, requests = paused(engine, auth_client, calls(*[("car_status", {})] * 5))
    assert len(requests) == relay.MAX_CALLS_PER_ROUND == 4
    # 4 calls at the manifest's longest deadline still end before the idle close.
    assert 4 * 120_000 + relay.GRACE_MS < relay_turn.IDLE_CLOSE_MS


def test_the_idle_close_waits_for_the_rounds_last_deadline(auth_client, engine, monkeypatch):
    cache.clear()
    slow = [dict(t, deadline_ms=120_000) if t["name"] == "car_status" else t for t in TOOLS]
    attach(auth_client, slow)
    monkeypatch.setattr(relay_turn, "IDLE_CLOSE_MS", 1_000)
    _e, turn_id, _r = paused(engine, auth_client, calls(("car_status", {}), ("car_status", {})))
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["asked_at_ms"]
    url = f"/api/agents/turn/{turn_id}"
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 200_000)
    assert auth_client.get(url).data["status"] == "awaiting_limb"
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 240_000 + relay.GRACE_MS + 1)
    assert auth_client.get(url).data["status"] == "failed"


def test_a_late_result_is_marked_in_history_and_refused_after_24_hours(
    auth_client, limb, engine, monkeypatch
):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("car_status", {}), ("set_timer", {"seconds": 60}))
    )
    auth_client.post(f"/api/agents/turn/{turn_id}/cancel")
    post_result(auth_client, turn_id, requests[0]["call_id"])
    asked = AgentTurn.objects.get(pk=turn_id).pending_relay["issued"][requests[1]["call_id"]][
        "asked_at_ms"
    ]
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + relay_turn.LATE_RESULT_MS + 1)
    gone = post_result(auth_client, turn_id, requests[1]["call_id"], words="Timer started.")
    assert gone.status_code == 410 and gone.data["code"] == "too_late"
    assert AgentToolCall.objects.get(call_id=requests[1]["call_id"]).outcome == "timed_out"
    monkeypatch.setattr(relay_turn, "_now_ms", lambda: asked + 60_000)
    post_result(auth_client, turn_id, requests[1]["call_id"], words="Timer started.")
    turn = AgentTurn.objects.get(pk=turn_id)
    timer = [r for r in _responses(turn) if r["name"] == "set_timer"][0]["response"]
    assert timer["reported_late"] is True
    assert timer["message"].startswith(relay_turn.LATE_REPORT)


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        ({"name": "day", "tool_class": "household"}, {"action": "read_calendar"}),
        ({"name": "find_place", "tool_class": "web"}, {"query": "coffee"}),
        ({"name": "phone_lookup", "tool_class": "household"}, {"action": "caller_id"}),
        ({"name": "invites_today", "tool_class": "calendar"}, {}),
    ],
    ids=["calendar-in-day", "place-search", "caller-id", "calendar-class"],
)
def test_text_other_people_wrote_taints_the_turn(auth_client, engine, tool, args):
    cache.clear()
    entry = {
        "description": "Invented.",
        "parameters": {"type": "object", "properties": {"action": {"type": "string"}}},
        "writes": False,
        "read_through": False,
        "actuates": False,
        **tool,
    }
    attach(auth_client, [*TOOLS, entry])
    _e, turn_id, requests = paused(engine, auth_client, m.call(tool["name"], args))
    assert requests[0]["read_through"] is True
    assert AgentTurn.objects.get(pk=turn_id).tainted


def test_a_day_read_of_lists_stays_plain_and_a_place_search_must_say_web(auth_client, engine):
    cache.clear()
    day = {
        "name": "day",
        "tool_class": "household",
        "description": "The day.",
        "parameters": {"type": "object", "properties": {"action": {"type": "string"}}},
        "writes": False,
        "read_through": False,
        "actuates": False,
    }
    search = {**day, "name": "search_places", "tool_class": "place"}
    attach(auth_client, [*TOOLS, day, search])
    _e, turn_id, requests = paused(engine, auth_client, m.call("day", {"action": "read_list"}))
    assert requests[0]["read_through"] is False
    assert not AgentTurn.objects.get(pk=turn_id).tainted
    offered = {d["name"] for d in engine.models[-1].requests[0].declarations}
    assert "search_places" not in offered  # an outside-sounding name with a plain class
