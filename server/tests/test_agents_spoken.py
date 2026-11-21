"""Engine-brain slice E3: the web door's turn rules on the engine's agent turn (`agents/legion/
spoken.py`). Unit tests drive `SpokenTurn` over a scripted event stream; the turn tests run
`run_turn` with a scripted model (`FakeModel`). Nothing reaches Google; every name is invented."""

from __future__ import annotations

import datetime as dt
from types import SimpleNamespace

import pytest

from agents.evals import fixture
from agents.evals.runner import member_request
from agents.graph.state import OUTSIDE_REPLY_STUB, TurnState
from agents.legion import spoken
from agents.legion.lines import CHANGE_MADE, CHANGE_NOT_MADE, NO_TEXT, NOTHING_CHANGED
from agents.legion.spoken import SPEECH_KEY, SpokenTurn, TurnCall, as_heard
from agents.legion.turn import open_conversation, run_turn
from agents.models import AgentTurn
from agents.runtime import events as ev
from agents.runtime import model as m
from agents.runtime.loop import MODEL_WORDS, NO_ANSWER_WORDS

NOW = dt.datetime(2026, 3, 10, 17, 0, tzinfo=dt.UTC)
TODAY = dt.date(2026, 3, 10)


def _state(**kw) -> TurnState:
    return TurnState(
        turn_id="t", conversation_id="c", seq=1, household_id="h", member_id="u", client="phone",
        transcript=kw.pop("transcript", "anything"), **kw,
    )


def _gateway(calls=()):
    return SimpleNamespace(calls=list(calls), request=SimpleNamespace())


def _graph(state, events, status="done"):
    """A stand-in for the graph: the loop's events, then `finish_node`'s status and `done`."""
    for e in events:
        if callable(e):
            e()
            continue
        yield e
    state.status = status
    said = [x.data["text"] for x in events if getattr(x, "type", "") == "sentence"]
    yield ev.done(status, " ".join(said), state.writes_ran)
    return state


def _run(state, events, gateway=None, status="done", on=True):
    turn = SpokenTurn(state, gateway or _gateway(), on=on)
    out = []
    gen = turn.run(_graph(state, events, status))
    try:
        while True:
            out.append(next(gen))
    except StopIteration:
        pass
    return [e.data["text"] for e in out if e.type == "sentence"], out[-1]


def S(text):
    return ev.sentence(text)


# ---- the sentence gate ------------------------------------------------------------------------


def test_an_opening_filler_line_is_dropped_and_the_answer_said():
    said, done = _run(_state(), [S("Checking now."), S("Your day is clear.")])
    assert said == ["Your day is clear."]
    assert done.data["final_text"] == "Your day is clear."


def test_a_trailing_filler_line_after_the_answer_is_dropped_too():
    # Inventory row 10 (the phone's TrailingFiller): the web door says it; the agent turn does not.
    said, _ = _run(_state(), [S("Your day is clear."), S("One moment.")])
    assert said == ["Your day is clear."]


def test_past_the_cap_nothing_more_is_said():
    lines = [S(f"Line number {w} here.") for w in "abcdefghijklmn"]
    said, _ = _run(_state(), lines)
    assert len(said) == spoken.MAX_SPOKEN_SENTENCES == 12


def test_a_figure_no_money_tool_returned_is_not_said():
    gw = _gateway([TurnCall("monthly_spend", True, "Food: 84.27.", False, {"total_cents": 8427})])
    said, _ = _run(_state(), [S("Food came to 84.27."), S("Food came to 84.30.")], gw)
    assert said == ["Food came to 84.27."]


def test_a_claim_with_no_write_is_held_and_the_closing_line_says_so():
    said, done = _run(_state(), [S("I added milk to your list.")])
    assert said == [CHANGE_NOT_MADE]
    assert done.data["final_text"] == CHANGE_NOT_MADE


def test_a_claim_made_before_its_own_write_is_said_at_the_end():
    state = _state()

    def write():
        state.actions.append({"name": "add_checklist_item", "writes": True, "ok": True})

    said, _ = _run(state, [S("I added milk to your list."), write])
    assert said == ["I added milk to your list."]


def test_a_claim_in_a_tainted_turn_with_no_write_gets_the_read_through_wait():
    from agents.legion.lines import READ_THROUGH_WAIT

    state = _state(taint=["web_search"])
    said, _ = _run(state, [S("I added it to your calendar.")])
    assert said == [READ_THROUGH_WAIT]


def test_a_write_refused_for_outside_text_gets_its_line():
    from agents.legion.lines import READ_THROUGH_WAIT
    from assistant import read_through

    refusal = read_through.WRITE_REFUSAL.format(verb="changed", name="add_event")
    gw = _gateway([TurnCall("add_event", False, refusal, True)])
    said, _ = _run(_state(taint=["web_search"]), [S("The fair is on Saturday.")], gw)
    assert said == ["The fair is on Saturday.", READ_THROUGH_WAIT]


def test_a_turn_that_says_nothing_says_so():
    said, _ = _run(_state(), [S("Checking.")])
    assert said == [NO_TEXT + NOTHING_CHANGED]


def test_the_runtime_no_answer_line_is_said_once_when_nothing_else_was():
    said, _ = _run(_state(), [S(NO_ANSWER_WORDS)])
    assert said == [NO_ANSWER_WORDS]


def test_a_code_line_from_finish_is_never_checked():
    # `finish_node` says the loop's words once the status is set: here a failure line that looks
    # like a claim, and filler-shaped; it goes out as it is.
    state = _state()
    events = [S("Your day is clear.")]

    def fail():
        state.status = "failed"

    turn = SpokenTurn(state, _gateway(), on=True)

    def graph():
        yield from events
        fail()
        yield S(MODEL_WORDS)
        yield ev.done("failed", "", False)
        return state

    out = list(turn.run(graph()))
    said = [e.data["text"] for e in out if e.type == "sentence"]
    assert said == ["Your day is clear.", MODEL_WORDS]


def test_tags_are_checked_off_and_kept_for_speech():
    said, done = _run(_state(), [S("<chuckle> Checking."), S("<sigh> Your day is clear.")])
    assert said == ["<sigh> Your day is clear."]
    assert done.data["final_text"] == "Your day is clear."


def test_the_background_spec_is_passed_through_untouched():
    said, done = _run(_state(), [S("Checking now."), S("I added milk.")], on=False)
    assert said == ["Checking now.", "I added milk."]
    assert done.data["final_text"] == "Checking now. I added milk."


def test_outside_text_keeps_the_stub_in_done():
    state = _state(outside=["web_search"], taint=["web_search"])
    turn = SpokenTurn(state, _gateway(), on=True)

    def graph():
        yield S("It opens at nine.")
        state.status = "done"
        yield ev.done("done", OUTSIDE_REPLY_STUB, False)
        return state

    out = list(turn.run(graph()))
    assert out[0].data["text"] == "It opens at nine."
    assert out[-1].data["final_text"] == OUTSIDE_REPLY_STUB


# ---- across a pause on a limb ----------------------------------------------------------------


def _paused(state, events):
    turn = SpokenTurn(state, _gateway(), on=True)

    def graph():
        yield from events
        yield ev.done("awaiting_limb", "", False, calls=["c1"])
        return state

    return [e for e in turn.run(graph())]


def test_a_pause_keeps_the_speech_so_far_and_says_no_closing_line():
    state = _state()
    out = _paused(state, [S("Checking."), S("I set the timer.")])
    assert [e.data["text"] for e in out if e.type == "sentence"] == []
    kept = state.client_facts[SPEECH_KEY]
    assert kept["held"] == [["I set the timer.", "I set the timer."]]
    assert kept["filler_dropped"] == 1
    # The continuation picks it up: the write went through on the limb, so the claim is said.
    state.status = "running"
    state.actions.append({"name": "set_timer", "writes": True, "ok": True})
    said, _ = _run(state, [S("It rings in five minutes.")])
    assert said == ["It rings in five minutes.", "I set the timer."]


def test_no_sentence_text_is_kept_in_client_facts_once_outside_text_is_in():
    state = _state(outside=["web_search"], taint=["web_search"])
    _paused(state, [S("CANARY-7f3a opens at nine."), S("I booked it.")])
    kept = state.client_facts[SPEECH_KEY]
    assert "said" not in kept and "held" not in kept
    assert "CANARY" not in repr(state.client_facts)


# ---- the stored history is what was said ------------------------------------------------------


def test_as_heard_leaves_an_unchanged_turn_alone():
    msgs = [
        {"role": "user", "parts": [{"text": "hi"}]},
        {"role": "model", "parts": [{"text": "Hello."}]},
    ]
    assert as_heard(msgs, 0, "Hello.") is msgs


def test_as_heard_keeps_calls_and_ends_on_the_said_words():
    call = {"functionCall": {"name": "read_list", "args": {}}}
    msgs = [
        {"role": "user", "parts": [{"text": "list?"}]},
        {"role": "model", "parts": [{"text": "Checking."}, call]},
        {"role": "user", "parts": [{"functionResponse": {"name": "read_list", "response": {}}}]},
        {"role": "model", "parts": [{"text": "Milk and eggs."}]},
    ]
    out = as_heard(msgs, 0, "Milk and eggs.")
    assert out[1] == {"role": "model", "parts": [call]}
    assert out[-1] == {"role": "model", "parts": [{"text": "Milk and eggs."}]}
    assert len(out) == 4


# ---- through run_turn ------------------------------------------------------------------------



@pytest.fixture
def seeded(household_user, household_a, monkeypatch):
    monkeypatch.setattr("engine_mcp.tools._now", lambda: NOW)
    fixture.seed(household_a, household_user, TODAY)
    return household_user


def _turn(user, text, model, conversation=None):
    request = member_request(user)
    conversation = conversation or open_conversation(request, None, "test")
    gen = run_turn(request, text, model, conversation=conversation, now=NOW)
    events = []
    try:
        while True:
            events.append(next(gen))
    except StopIteration as stop:
        state = stop.value
    return events, state, conversation


@pytest.mark.django_db
def test_the_agent_turn_drops_an_opening_filler_and_stores_what_was_said(seeded):
    model = m.FakeModel([m.say("One moment. Nothing is due today.")])
    events, _state_, conversation = _turn(seeded, "anything due?", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == ["Nothing is due today."]
    turn = AgentTurn.objects.get(conversation=conversation)
    assert turn.final_text == "Nothing is due today."
    assert turn.history[-1] == {"role": "model", "parts": [{"text": "Nothing is due today."}]}


@pytest.mark.django_db
def test_the_agent_turn_holds_an_unbacked_claim_and_closes_in_words(seeded):
    model = m.FakeModel([m.say("I added milk to your list.")])
    events, _s, _c = _turn(seeded, "milk", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == [CHANGE_NOT_MADE]
    done = events[-1]
    assert done.type == "done" and done.data["final_text"] == CHANGE_NOT_MADE


@pytest.mark.django_db
def test_a_model_failure_still_ends_in_the_loops_own_words(seeded):
    events, _s, _c = _turn(seeded, "anything?", m.FakeModel([]))
    assert [e.data["text"] for e in events if e.type == "sentence"] == [MODEL_WORDS]


@pytest.mark.django_db
def test_a_real_write_is_said_and_no_closing_line_follows(seeded):
    model = m.FakeModel(
        [
            m.call(
                "add_checklist_item",
                {"checklist_id": str(fixture.rid("Todo")), "text": "made-up stamps"},
            ),
            m.say("I added made-up stamps to your to-do list."),
        ]
    )
    events, state, _c = _turn(seeded, "add stamps", model)
    said = [e.data["text"] for e in events if e.type == "sentence"]
    assert state.writes_ran, said
    assert said == ["I added made-up stamps to your to-do list."]
    assert CHANGE_MADE not in said
