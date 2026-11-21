"""The garage switch at every door, and the torque guard. Every figure here is MADE UP.

With `LEGION_JEEP_ASSISTANT` unset every door behaves as it did: `show_part` is in no list, no
declaration and no prompt, a call to it is refused as unknown, and no sentence is checked for a
torque. On, the engine household's OWNER gets it on the web cascade, and the guard with it;
another member, another household, a recruiter, Live, `/mcp` and the head unit never do.
"""

from __future__ import annotations

import datetime as dt
from decimal import Decimal
from types import SimpleNamespace

import pytest
from django.core.cache import cache

from agents.legion.speech_rules import (
    TorqueGuard,
    backed_torque,
    states_unbacked_torque,
    torque_figures,
)
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
from garage import flags, specs, topic
from garage import xj_front as m
from garage.models import FastenerSpec, GarageTruck
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
def owner(household_user):
    """`household_user` is the bootstrap (engine) household's owner (`tests/conftest.py`)."""
    return household_user


@pytest.fixture
def member(owner):
    return browser(owner)


@pytest.fixture
def plain_member(household_a):
    user = User.objects.create_user(email="other-member@example.com", password="x-y-z-w-v")
    HouseholdMember.objects.create(user=user, household=household_a, role=HouseholdMember.MEMBER)
    return user


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


def _checked_row(**extra):
    GarageTruck.objects.create(
        model=m.MODEL, year=2000, engine="9.9", axle="Example axle", lift_in=Decimal(0)
    )
    got = {
        "fastener_id": "xj.susp.track_bar@frame_end",
        "torque_min": Decimal(12),
        "unit": "ft-lb",
        "year_from": 1999,
        "year_to": 2001,
        "engine": "any",
        "axle": "any",
        "hardware": "stock",
        "source_kind": "fsm",
        "source": "Example book",
        "source_page": "Page 1",
        "verified_on": dt.date(2000, 1, 2),
        **extra,
    }
    return FastenerSpec.objects.create(model=m.MODEL, **got)


# =============================================================================
# Switch off: exactly as before
# =============================================================================


def test_off_the_tool_is_in_no_list(owner, visitor_user):
    assert not flags.enabled()
    assert "show_part" not in TOOLS_BY_NAME
    assert "show_part" not in WEB_TOOL_NAMES and "show_part" not in RECRUITER_TOOLS
    assert member_only_tools(owner) == {}
    assert [t.name for t in tools_for(owner)] == list(WEB_TOOL_NAMES)
    assert off_registry_tool("show_part", owner) is None
    assert refusal_for("show_part", owner) is not None
    assert refusal_for("show_part", visitor_user) is not None


@pytest.mark.parametrize("value", ["", "off", "1", "true", "ON "])
def test_only_the_word_on_turns_it_on(monkeypatch, value):
    monkeypatch.setenv(flags.ENV, value)
    assert flags.enabled() is (value.strip().lower() == "on")


def test_off_a_turn_declares_what_it_did_and_says_a_torque_unchecked(member, engine):
    engine.model = FakeModel([say("Tighten it to 33 ft-lb. Anything else?")])
    events = _events(_post(member, text="What torque for the track bar?"))
    assert "show_part" not in _declared(engine)
    # The guard is off with the switch: the sentence goes out as it did before this change.
    assert _said(events) == ["Tighten it to 33 ft-lb.", "Anything else?"]


def test_off_the_tool_door_refuses_it_as_unknown(member):
    response = _tool(member, "show_part", {"words": "track bar"})
    assert response.status_code == 400


# =============================================================================
# Switch on: the owner only
# =============================================================================


def test_on_the_owner_is_declared_it_and_nothing_else_changes(member, engine, switch_on):
    _events(_post(member, text="Hello."))
    declared = _declared(engine)
    assert "show_part" in declared
    assert declared - {"show_part", cascade.END_TOOL, cascade.NOT_FOR_ME_TOOL} <= set(
        WEB_TOOL_NAMES
    )


def test_on_another_member_of_his_household_does_not_get_it(plain_member, engine, switch_on):
    assert member_only_tools(plain_member) == {}
    assert refusal_for("show_part", plain_member) is not None
    client = browser(plain_member)
    engine.model = FakeModel([say("Tighten it to 33 ft-lb.")])
    events = _events(_post(client, text="Track bar torque?"))
    assert "show_part" not in _declared(engine)
    # No tool for him, so no guard either: his turn is as before.
    assert _said(events) == ["Tighten it to 33 ft-lb."]
    assert _tool(client, "show_part", {"words": "track bar"}).status_code == 400


def test_on_another_households_owner_does_not_get_it(user_b, switch_on):
    assert member_only_tools(user_b) == {}
    assert refusal_for("show_part", user_b) is not None


def test_on_a_visitor_is_declared_exactly_its_allowlist(visitor, engine, switch_on):
    _events(_post(visitor, text="Hello."))
    assert _declared(engine) == set(RECRUITER_TOOLS) | {cascade.END_TOOL, cascade.NOT_FOR_ME_TOOL}


def test_on_a_visitor_cannot_call_it_and_gets_no_guard(visitor, engine, switch_on):
    engine.model = FakeModel([call("show_part", {"words": "track bar"}), say("It is 33 ft-lb.")])
    events = _events(_post(visitor, text="Where is the track bar?"))
    assert _of(events, "tool")[0]["ok"] is False
    assert "It is 33 ft-lb." in _said(events)
    assert _tool(visitor, "show_part", {"words": "track bar"}).status_code == 400


@pytest.mark.django_db
def test_on_mcp_never_lists_it(switch_on):
    assert "show_part" not in {t.name for t in _tools_for_request(None)}


def test_on_the_card_kind_never_reaches_a_recruiter():
    assert cards.for_viewer({"card": "part3d", "title": "x"}, True) is None


def test_on_the_live_fallback_does_not_get_it(owner, monkeypatch, switch_on):
    # Live speaks no code lines and runs no guard: the tool stays off there.
    monkeypatch.setenv("LEGION_WEB_VOICE", "live")
    assert member_only_tools(owner) == {}
    assert refusal_for("show_part", owner) is not None


def test_on_the_agents_door_refuses_it_outside_a_spec(owner, switch_on):
    from agents.legion.guards import check_call
    from garage.tool import SHOW_PART

    verdict = check_call(
        None, SimpleNamespace(tools=frozenset()), "show_part", {}, SHOW_PART, owner
    )
    assert verdict.allow is False and verdict.guard == "not_allowed"


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


def test_on_a_checked_nut_is_said_with_its_figure(member, engine, switch_on):
    _checked_row()
    engine.model = FakeModel([call("show_part", {"words": "track bar frame end"}), say("Done.")])
    said = _said(_events(_post(member, text="Track bar frame end torque?")))
    assert any(s.startswith("12 foot-pounds. That's for your 2000") for s in said)


def test_on_a_model_torque_with_no_table_behind_it_is_not_said(member, engine, switch_on):
    engine.model = FakeModel([say("It sits on the frame rail. Tighten it to 33 ft-lb.")])
    said = _said(_events(_post(member, text="Track bar torque?")))
    assert said == ["It sits on the frame rail."]


def test_on_a_figure_repeated_from_the_history_is_not_said(member, engine, switch_on):
    # The blind review's case: code spoke a checked figure last turn; this turn ran no tool, and
    # the model repeats or moves it ("the other side is also ..."). Not backed THIS turn: dropped.
    _checked_row()
    engine.model = FakeModel([call("show_part", {"words": "track bar frame end"}), say("Done.")])
    first = _events(_post(member, text="Track bar frame end torque?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("The other side is also 12. Anything else?")])
    second = _events(_post(member, text="And the other side?", conversation_id=conversation))
    assert _said(second) == ["Anything else?"]


@pytest.mark.parametrize("asked", ["how tight is the hub?", "what about the shock?"])
def test_on_a_common_words_torque_question_answered_without_the_tool_gets_no_number(
    member, engine, switch_on, asked
):
    # The re-check's case: no tool ran, the model answers from memory. His words mark the turn.
    engine.model = FakeModel([say("About 175, give or take. Anything else?")])
    said = _said(_events(_post(member, text=asked)))
    assert said == ["Anything else?"]


def test_on_a_new_topic_after_a_front_end_reply_keeps_its_numbers(member, engine, switch_on):
    engine.model = FakeModel([call("show_part", {"words": "track bar frame end"}), say("Done.")])
    first = _events(_post(member, text="Where's the track bar nut at the frame?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("It's 3:15.")])
    second = _events(_post(member, text="What time is it?", conversation_id=conversation))
    assert _said(second) == ["It's 3:15."]


def test_on_the_steering_box_question_code_asks_which_bolt_in_words(member, engine, switch_on):
    engine.model = FakeModel(
        [call("show_part", {"words": "steering box bolt torque"}), say("Bolt 2 is 33 ft-lb.")]
    )
    said = _said(_events(_post(member, text="What's the torque on the steering box bolt?")))
    assert len(said) == 1 and said[0].startswith("Which one do you mean:")
    assert "frame bolt two of three" in said[0]
    assert not any(ch.isdigit() for ch in said[0])


def test_on_the_tool_door_runs_it_for_the_owner(member, switch_on):
    response = _tool(member, "show_part", {"words": "where is the alternator"})
    assert response.status_code == 200, response.data


# =============================================================================
# The guard's own rules
# =============================================================================

OFF_TOPIC = TorqueGuard(frozenset(), topic.SENTENCE, whole_turn=False)
FRONT_END_TURN = TorqueGuard(frozenset(), topic.SENTENCE, whole_turn=True)


@pytest.mark.parametrize(
    "sentence",
    [
        # The blind review's phrasings.
        "Run the track bar bolt up to 95 lbs.",
        "Snug the pitman nut to about ninety.",
        "The pitman arm nut takes 185.",
        "It's 185 pounds on that one.",
        # Units in every form, words, ranges.
        "Tighten it to 33 ft-lb.",
        "Torque them to sixty-one foot-pounds.",
        "It takes 41 to 43 lb-ft.",
        "Set the drag link between 40 and 45.",
        "The tie rod end goes to one eighty-five.",
        "About 77 N·m.",
        "About 77 N⋅m.",
        "88 Nm is right.",
        "Use a 13 mm socket.",
        "It's an M7 x 1.0 bolt.",
        "Grab a 5/8 inch socket.",
        "Use a half-inch socket.",
        "Grab the three-eighths wrench.",
        "A three-quarter inch wrench.",
        "Twenty two foot pounds.",
        "29 in-lbs on the cover.",
        "The axle nut is 27mm.",
        "Do 33 foot-lbs.",
        "Do 33 ft-pounds.",
        "Do 33 ft·lb.",
        "Do 33 lb·ft.",
        "Do 33 lbf ft.",
        "Use 33 ft/lbs.",
        "Give it 33 pounds of torque.",
        "Torque it to 33 lbs.",
        "Torque it to 33.",
    ],
)
def test_a_figure_about_front_end_hardware_is_dropped_in_any_turn(sentence):
    assert states_unbacked_torque(sentence, OFF_TOPIC)


@pytest.mark.parametrize(
    "sentence",
    [
        # The blind review's phrasings that name nothing: dropped when the turn is about the
        # front end (his words, the reply before, or show_part ran).
        "Did you mean the 185 one?",
        "Grab a 19 for that.",
        "The other side is also 185.",
        "A three quarter inch on it.",
        "Same as before, 60.",
    ],
)
def test_any_number_is_dropped_in_a_front_end_turn(sentence):
    assert states_unbacked_torque(sentence, FRONT_END_TURN)


@pytest.mark.parametrize(
    "sentence",
    [
        # No front-end word, no torque or size unit, and the turn is not about the truck. Each is
        # safe because nothing in it can be read as a fastener figure.
        "Your 1998 Cherokee with the 4.0.",
        "That is 2 of 3 on your list.",
        "We got 12 mm of rain.",
        "There is 1/2 inch of snow.",
        "Take the M25 north.",
        "The trip is 30 feet.",
        "It's 72 degrees today.",
        "Your car payment of $340 is due on the 3rd.",
        "You've driven 1,200 miles this month.",
        "Meeting at 3 pm on November 21.",
        "Which one do you mean?",
    ],
)
def test_ordinary_numbers_are_said(sentence):
    assert not states_unbacked_torque(sentence, OFF_TOPIC)


def test_which_one_is_said_even_in_a_front_end_turn():
    # "one" alone is a pronoun; the model must be able to ask which one.
    assert not states_unbacked_torque("Which one do you mean, driver or passenger?", FRONT_END_TURN)


@pytest.mark.parametrize(
    "words",
    [
        # The re-check's phrasings: common part words and "tight" mark the turn.
        "how tight is the hub?",
        "what about the shock?",
        "how much on the axle?",
        "what do I crank the frame bolt to?",
        "snug it to what?",
        "what torque for the pitman nut again?",
    ],
)
def test_his_words_about_hardware_make_a_front_end_turn(words):
    assert topic.about_front_end(words, "")


REPLY = "Track bar, frame end, driver side. The torque for it is not verified yet."


@pytest.mark.parametrize(
    "words",
    ["what was that again?", "and the other side?", "same for this one?", "the second one"],
)
def test_a_bare_follow_up_to_a_front_end_reply_stays_a_front_end_turn(words):
    assert topic.about_front_end(words, REPLY)


@pytest.mark.parametrize(
    "words", ["what time is it?", "what's the weather tomorrow?", "remind me at 5 to call mum"]
)
def test_a_new_topic_after_a_front_end_reply_ends_the_carry_over(words):
    assert not topic.about_front_end(words, REPLY)
    assert not topic.about_front_end(words, "")


class _Call:
    def __init__(self, name, data, ok=True):
        self.name, self.data, self.ok = name, data, ok


def test_only_show_parts_spoken_figures_back_a_number():
    calls = [_Call("show_part", {"spoken_figures": ["33"]}), _Call("web_search", {"x": "47"})]
    guard = backed_torque(calls, topic.SENTENCE, about_front_end=False)
    assert guard.numbers == {"33"} and guard.whole_turn is True
    assert not states_unbacked_torque("It is 33 ft-lb.", guard)
    assert states_unbacked_torque("It is 47 ft-lb.", guard)
    # Number words are never backed: code speaks digits.
    assert states_unbacked_torque("It is thirty-three ft-lb.", guard)


def test_the_guard_off_checks_nothing():
    calls = [_Call("show_part", {"spoken_figures": ["33"]})]
    assert backed_torque(calls, None, about_front_end=True) is None
    assert not states_unbacked_torque("It is 47 ft-lb.", None)
