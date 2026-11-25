# ruff: noqa: F811 - fixtures imported from the cascade tests
"""A lookup's line on every turn of a conversation, never holding an answer up (perf/try-faster-
first-word, fix 1 and its rule A; Kevin 2026-11-19). Measured before: later questions were silent
until the answer (2.7 to 4.7 s on the engine); with every lookup announced at its start, a fast
answer waited behind its line (profile: ready 3.56 s, heard 4.90 s). Rule: a network lookup is
announced when it starts; any other lookup only if the answer is still not in hand
`cascade.FILLER_WAIT_S` (0.7 s, the phone's) after it started, and never once an answer sentence is
ready; one line per turn at most. Invented household only."""

from __future__ import annotations

import base64
import re
import time

import pytest

from agents.runtime import model as m
from agents.runtime.model import FakeModel, call, say
from agents.voice import wait_lines
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
    """A conversation with one question behind it. Since 2026-11-24 every turn of theirs gets its
    own acknowledgement (`fillers.ack_skip`, the phone's rule), so this no longer "uses up" a
    line; it is kept because a later turn is the case these tests are about."""
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    _turn(member, engine, conversation, FakeModel([say("First answer.")]))
    return conversation


def _fillers(events) -> list[str]:
    """This turn's TASK lines, by tool: the lines that name work a tool started. The
    acknowledgement and the chained wait lines carry no tool and have their own helpers."""
    return [f["tool"] for f in _of(events, "filler") if f["tool"]]


def _acks(events) -> list[str]:
    return [f["text"] for f in _of(events, "filler") if not f["tool"] and not f.get("wait")]


def _waits(events) -> list[str]:
    return [f["text"] for f in _of(events, "filler") if f.get("wait")]


def _slow_answer():
    # The lookup returns at once; the answer round says nothing for about a second.
    return SlowModel([call("monthly_spend", {}), say("You spent a little.")], [0.0, 1.0])


def test_a_fast_lookup_gets_no_line_and_no_delay(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(
        member, engine, conversation, FakeModel([call("monthly_spend", {}), say("Some.")])
    )
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


def test_turns_one_two_and_three_each_get_a_line_and_an_acknowledgement(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    turns = [_turn(member, engine, conversation, _slow_answer()) for _ in range(3)]
    # Before 2026-11-24 only the first turn said anything of its own ("one sec") and the lookup's
    # line was never stacked on it, so turn one had no task line at all.
    for events in turns:
        assert len(_acks(events)) == 1, _acks(events)
        assert _fillers(events) == ["monthly_spend"]


def test_the_acknowledgement_and_the_task_line_are_never_back_to_back(
    member, engine, monkeypatch, household_user
):
    conversation = _later(member, engine, monkeypatch, household_user)
    model = SlowModel([call("web_search", {"query": "x"}), say("Done.")], [0.0, 1.0])
    events = _turn(member, engine, conversation, model)
    # The search's line is announced at its start, right behind the acknowledgement, so it carries
    # the spacing it still owed as silence in front of its own clip (`wait_lines.lead_ms`).
    assert _fillers(events) == ["web_search"] and len(_acks(events)) == 1
    clips = [len(base64.b64decode(a["pcm"])) for a in _of(events, "audio")]
    assert clips[1] > clips[0], clips


def test_only_one_task_line_a_turn_however_many_tools_run(
    member, engine, monkeypatch, household_user
):
    conversation = _later(member, engine, monkeypatch, household_user)
    model = SlowModel(
        [call("web_search", {"query": "x"}), call("monthly_spend", {}), say("Done.")],
        [0.0, 0.0, 1.0],
    )
    assert _fillers(_turn(member, engine, conversation, model)) == ["web_search"]


@pytest.mark.parametrize(
    "script",
    [
        [say("You spent a little this month.")],  # no tool
        [call("create_checklist", {"name": "Party"}), say("Made it.")],  # a write: silent
    ],
    ids=["no-tool", "write"],
)
def test_no_lookup_no_task_line(member, engine, monkeypatch, household_user, script):
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


# ---- review 2026-11-19 (review-try-first-word-1119.md) -------------------------------------------


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        ("legion_docs", {"query": "how is it built"}),
        ("brief_latest", {}),
        ("run_procedure", {"name": "xx"}),
    ],
)
def test_a_quick_local_lookup_on_the_doors_slow_list_is_not_announced_at_its_start(
    member, engine, monkeypatch, household_user, tool, args
):
    # F1: the door's wider slow list (SLOW_TOOLS) still makes `working`; the line waits for 0.7 s.
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, FakeModel([call(tool, args), say("Here it is.")]))
    assert _fillers(events) == []


def test_a_slow_answer_after_the_docs_gets_its_line_after_the_tool(
    member, engine, monkeypatch, household_user
):
    conversation = _later(member, engine, monkeypatch, household_user)
    # The docs' own answer, so the lookup succeeds whatever corpus this test database holds.
    monkeypatch.setattr(
        cascade, "run_tool", lambda request, name, args: ToolResult(text="ADR 0102.")
    )
    model = SlowModel([call("legion_docs", {"query": "how"}), say("It is Django.")], [0.0, 1.0])
    events = _turn(member, engine, conversation, model)
    kinds = [k for k, _ in events]
    assert _fillers(events) == ["legion_docs"]
    assert kinds.index("tool") < kinds.index("filler") < kinds.index("sentence")


def test_a_delayed_line_is_the_shortest_clip_in_hand():
    # F3: a delayed line may still be playing when the answer is ready: the shortest one.
    audio = cascade.FillerAudio(spawn=lambda work: None)
    long_line, short_line, mid_line = fillers.lines_for("monthly_spend")
    audio._audio = {
        ("v", long_line): b"\x00" * 9000,
        ("v", short_line): b"\x00" * 3000,
        ("v", mid_line): b"\x00" * 6000,
    }
    picker = fillers.LinePicker()
    cascade.LINES, saved = picker, cascade.LINES
    try:
        assert audio.pick("v", "monthly_spend", shortest=True)[0] == short_line
        # Never the same line twice running: the next shortest.
        assert audio.pick("v", "monthly_spend", shortest=True)[0] == mid_line
    finally:
        cascade.LINES = saved


def test_a_cancel_during_a_silent_round_sends_no_line(member, engine, monkeypatch, household_user):
    # F6: the stop lands while the answer round is silent; at 0.7 s nothing is said.
    from tests.test_cancel_paid_calls import _Flag

    conversation = _later(member, engine, monkeypatch, household_user)
    flag = _Flag()
    monkeypatch.setattr(cascade, "CancelCheck", lambda *a, **k: flag)

    class CancelledWhileSilent(SlowModel):
        def stream(self, req):
            if len(self.requests) == 1:
                flag.hit = True
            yield from super().stream(req)

    model = CancelledWhileSilent([call("monthly_spend", {}), say("Some.")], [0.0, 1.0])
    events = _turn(member, engine, conversation, model)
    assert _fillers(events) == []


def test_a_closed_round_stops_reading_the_model_at_its_next_item():
    # F5: the reader thread stops at the next item after the turn lets go, and closes the stream.
    closed = []

    class Slow:
        def stream(self, req):
            try:
                for n in range(50):
                    time.sleep(0.02)
                    yield m.TextDelta(str(n))
            finally:
                closed.append(True)

    stream = cascade.Ticking(Slow(), every=0.01).stream(None)
    first = next(item for item in stream if isinstance(item, m.TextDelta))
    assert first.text == "0"
    stream.close()
    deadline = time.monotonic() + 1.0
    while not closed and time.monotonic() < deadline:
        time.sleep(0.01)
    assert closed == [True]


def test_the_answers_first_audio_is_not_the_lines(member, engine, monkeypatch, household_user):
    # F8: `tts_first_audio_ms` stays the ANSWER's first audio; `first_sound_ms` is any first sound.
    conversation = _later(member, engine, monkeypatch, household_user)
    _turn(member, engine, conversation, _slow_answer())
    line = [ln for ln in _record(conversation).splitlines() if "cascade_turn status=done" in ln][-1]
    t = {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)", line)}
    assert 0 <= t["filler_ms"] <= t["first_sound_ms"] + 5
    assert t["tts_first_audio_ms"] >= t["first_sentence_ms"] > t["filler_ms"]


# ---- review round 2 ------------------------------------------------------------------------------


def _clip(ms: int) -> bytes:
    return b"\x00" * (ms * 48)  # 24 kHz, 16-bit


@pytest.mark.parametrize("family_ms", [900, 1400, 1700, 2600, 4800])
@pytest.mark.parametrize("generic_ms", [1040, 1600])
def test_no_delayed_line_is_longer_than_the_ceiling_for_any_family(family_ms, generic_ms):
    ceiling = cascade.DELAYED_LINE_MAX_MS
    for tool, (_slow, lines) in fillers.FILLERS.items():
        audio = cascade.FillerAudio(spawn=lambda work: None)
        audio._audio = {("v", line): _clip(family_ms + 10 * i) for i, line in enumerate(lines)}
        audio._audio.update({("v", line): _clip(generic_ms) for line in fillers.FALLBACK})
        saved, cascade.LINES = cascade.LINES, fillers.LinePicker()
        try:
            picked = audio.pick("v", tool, shortest=True, ceiling_ms=ceiling)
        finally:
            cascade.LINES = saved
        if family_ms <= ceiling:
            assert picked is not None and picked[0] in lines, tool
        elif generic_ms <= ceiling:
            assert picked is not None and picked[0] in fillers.FALLBACK, tool
        else:
            assert picked is None, tool
        if picked is not None:
            assert len(picked[1]) // 48 <= ceiling, tool


def test_a_due_line_with_no_clip_asks_about_a_cancel_at_the_doors_rate(
    member, engine, monkeypatch, household_user
):
    # N1: a line due but not said (no clip in hand) must not ask the database on every tick.
    conversation = _later(member, engine, monkeypatch, household_user)
    monkeypatch.setattr(cascade, "FILLER_AUDIO", cascade.FillerAudio(spawn=lambda work: None))

    class Counting:
        direct = 0
        throttled = 0

        def __call__(self):
            Counting.direct += 1
            return False

        def often(self):
            Counting.throttled += 1
            return False

    monkeypatch.setattr(cascade, "CancelCheck", lambda *a, **k: Counting())
    # The same turn with no silent wait: how often the turn itself asks directly.
    _turn(member, engine, conversation, FakeModel([call("monthly_spend", {}), say("Some.")]))
    quick = Counting.direct
    Counting.direct = 0
    model = SlowModel([call("monthly_spend", {}), say("Some.")], [0.0, 1.5])
    events = _turn(member, engine, conversation, model)
    assert _fillers(events) == []
    # About 8 ticks past the 0.7 s wait, none of them a direct (database) question.
    assert Counting.direct <= quick


# ---- the chained wait lines (Kevin, 2026-11-24: the same voice behavior as the phone) ----------
#
# Before this, a later question answered with no tool was SILENT for the whole model round, and a
# turn with a tool heard exactly one line however long the wait ran. The phone has played an
# acknowledgement at end of turn and then a chain of holding lines since 2026-11-20/21
# (`WaitFillers`), which is what Kevin asked /try to match. The GAPS are the phone's, pinned by
# `test_assistant_wait_lines.py`; these tests shorten them so a case runs in under a second.


@pytest.fixture
def quick_chain(monkeypatch):
    """The phone's chain with its gaps shortened: the rule under test is the chaining, not the
    numbers (those are pinned against `FillerTiming.kt` in `test_assistant_wait_lines.py`)."""
    monkeypatch.setattr(wait_lines, "FIRST_GAP_MS", 100)
    monkeypatch.setattr(wait_lines, "NEXT_GAP_MS", 100)


def test_a_no_tool_turn_is_no_longer_silent_while_the_model_thinks(
    member, engine, monkeypatch, household_user, quick_chain
):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, SlowModel([say("You spent a little.")], [1.0]))
    assert _fillers(events) == []  # no tool ran, so no task line
    assert len(_waits(events)) >= 2, _waits(events)
    assert set(_waits(events)) <= set(wait_lines.LINES)
    assert [s["text"] for s in _of(events, "sentence")] == ["You spent a little."]


def test_no_wait_line_is_said_twice_in_a_turn(
    member, engine, monkeypatch, household_user, quick_chain
):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, SlowModel([say("Done.")], [2.0]))
    said = _waits(events)
    assert len(said) == len(set(said)), said


def test_the_chain_never_passes_six_fillers_of_all_kinds(
    member, engine, monkeypatch, household_user, quick_chain
):
    conversation = _later(member, engine, monkeypatch, household_user)
    # Long enough that the gaps alone would allow far more than the cap.
    events = _turn(member, engine, conversation, SlowModel([say("Done.")], [3.0]))
    lines = _of(events, "filler")
    assert len(lines) <= wait_lines.MAX_PER_TURN, [f["text"] for f in lines]


def test_a_wait_line_counts_the_task_line_against_the_cap(
    member, engine, monkeypatch, household_user, quick_chain
):
    conversation = _later(member, engine, monkeypatch, household_user)
    model = SlowModel([call("web_search", {"query": "x"}), say("Done.")], [0.0, 3.0])
    events = _turn(member, engine, conversation, model)
    assert _fillers(events) == ["web_search"]
    assert len(_of(events, "filler")) <= wait_lines.MAX_PER_TURN


def test_the_chain_stops_once_the_answer_is_ready(
    member, engine, monkeypatch, household_user, quick_chain
):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, SlowModel([say("You spent a little.")], [0.5]))
    kinds = [k for k, _ in events]
    waits = (i for i, f in enumerate(events) if f[0] == "filler" and f[1].get("wait"))
    last_wait = max(waits, default=-1)
    assert last_wait < kinds.index("sentence"), kinds


def test_a_fast_round_hears_no_wait_line(member, engine, monkeypatch, household_user):
    # The phone's own gap: 1.5 s. A round that answers at once never reaches it.
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, FakeModel([say("You spent a little.")]))
    assert _waits(events) == []


def test_a_cancel_during_a_silent_round_sends_no_wait_line(
    member, engine, monkeypatch, household_user, quick_chain
):
    # The chain's own version of F6: a clip already pushed cannot be recalled, so once the page has
    # let the turn go nothing more is sent.
    from tests.test_cancel_paid_calls import _Flag

    conversation = _later(member, engine, monkeypatch, household_user)
    flag = _Flag()
    monkeypatch.setattr(cascade, "CancelCheck", lambda *a, **k: flag)

    class CancelledWhileSilent(SlowModel):
        def stream(self, req):
            flag.hit = True
            yield from super().stream(req)

    events = _turn(member, engine, conversation, CancelledWhileSilent([say("Done.")], [1.0]))
    assert _waits(events) == []


# ---- the acknowledgement's skip list is the phone's (`fillers.ack_skip`) -----------------------


def test_a_goodbye_turn_says_no_acknowledgement(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, FakeModel([say("Goodbye.")]), heard="bye")
    assert _acks(events) == []


def test_a_barge_in_turn_says_no_acknowledgement(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    conversation = _conversation(member, engine)
    engine.speech.heard = ASK
    engine.model = FakeModel([say("You spent a little.")])
    events = _events(
        _post(member, audio=PCM, conversation_id=conversation, barge_in="true")
    )
    assert _acks(events) == []


def test_an_ordinary_later_turn_says_one(member, engine, monkeypatch, household_user):
    conversation = _later(member, engine, monkeypatch, household_user)
    events = _turn(member, engine, conversation, FakeModel([say("You spent a little.")]))
    assert len(_acks(events)) == 1
    assert _acks(events)[0] in fillers.ACK
