"""Engine-brain slice E3, inventory row 13: the phone's `RelativeDay` on the agent turn
(`agents/legion/day_words.py`). The cases mirror `app/.../tools/day/RelativeDayTest.kt`; the
phone's `manage_item` cases stay on the phone (the engine has no twin of it), and stand here as
"not a date tool"."""

from __future__ import annotations

import datetime as dt
from datetime import date, time

import pytest

from agents.evals import fixture
from agents.evals.runner import member_request
from agents.legion import day_words as d
from agents.legion.turn import open_conversation, run_turn
from agents.runtime import model as m

SUNDAY = date(2026, 11, 15)
TUE = date(2026, 11, 17)
EVENING = time(21, 0)


def test_words_and_iso_dates_resolve_anything_else_is_refused():
    assert d.parse("today", SUNDAY) == SUNDAY
    assert d.parse(" Tomorrow ", SUNDAY) == date(2026, 11, 16)
    assert d.parse("yesterday", SUNDAY) == date(2026, 11, 14)
    assert d.parse("tonight", SUNDAY) == SUNDAY
    assert d.parse("2026-11-20", SUNDAY) == date(2026, 11, 20)
    assert d.parse("next week", SUNDAY) is None
    assert d.parse("", SUNDAY) is None


def test_at_23_30_local_already_the_next_day_in_utc_tomorrow_is_the_local_tomorrow():
    west = dt.timezone(dt.timedelta(hours=-6))
    now = dt.datetime(2026, 11, 15, 23, 30, tzinfo=west)
    assert now.astimezone(dt.UTC).date() == date(2026, 11, 16)
    assert d.parse("tomorrow", now.date()) == date(2026, 11, 16)
    after_midnight = dt.datetime(2026, 11, 16, 0, 30, tzinfo=west).date()
    assert d.parse("tomorrow", after_midnight) == date(2026, 11, 17)


def test_a_resolved_day_is_said_back_with_its_weekday():
    assert d.label(date(2026, 11, 16)) == "Monday 2026-11-16"


def test_the_one_day_word_he_said_is_the_day_he_meant():
    assert d.said_day("and tomorrow?", TUE) == TUE + dt.timedelta(days=1)
    assert d.said_day("What about tomorrow", TUE) == TUE + dt.timedelta(days=1)
    assert d.said_day("what do I have due today", TUE) == TUE
    assert d.said_day("anything tonight?", TUE) == TUE


@pytest.mark.parametrize(
    "words",
    [
        "the day after tomorrow", "tomorrow and the next day", "tomorrow or Friday", "this week",
        "tomorrow, the 19th", "what's due next Tuesday", "today and tomorrow", "", None,
        "tomorrow and Thanksgiving", "not tomorrow, the twenty first", "in two days, not tomorrow",
        "a fortnight from today", "move the dentist to tomorrow", "what did I miss yesterday",
    ],
)
def test_anything_more_than_one_plain_day_word_is_left_to_the_model(words):
    assert d.said_day(words, TUE) is None


def _fix(args, words="and tomorrow?", tool="read_calendar", day=TUE, at=EVENING, only=True):
    return d.corrected(tool, args, words, day, at, only)


def test_a_single_date_one_day_off_the_day_he_said_becomes_that_day_with_a_note():
    tomorrow = (TUE + dt.timedelta(days=1)).isoformat()
    off = (TUE + dt.timedelta(days=2)).isoformat()
    fixed, note = _fix({"from": off, "to": off})
    assert fixed == {"from": tomorrow, "to": tomorrow}
    assert tomorrow in note
    # Kept: the right day, two days off, another day named.
    assert _fix({"from": tomorrow, "to": tomorrow}) is None
    three = (TUE + dt.timedelta(days=3)).isoformat()
    assert _fix({"from": three, "to": three}) is None
    assert _fix({"from": off, "to": off}, "what about Thursday") is None


def test_never_a_range_one_side_of_one_another_tool_or_a_second_date_call():
    off = (TUE + dt.timedelta(days=2)).isoformat()
    assert _fix({"from": TUE.isoformat(), "to": (TUE + dt.timedelta(days=6)).isoformat()}) is None
    assert _fix({"from": off}) is None
    assert _fix({"to": off}) is None
    assert _fix({"date": off, "from": off, "to": off}) is None
    assert _fix({"date": off}, tool="log_sleep") is None
    # The phone's manage_item cases: the engine has no twin, so not a date tool here.
    assert _fix({"date": off}, "remind me tomorrow", tool="manage_item") is None
    assert _fix({"from": off, "to": off}, only=False) is None
    assert d.is_date_call("read_calendar", {"from": off})
    assert not d.is_date_call("read_calendar", {"item": "x"})


def test_the_day_is_the_turns_so_a_call_after_midnight_keeps_a_right_tomorrow():
    tomorrow = (TUE + dt.timedelta(days=1)).isoformat()
    late = time(23, 59, 58)
    assert _fix({"from": tomorrow, "to": tomorrow}, "what's on tomorrow", at=late) is None
    early = time(0, 0, 2)
    fixed, _ = _fix(
        {"from": tomorrow, "to": tomorrow}, "what's on tomorrow", day=TUE + dt.timedelta(days=1),
        at=early,
    )
    assert fixed["from"] == (TUE + dt.timedelta(days=2)).isoformat()


def test_tonight_in_the_small_hours_is_left_to_the_model():
    half_past_midnight = time(0, 30)
    nxt = TUE + dt.timedelta(days=1)
    assert _fix({"from": TUE.isoformat(), "to": TUE.isoformat()}, "what's on tonight", day=nxt,
                at=half_past_midnight) is None
    assert d.said_day("what's on tonight", TUE, half_past_midnight) is None
    assert d.said_day("what's on tonight", TUE, EVENING) == TUE


def test_day_words_in_args_resolve_on_the_turns_day():
    assert d.resolved({"from": "tonight", "to": "tomorrow", "x": "today"}, TUE) == {
        "from": TUE.isoformat(),
        "to": (TUE + dt.timedelta(days=1)).isoformat(),
        "x": "today",
    }


# ---- through the agent turn ------------------------------------------------------------------

# 21:00 in Chicago on Tuesday 2026-03-10 is 03:00 UTC on Wednesday the 11th.
EVENING_UTC = dt.datetime(2026, 3, 11, 3, 0, tzinfo=dt.UTC)


@pytest.fixture
def seeded(household_user, household_a, monkeypatch):
    monkeypatch.setattr("engine_mcp.tools._now", lambda: EVENING_UTC)
    fixture.seed(household_a, household_user, date(2026, 3, 10))
    return household_user


def _turn(user, text, model, facts=None):
    seen = []
    request = member_request(user)
    conversation = open_conversation(request, None, "test")
    gen = run_turn(
        request, text, model, conversation=conversation, now=EVENING_UTC,
        observe=seen.append, client_facts=facts,
    )
    for _ in gen:
        pass
    return seen


@pytest.mark.django_db
def test_an_evening_and_tomorrow_read_two_days_out_is_put_back_to_his_tomorrow(seeded):
    model = m.FakeModel(
        [m.call("read_calendar", {"from": "2026-03-12", "to": "2026-03-12"}), m.say("Clear.")]
    )
    seen = _turn(seeded, "and tomorrow?", model)
    assert seen[0]["args"] == {"from": "2026-03-11", "to": "2026-03-11"}
    response = model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert response["message"].endswith(
        "Used Wednesday 2026-03-11, the day he said, not the date asked for."
    )


@pytest.mark.django_db
def test_tomorrow_as_a_word_resolves_on_the_devices_day_not_the_households(seeded):
    # The device says it is at UTC+8, where it is already Wednesday 11:00: its tomorrow is the 12th.
    model = m.FakeModel(
        [m.call("read_calendar", {"from": "tomorrow", "to": "tomorrow"}), m.say("Clear.")]
    )
    seen = _turn(seeded, "what's on tomorrow", model, facts={"utc_offset_minutes": 480})
    assert seen[0]["args"] == {"from": "2026-03-12", "to": "2026-03-12"}
