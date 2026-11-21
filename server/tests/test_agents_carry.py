"""The carry on the engine brain (owner's ruling 2026-11-21, ADR 0090 amended): the turn after one
that read outside text refuses every write, in the carried words, for that one turn. Fake model;
the web search answers with an invented page; nothing reaches Google."""

from __future__ import annotations

import pytest

from agents.evals import fixture
from agents.legion import carry, late
from agents.legion import client_facts as facts_of
from agents.runtime import model as m
from assistant import read_through
from engine_mcp.tools import ok
from tests.test_agents_legion import NOW, TODAY, _turn

pytestmark = pytest.mark.django_db

ADD = {"checklist_id": str(fixture.rid("Todo")), "text": "made-up stamps"}


@pytest.fixture
def seeded(household_user, household_a, monkeypatch):
    from agents.legion import gateway

    monkeypatch.setattr("engine_mcp.tools._now", lambda: NOW)
    fixture.seed(household_a, household_user, TODAY)
    real = gateway.run_engine_tool

    def fake(request, tool, args):
        if tool.name == "web_search":
            return ok("An invented page says the invented museum opens at nine.")
        return real(request, tool, args)

    monkeypatch.setattr(gateway, "run_engine_tool", fake)
    return household_user


def _search(user, conversation=None):
    model = m.FakeModel([m.call("web_search", {"query": "invented museum"}), m.say("Nine.")])
    return _turn(user, "when does the invented museum open", model, conversation)


def _add(user, conversation, words="add that to my list"):
    model = m.FakeModel([m.call("add_checklist_item", ADD), m.say("Okay.")])
    events, state, _c = _turn(user, words, model, conversation)
    tool = next(e.data for e in events if e.type == "tool")
    return tool, state


def _response(state, name) -> dict:
    return next(
        p["functionResponse"]["response"]
        for c in reversed(state.messages)
        for p in c.get("parts") or []
        if p.get("functionResponse", {}).get("name") == name
    )


def test_the_carry_sources_match_their_owners():
    assert facts_of.CARRY in carry.CARRY_SOURCES
    assert late.AFTER_CARRY in carry.CARRY_SOURCES
    assert late.CARRY not in carry.CARRY_SOURCES


def test_add_that_the_turn_after_a_search_is_refused_in_the_carried_words(seeded):
    _e, _s, conversation = _search(seeded)
    tool, state = _add(seeded, conversation)
    assert tool["ok"] is False and tool["guard"] == "tainted"
    assert state.taint == [carry.PREVIOUS_TURN] and not state.writes_ran
    calls = state.actions
    assert calls[-1]["name"] == "add_checklist_item" and calls[-1]["ok"] is False
    response = _response(state, "add_checklist_item")
    assert response["message"] == read_through.CARRY_WRITE_REFUSAL.format(
        verb="changed", name="add_checklist_item"
    )


def test_a_write_he_fully_names_himself_also_waits_then_the_carry_clears(seeded):
    _e, _s, conversation = _search(seeded)
    tool, _state = _add(seeded, conversation, "add made-up stamps to my to-do list")
    assert tool["ok"] is False
    tool, state = _add(seeded, conversation, "add made-up stamps to my to-do list")
    assert tool["ok"] is True and state.writes_ran and state.taint == []


def test_a_turn_with_no_outside_read_carries_nothing(seeded):
    _e, _s, conversation = _turn(seeded, "hello", m.FakeModel([m.say("Hello.")]))
    tool, _state = _add(seeded, conversation)
    assert tool["ok"] is True


def test_the_same_turn_keeps_the_same_turn_words(seeded):
    model = m.FakeModel(
        [
            m.call("web_search", {"query": "invented museum"}),
            m.call("add_checklist_item", ADD),
            m.say("Nine."),
        ]
    )
    _e, state, _c = _turn(seeded, "look it up and add it", model)
    response = _response(state, "add_checklist_item")
    assert response["message"] == read_through.WRITE_REFUSAL.format(
        verb="changed", name="add_checklist_item"
    )


def test_only_carried():
    assert carry.only_carried([carry.PREVIOUS_TURN])
    assert carry.only_carried(["carry"])
    assert not carry.only_carried([carry.PREVIOUS_TURN, "web_search"])
    assert not carry.only_carried([])
    assert carry.carries(["web_search"]) and carry.carries(["late_carry"])
    assert not carry.carries([carry.PREVIOUS_TURN]) and not carry.carries([])


def _cancelled_turn(conversation, taint=()):
    """A turn that was never answered: the phone took it (noise, a stop) or it was cut off."""
    from django.db.models import Max

    from agents.models import AgentTurn

    seq = (conversation.turns.aggregate(m=Max("seq"))["m"] or 0) + 1
    AgentTurn.objects.create(
        household_id=conversation.household_id,
        owner_user_id=conversation.owner_user_id,
        conversation=conversation,
        seq=seq,
        status="cancelled",
        taint_sources=list(taint),
    )


def test_a_turn_the_phone_took_between_does_not_spend_the_carry(seeded):
    _e, _s, conversation = _search(seeded)
    _cancelled_turn(conversation)
    tool, _state = _add(seeded, conversation)
    assert tool["ok"] is False


def test_a_carried_turn_cut_off_before_its_answer_does_not_spend_the_carry(seeded):
    _e, _s, conversation = _search(seeded)
    _cancelled_turn(conversation, [carry.PREVIOUS_TURN])
    tool, _state = _add(seeded, conversation)
    assert tool["ok"] is False
    tool, _state = _add(seeded, conversation)
    assert tool["ok"] is True
