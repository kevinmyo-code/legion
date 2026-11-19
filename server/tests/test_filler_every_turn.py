# ruff: noqa: F811 - fixtures imported from the cascade tests
"""A lookup's line on every turn of a conversation, never holding an answer up (perf/try-faster-
first-word, fix 1 and its rule A; Kevin 2026-11-19). Measured before: later questions were silent
until the answer (2.7 to 4.7 s on the engine); with every lookup announced at its start, a fast
answer waited behind its line (profile: ready 3.56 s, heard 4.90 s). Rule: a network lookup is
announced when it starts; any other lookup only if the answer is still not in hand
`cascade.FILLER_WAIT_S` (0.7 s, the phone's) after it started, and never once an answer sentence is
ready; one line per turn at most. Invented household only."""

from __future__ import annotations

import re
import time

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, fillers
from engine_mcp.tools import SPEAK_VERBATIM, ToolResult
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


class SlowModel(FakeModel):
    """A fake whose rounds may take a while before their first item (seconds, per round)."""

    def __init__(self, rounds, delays):
        super().__init__(rounds)
        self.delays = list(delays)

    def stream(self, req):
        delay = self.delays.pop(0) if self.delays else 0.0
        if delay:
            time.sleep(delay)
        yield from super().stream(req)


def _conversation(member, engine):
    engine.model = FakeModel([say("Morning.")])
    return _of(_events(_post(member, greet="true")), "turn")[0]["conversation_id"]


def _turn(member, engine, conversation, model, heard=ASK):
    engine.speech.heard = heard
    engine.model = model
    return _events(_post(member, audio=PCM, conversation_id=conversation))


def _later(member, engine, monkeypatch, household_user):
    """A conversation whose first question is behind it (its "one sec" used)."""
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    _turn(member, engine, conversation, FakeModel([say("First answer.")]))
    return conversation


def _fillers(events) -> list[str]:
    return [f["tool"] for f in _of(events, "filler")]


def _slow_answer():
    # The lookup returns at once; the answer round says nothing for about a second.
    return SlowModel([call("monthly_spend", {}), say("You spent a little.")], [0.0, 1.0])


def test_a_fast_lookup_gets_no_line_and_no_delay(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, FakeModel([call("monthly_spend", {}), say("Some.")]))
    assert _fillers(events) == []
    assert "waiting" not in [k for k, _ in events]  # never sent to the page


def test_a_slow_answer_gets_its_line_at_about_the_wait(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, _slow_answer())
    assert _fillers(events) == ["monthly_spend"]
    kinds = [k for k, _ in events]
    assert kinds.index("tool") < kinds.index("filler") < kinds.index("sentence")
    assert kinds[kinds.index("filler") + 1] == "audio"
    assert _of(events, "filler")[0]["text"] in fillers.lines_for("monthly_spend")
    line = [ln for ln in _record(conversation).splitlines() if "cascade_turn status=done" in ln][-1]
    assert int(dict(re.findall(r"(\w+)=(-?\d+)", line))["filler_ms"]) >= 650


def test_an_answer_ready_when_the_line_falls_due_drops_the_line(
    member, engine, monkeypatch, household_user
):
    conversation = _later(member, engine, monkeypatch, household_user)
    # No "still nothing" moments: the next moment the turn can act is the answer itself, already
    # past the wait. The line is dropped, never played after the answer is ready.
    monkeypatch.setattr(cascade, "TICK_S", 30.0)
    events = _turn(member, engine, conversation, _slow_answer())
    assert _fillers(events) == []
    assert [s["text"] for s in _of(events, "sentence")] == ["You spent a little."]


def test_a_code_spoken_answer_gets_no_line(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)

    def fake(request, name, args):
        return ToolResult(text="figures", structured={SPEAK_VERBATIM: "Net cash is $1,195.55."})

    monkeypatch.setattr(cascade, "run_tool", fake)
    model = SlowModel([call("account_balances", {}), say("Anything else?")], [0.0, 1.0])
    events = _turn(member, engine, conversation, model)
    assert _fillers(events) == []
    assert _of(events, "sentence")[0]["text"] == "Net cash is $1,195.55."


def test_a_network_lookup_is_announced_when_it_starts(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(
        member, engine, conversation, FakeModel([call("web_search", {"query": "x"}), say("Done.")])
    )
    assert _fillers(events) == ["web_search"]
    kinds = [k for k, _ in events]
    assert kinds.index("working") < kinds.index("filler") < kinds.index("tool")


def test_turns_one_two_and_three_each_get_one_line_when_slow(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    first = _turn(member, engine, conversation, _slow_answer())
    second = _turn(member, engine, conversation, _slow_answer())
    third = _turn(member, engine, conversation, _slow_answer())
    # The first question's "one sec" is its line: never a second one stacked on it.
    assert _fillers(first) == [""]
    assert _fillers(second) == ["monthly_spend"]
    assert _fillers(third) == ["monthly_spend"]


def test_never_two_lines_in_a_turn(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    model = SlowModel([call("web_search", {"query": "x"}), say("Done.")], [0.0, 1.0])
    events = _turn(member, engine, conversation, model)
    assert _fillers(events) == ["web_search"]


@pytest.mark.parametrize(
    "script",
    [
        [say("You spent a little this month.")],  # no tool
        [call("create_checklist", {"name": "Party"}), say("Made it.")],  # a write: silent
    ],
    ids=["no-tool", "write"],
)
def test_no_lookup_no_line(member, engine, monkeypatch, household_user, script):
    conversation = _later(member, engine, monkeypatch, household_user)
    delays = [1.0] * len(script)
    events = _turn(member, engine, conversation, SlowModel(script, delays))
    assert _fillers(events) == []


def test_every_lookup_is_mapped_and_every_write_is_silent():
    assert fillers.is_slow("web_search") and not fillers.is_slow("monthly_spend")
    for tool, (_slow, lines) in fillers.FILLERS.items():
        assert lines, tool
    for tool in fillers.SILENT:
        assert fillers.lines_for(tool) == (), tool


def test_the_timing_line_says_no_line_was_heard(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    _turn(member, engine, conversation, FakeModel([call("monthly_spend", {}), say("Some.")]))
    line = [ln for ln in _record(conversation).splitlines() if "cascade_turn status=done" in ln][-1]
    assert dict(re.findall(r"(\w+)=(-?\d+)", line))["filler_ms"] == "-1"
