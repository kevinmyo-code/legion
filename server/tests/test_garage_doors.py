"""The garage switch at every door, and the torque guard. Every figure here is MADE UP.

With `LEGION_JEEP_ASSISTANT` unset every door behaves as it did: `show_part` is in no list, no
declaration and no prompt, a call to it is refused as unknown, and no sentence is checked for a
torque. On, a member's web assistant gets it and the guard; a recruiter, `/mcp` and the head unit
never do.
"""

from __future__ import annotations

import datetime as dt
from decimal import Decimal

import pytest
from django.core.cache import cache

from agents.legion.speech_rules import backed_torque, states_unbacked_torque, torque_figures
from agents.runtime.model import FakeModel, call, say
from assistant import cascade, views
from assistant.surface import (
    RECRUITER_TOOLS,
    WEB_TOOL_NAMES,
    member_only_tools,
    off_registry_tool,
    refusal_for,
    tools_for,
)
from engine_mcp import cards
from engine_mcp.server import _tools_for_request
from engine_mcp.tools import TOOLS_BY_NAME
from garage import flags, specs
from garage import xj_front as m
from garage.models import FastenerSpec
from household.models import Household, HouseholdMember, User
from ingest.statements import GEMINI_KEY_ENV
from tests.test_assistant import _tool, browser
from tests.test_assistant_cascade import FakeSpeech, _events, _of, _post
from tests.test_recruiter import _recruiter_browser, _visitor_token


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    # As `tests/test_assistant_cascade.py`: no filler audio, a key in the environment.
    monkeypatch.setattr(cascade, "FILLER_AUDIO", cascade.FillerAudio(spawn=lambda work: None))
    monkeypatch.setenv(GEMINI_KEY_ENV, "test-key-not-real")
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)
    monkeypatch.delenv(flags.ENV, raising=False)
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def engine(monkeypatch):
    class Engine:
        model = FakeModel([say("Nothing is on today.")])
        speech = FakeSpeech()

    monkeypatch.setattr(views, "_cascade_clients", lambda: (Engine.model, Engine.speech))
    return Engine


@pytest.fixture
def member(household_user):
    return browser(household_user)


@pytest.fixture
def visitor_user(db):
    demo = Household.objects.create(name="Demo visit garage", is_demo_visit=True)
    user = User.objects.create_user(email="visitor-garage@demo.invalid", first_name="Pat")
    user.set_unusable_password()
    user.save()
    HouseholdMember.objects.create(user=user, household=demo, role=HouseholdMember.RECRUITER)
    return user


@pytest.fixture
def visitor(visitor_user):
    token, _raw = _visitor_token(visitor_user)
    return _recruiter_browser(visitor_user, token)


@pytest.fixture
def switch_on(monkeypatch):
    monkeypatch.setenv(flags.ENV, "on")


def _said(events) -> list[str]:
    return [s["text"] for s in _of(events, "sentence")]


def _declared(engine_) -> set[str]:
    return {d["name"] for d in engine_.model.requests[0].declarations}


# =============================================================================
# Switch off: exactly as before
# =============================================================================


def test_off_the_tool_is_in_no_list(household_user, visitor_user):
    assert not flags.enabled()
    assert "show_part" not in TOOLS_BY_NAME
    assert "show_part" not in WEB_TOOL_NAMES and "show_part" not in RECRUITER_TOOLS
    assert member_only_tools(household_user) == {}
    assert [t.name for t in tools_for(household_user)] == list(WEB_TOOL_NAMES)
    assert off_registry_tool("show_part", household_user) is None
    assert refusal_for("show_part", household_user) is not None
    assert refusal_for("show_part", visitor_user) is not None


@pytest.mark.parametrize("value", ["", "off", "1", "true", "ON "])
def test_only_the_word_on_turns_it_on(monkeypatch, value):
    monkeypatch.setenv(flags.ENV, value)
    assert flags.enabled() is (value.strip().lower() == "on")


def test_off_a_member_turn_declares_what_it_did_and_says_a_torque_unchecked(member, engine):
    engine.model = FakeModel([say("Tighten it to 33 ft-lb. Anything else?")])
    events = _events(_post(member, text="What torque for the track bar?"))
    assert "show_part" not in _declared(engine)
    # The guard is off with the switch: the sentence goes out as it did before this change.
    assert _said(events) == ["Tighten it to 33 ft-lb.", "Anything else?"]


def test_off_the_tool_door_refuses_it_as_unknown(member):
    response = _tool(member, "show_part", {"words": "track bar"})
    assert response.status_code == 400


# =============================================================================
# Switch on
# =============================================================================


def test_on_a_member_is_declared_it_and_nothing_else_changes(member, engine, switch_on):
    _events(_post(member, text="Hello."))
    declared = _declared(engine)
    assert "show_part" in declared
    assert declared - {"show_part", cascade.END_TOOL, cascade.NOT_FOR_ME_TOOL} <= set(
        WEB_TOOL_NAMES
    ) | {"about_kevin"}


def test_on_a_visitor_is_declared_exactly_its_allowlist(visitor, engine, switch_on):
    _events(_post(visitor, text="Hello."))
    assert _declared(engine) == set(RECRUITER_TOOLS) | {cascade.END_TOOL, cascade.NOT_FOR_ME_TOOL}


def test_on_a_visitor_cannot_call_it_and_gets_no_guard(visitor, engine, switch_on):
    engine.model = FakeModel([call("show_part", {"words": "track bar"}), say("It is 33 ft-lb.")])
    events = _events(_post(visitor, text="Where is the track bar?"))
    assert _of(events, "tool")[0]["ok"] is False
    assert "It is 33 ft-lb." in _said(events)
    response = _tool(visitor, "show_part", {"words": "track bar"})
    assert response.status_code == 400


@pytest.mark.django_db
def test_on_mcp_never_lists_it(switch_on):
    assert "show_part" not in {t.name for t in _tools_for_request(None)}


def test_on_the_card_kind_never_reaches_a_recruiter():
    assert cards.for_viewer({"card": "part3d", "title": "x"}, True) is None


@pytest.mark.django_db
def test_on_an_unchecked_nut_is_said_by_code_with_no_number(member, engine, switch_on):
    engine.model = FakeModel(
        [call("show_part", {"words": "track bar frame end"}), say("It is 33 ft-lb.")]
    )
    events = _events(_post(member, text="Where's the track bar nut at the frame?"))
    tool = _of(events, "tool")[0]
    assert tool["ok"] is True and tool["card"]["card"] == "part3d"
    said = _said(events)
    assert said[-1] == specs.NOT_VERIFIED
    assert all(torque_figures(s) == [] for s in said)
    # The model and the page's strip were handed no sentence of code's and no figure; the
    # spoken lines were the turn's whole answer, so the model's own figure never came.
    assert torque_figures(tool["text"]) == []
    assert "It is 33 ft-lb." not in said


@pytest.mark.django_db
def test_on_a_checked_nut_is_said_with_its_figure(member, engine, switch_on):
    FastenerSpec.objects.create(
        model=m.MODEL,
        fastener_id="xj.susp.track_bar@frame_end",
        torque_min=Decimal(12),
        unit="ft-lb",
        applies_to="a made-up truck",
        source="Example book",
        source_page="Page 1",
        verified_on=dt.date(2000, 1, 2),
    )
    engine.model = FakeModel([call("show_part", {"words": "track bar frame end"}), say("Done.")])
    said = _said(_events(_post(member, text="Track bar frame end torque?")))
    assert any(s.startswith("12 foot-pounds, for a made-up truck.") for s in said)


def test_on_a_model_torque_with_no_table_behind_it_is_not_said(member, engine, switch_on):
    engine.model = FakeModel([say("It sits on the frame rail. Tighten it to 33 ft-lb.")])
    said = _said(_events(_post(member, text="Track bar torque?")))
    assert said == ["It sits on the frame rail."]


def test_on_the_tool_door_runs_it_for_a_member(member, switch_on):
    response = _tool(member, "show_part", {"words": "where is the alternator"})
    assert response.status_code == 200, response.data


# =============================================================================
# The guard's own rules
# =============================================================================


@pytest.mark.parametrize(
    "sentence",
    [
        "Tighten it to 33 ft-lb.",
        "Torque them to sixty-one foot-pounds.",
        "It takes 41 to 43 lb-ft.",
        "About 77 N·m.",
        "88 Nm is right.",
        "Use a 13 mm socket.",
        "It's an M7 x 1.0 bolt.",
        "Grab a 5/8 inch socket.",
        "A three-quarter inch wrench.",
        "Twenty two foot pounds.",
        "29 in-lbs on the cover.",
        "The axle nut is 27mm.",
    ],
)
def test_a_figure_with_no_table_behind_it_is_unbacked(sentence):
    assert torque_figures(sentence)
    assert states_unbacked_torque(sentence, set())


@pytest.mark.parametrize(
    "sentence",
    [
        "Your 1998 Cherokee with the 4.0.",
        "A 3 inch lift changes the angle.",
        "That is bolt 2 of 3.",
        "It's on the driver side.",
        "Check the cotter pin.",
    ],
)
def test_ordinary_numbers_are_not_torque(sentence):
    assert torque_figures(sentence) == []
    assert not states_unbacked_torque(sentence, set())


class _Call:
    def __init__(self, name, data, ok=True):
        self.name, self.data, self.ok = name, data, ok


def test_only_show_parts_spoken_figures_back_a_number():
    calls = [_Call("show_part", {"spoken_figures": ["33"]}), _Call("web_search", {"x": "47"})]
    backed = backed_torque(calls, True)
    assert backed == {"33"}
    assert not states_unbacked_torque("It is 33 ft-lb.", backed)
    assert states_unbacked_torque("It is 47 ft-lb.", backed)
    # Number words are never backed: code speaks digits.
    assert states_unbacked_torque("It is thirty-three ft-lb.", backed)


def test_the_guard_off_checks_nothing():
    assert backed_torque([_Call("show_part", {"spoken_figures": ["33"]})], False) is None
    assert not states_unbacked_torque("It is 47 ft-lb.", None)
