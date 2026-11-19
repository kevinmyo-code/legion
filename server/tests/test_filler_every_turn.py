# ruff: noqa: F811 - fixtures imported from the cascade tests
"""A filler on every lookup turn, not only the first question of a conversation (perf/try-faster-
first-word, fix 1; Kevin 2026-11-19 "yes to all fixes"). Measured before: later questions with a
money tool were silent until the answer, 2.7 to 4.7 s on the engine. Fillers are code lines from
memory, said when a lookup tool starts and never naming a result; never two in one turn; none for
a turn with no tool or a silent one (a write). Invented household only."""

from __future__ import annotations

import re

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, fillers
from tests.test_addressed import _warm
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
)

pytestmark = pytest.mark.django_db

ASK = "What did we spend last month?"


def _conversation(member, engine):
    engine.model = FakeModel([say("Morning.")])
    return _of(_events(_post(member, greet="true")), "turn")[0]["conversation_id"]


def _turn(member, engine, conversation, script, heard=ASK):
    engine.speech.heard = heard
    engine.model = FakeModel(script)
    return _events(_post(member, audio=PCM, conversation_id=conversation))


def _filler_tools(events) -> list[str]:
    return [f["tool"] for f in _of(events, "filler")]


def test_turns_one_two_and_three_each_get_one_filler_when_a_lookup_runs(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    lookup = [call("monthly_spend", {}), say("You spent a little this month.")]
    first = _turn(member, engine, conversation, lookup)
    second = _turn(member, engine, conversation, lookup)
    third = _turn(member, engine, conversation, lookup)
    # The first question's "one sec" is its filler: never a second line stacked on it.
    assert _filler_tools(first) == [""]
    assert _filler_tools(second) == ["monthly_spend"]
    assert _filler_tools(third) == ["monthly_spend"]
    for events in (second, third):
        line = _of(events, "filler")[0]["text"]
        assert line in fillers.lines_for("monthly_spend")
        kinds = [k for k, _ in events]
        # Said before the answer's first sentence, from code, with its audio right after it.
        assert kinds.index("filler") < kinds.index("sentence")
        assert kinds[kinds.index("filler") + 1] == "audio"


@pytest.mark.parametrize(
    "script",
    [
        [say("You spent a little this month.")],  # no tool
        [call("create_checklist", {"name": "Party"}), say("Made it.")],  # a silent tool (a write)
    ],
    ids=["no-tool", "silent-tool"],
)
def test_a_later_turn_with_no_lookup_gets_no_filler(
    member, engine, monkeypatch, household_user, script
):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    _turn(member, engine, conversation, [say("First answer.")])
    events = _turn(member, engine, conversation, script)
    assert _filler_tools(events) == []


def test_the_first_turn_never_stacks_a_tool_filler_on_its_acknowledgement(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    events = _turn(
        member, engine, conversation, [call("legion_docs", {"query": "how is it built"}), say("Django.")]
    )
    assert _filler_tools(events) == [""]


def test_every_mapped_lookup_is_announced_and_every_write_stays_silent():
    for tool, (slow, lines) in fillers.FILLERS.items():
        assert slow and lines, tool
    for tool in fillers.SILENT:
        assert not fillers.is_slow(tool), tool


def test_the_timing_line_says_when_the_filler_was_heard(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    _turn(member, engine, conversation, [say("Morning.")], heard="Hi there.")  # too short to ack
    _turn(member, engine, conversation, [say("First answer.")])
    _turn(member, engine, conversation, [call("monthly_spend", {}), say("Some.")])
    lines = [ln for ln in _record(conversation).splitlines() if "cascade_turn status=done" in ln]
    quiet, announced = (dict(re.findall(r"(\w+)=(-?\d+)", ln)) for ln in lines[-2:])
    assert quiet["filler_ms"] == "-1"
    assert int(announced["filler_ms"]) >= 0
    assert announced["filler"] == "1"
