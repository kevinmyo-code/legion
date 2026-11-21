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


def test_a_claim_in_a_tainted_turn_with_no_write_promises_nothing():
    # Re-check R1 (ADR 0090 amended): the unbacked-claim path never sets the carry's exception, so
    # the next turn is carried and "ask me again" would be false.
    from agents.legion.lines import READ_THROUGH_NOTHING_CHANGED

    state = _state(taint=["web_search"])
    said, _ = _run(state, [S("I added it to your calendar.")])
    assert said == [READ_THROUGH_NOTHING_CHANGED]


def test_a_write_refused_for_outside_text_gets_its_line():
    from agents.legion.lines import READ_THROUGH_WAIT
    from assistant import read_through

    refusal = read_through.WRITE_REFUSAL.format(verb="changed", name="add_event")
    gw = _gateway([TurnCall("add_event", False, refusal, True)])
    # His words asked for the change, so the exception's mark is set and the promise holds (R1).
    state = _state(taint=["web_search", "write_refused"])
    said, _ = _run(state, [S("The fair is on Saturday.")], gw)
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


# ---- ask-again (spec 23, inventory row 11) ---------------------------------------------------


@pytest.mark.django_db
def test_a_filler_only_answer_after_a_tool_is_asked_once_more_without_tools(seeded):
    from agents.legion.speech_rules import FILLER_NOTE
    from agents.models import AgentModelCall
    from llm.models import LlmUsage

    model = m.FakeModel(
        [m.call("read_list", {}), m.say("Checking now."), m.say("Milk and eggs are on it.")]
    )
    events, _s, conversation = _turn(seeded, "what's on my list?", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == ["Milk and eggs are on it."]
    again = model.requests[-1]
    assert again.allow_tools is False
    assert again.contents[-1]["parts"][0]["text"] == FILLER_NOTE
    turn = AgentTurn.objects.get(conversation=conversation)
    assert AgentModelCall.objects.filter(turn=turn).count() == 3
    assert LlmUsage.objects.filter(user=seeded, feature="agent_turn").count() == 3
    assert turn.final_text == "Milk and eggs are on it."


@pytest.mark.django_db
def test_an_empty_answer_after_a_tool_is_asked_once_more(seeded):
    model = m.FakeModel([m.call("read_list", {}), m.say(""), m.say("Milk is on it.")])
    events, _s, _c = _turn(seeded, "list?", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == ["Milk is on it."]


@pytest.mark.django_db
def test_with_no_tool_run_a_filler_answer_is_not_asked_again(seeded):
    model = m.FakeModel([m.say("One moment.")])
    events, _s, _c = _turn(seeded, "hm", model)
    assert len(model.requests) == 1
    assert [e.data["text"] for e in events if e.type == "sentence"] == [NO_TEXT + NOTHING_CHANGED]


@pytest.mark.django_db
def test_an_ask_again_that_fails_ends_in_the_no_answer_line(seeded):
    model = m.FakeModel([m.call("read_list", {}), m.say("")])
    events, _s, _c = _turn(seeded, "list?", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == [NO_ANSWER_WORDS]


# ---- money spoken by code (ADR 0094, inventory row 17) ---------------------------------------


def _verbatim_tool(monkeypatch, name, structured, text="code's own text"):
    from agents.legion import gateway
    from engine_mcp.tools import ToolResult

    real = gateway.run_engine_tool

    def run(request, tool, args):
        if tool.name == name:
            return ToolResult(text=text, structured=structured)
        return real(request, tool, args)

    monkeypatch.setattr(gateway, "run_engine_tool", run)


@pytest.mark.django_db
def test_a_money_line_is_said_by_code_and_model_money_after_it_is_dropped(seeded, monkeypatch):
    _verbatim_tool(
        monkeypatch,
        "account_balances",
        {"speak_verbatim": "Net cash is 1,962.48 dollars.", "net_cents": 196248},
    )
    model = m.FakeModel(
        [
            m.call("account_balances", {}),
            m.say("You have about two thousand dollars. Anything else?"),
        ]
    )
    events, _s, conversation = _turn(seeded, "how much cash?", model)
    said = [e.data["text"] for e in events if e.type == "sentence"]
    assert said == ["Net cash is 1,962.48 dollars.", "Anything else?"]
    # The model was handed the result as before (no for_model in this result).
    response = model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert response == {"success": True, "message": "code's own text"}


@pytest.mark.django_db
def test_a_whole_answer_by_code_ends_the_turn_and_the_history_stays_whole(seeded, monkeypatch):
    # The shape `board_status` returns; a tool every member is offered stands in for it.
    _verbatim_tool(
        monkeypatch,
        "account_balances",
        {
            "speak_verbatim": ["Two cards are in flight.", "One waits on review."],
            "speak_exact": True,
            "ends_turn": True,
            "for_model": "(two cards; read aloud by code)",
            "replay_as": "(Read the board aloud.)",
        },
    )
    model = m.FakeModel([m.call("account_balances", {})])
    events, _s, conversation = _turn(seeded, "what's on the board?", model)
    said = [e.data["text"] for e in events if e.type == "sentence"]
    assert said == ["Two cards are in flight.", "One waits on review."]
    assert len(model.requests) == 1  # no further round: the answer is code's
    turn = AgentTurn.objects.get(conversation=conversation)
    assert turn.final_text == "Two cards are in flight. One waits on review."
    history = turn.history
    responses = [
        p["functionResponse"] for c in history for p in c.get("parts") or []
        if "functionResponse" in p
    ]
    assert responses[-1]["response"]["message"] == "(two cards; read aloud by code)"
    assert history[-1] == {"role": "model", "parts": [{"text": "(Read the board aloud.)"}]}


# ---- end_conversation (spec 18, inventory row 28) --------------------------------------------


@pytest.mark.django_db
def test_end_conversation_signs_off_and_tells_the_limb_to_close(seeded):
    from agents.legion.end_conversation import END_INSTRUCTION, END_TOOL

    model = m.FakeModel([m.call(END_TOOL, {}), m.say("Very well.")])
    events, _s, _c = _turn(seeded, "never mind", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == ["Very well."]
    assert events[-1].data["end"] is True
    response = model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert response == {"success": True, "message": END_INSTRUCTION}
    assert END_TOOL in {d["name"] for d in model.requests[0].declarations}


@pytest.mark.django_db
def test_a_stop_phrase_closes_even_when_the_model_only_signs_off(seeded):
    events, _s, _c = _turn(seeded, "That will be all, thanks", m.FakeModel([m.say("Very well.")]))
    assert events[-1].data["end"] is True


@pytest.mark.django_db
def test_an_ordinary_turn_carries_no_end(seeded):
    events, _s, _c = _turn(seeded, "hello", m.FakeModel([m.say("Hello.")]))
    assert "end" not in events[-1].data


@pytest.mark.django_db
def test_end_conversation_is_never_offered_to_the_background_spec(seeded):
    from agents.legion.end_conversation import END_TOOL
    from agents.legion.gateway import LegionGateway
    from agents.legion.specs import spec

    gw = LegionGateway(member_request(seeded))
    assert END_TOOL in {d["name"] for d in gw.declarations(spec("generalist"))}
    assert END_TOOL not in {d["name"] for d in gw.declarations(spec("background"))}


# ---- the unconfirmed write (plan 1119 section 2) ---------------------------------------------


def _add_stamps():
    return m.call(
        "add_checklist_item", {"checklist_id": str(fixture.rid("Todo")), "text": "made-up stamps"}
    )


@pytest.mark.django_db
def test_gate_case_1_a_write_the_reply_never_names_is_said_by_code(seeded):
    events, state, _c = _turn(seeded, "stamps", m.FakeModel([_add_stamps(), m.say("Okay.")]))
    said = [e.data["text"] for e in events if e.type == "sentence"]
    assert state.writes_ran
    assert said[0] == "Okay." and said[1].startswith("Done: added made-up stamps")
    assert len(said) == 2


@pytest.mark.django_db
def test_gate_case_1_variant_a_reply_that_names_the_item_gets_no_extra_line(seeded):
    model = m.FakeModel([_add_stamps(), m.say("Made-up stamps is on your list.")])
    events, _s, _c = _turn(seeded, "stamps", model)
    assert [e.data["text"] for e in events if e.type == "sentence"] == [
        "Made-up stamps is on your list."
    ]


def test_the_done_line_names_each_write_and_counts_the_rest():
    from agents.legion.done_lines import done_line

    facts = [
        {"tool": "add_checklist_item", "title": "eggs", "list": "Groceries"},
        {"tool": "log_meal", "title": "lunch"},
        {"tool": "tick_checklist_item", "title": "rent"},
        {"tool": "add_event", "title": "dentist"},
    ]
    assert done_line(facts, "Sure.") == (
        "Done: added eggs to Groceries; logged lunch; ticked off rent; and 1 more."
    )
    assert done_line(facts[:1], "Eggs are on Groceries.") is None


def test_a_write_with_no_certain_verb_gets_the_generic_line_only_when_nothing_was_claimed():
    from agents.legion.done_lines import GENERIC_DONE, done_line

    fact = [{"tool": "update_event"}]
    assert done_line(fact, "Okay.") == GENERIC_DONE
    assert done_line(fact, "I marked it done.") is None


def test_a_relayed_write_that_never_came_back_is_said_as_not_confirmed():
    from agents.legion.done_lines import UNCONFIRMED_ONE

    state = _state()
    state.actions.append({"name": "set_timer", "writes": True, "ok": False, "outcome": "timed_out"})
    said, _ = _run(state, [S("The timer is ringing soon.")])
    assert said == ["The timer is ringing soon.", UNCONFIRMED_ONE]


# ---- the voice clause: both brains are told the same (inventory row 19) ----------------------


BLANK_LINE = chr(10) * 2
PHONE_FIGURES = "Write numbers, money, times and units the way you would say them."
WEB_FIGURES = "Write every number, amount, time and unit exactly, in digits."


def test_the_generalist_prompt_carries_the_phones_voice_clause_with_the_web_doors_figure_rule():
    """Both brains get the phone's clause, except its figure sentence: the model writes figures
    in digits (the web door's rule), so the money and torque guards can check them, and code
    decides how they are said (review 1121 F1)."""
    import re
    from pathlib import Path

    from assistant import cascade

    root = Path(__file__).resolve().parents[2]
    rules = root / "app/src/main/java/com/kevin/legion/service/cascade/CascadeRules.kt"
    kotlin = rules.read_text(encoding="utf-8")
    block = kotlin.split("const val VOICE_CLAUSE =", 1)[1].split(BLANK_LINE, 1)[0]
    # The Kotlin string's pieces: its clause holds no quote or backslash.
    clause = " ".join("".join(re.findall(r'"([^"]*)"', block)).split())
    assert PHONE_FIGURES in clause
    assert WEB_FIGURES in " ".join(cascade.VOICE_CLAUSE.split())
    prompt_file = root / "server/agents/specs/generalist.md"
    prompt = " ".join(prompt_file.read_text(encoding="utf-8").split())
    assert clause.replace(PHONE_FIGURES, WEB_FIGURES) in prompt
    assert PHONE_FIGURES not in prompt


def test_a_spelled_out_money_amount_is_checked_like_a_figure_once_a_money_tool_ran():
    # Review 1121 F1: the model is told digits; an amount it still writes in words is read and
    # checked by membership too (the second net), never rounded.
    gw = _gateway([TurnCall("monthly_spend", True, "Food: 312.50.", False, {"total_cents": 31250})])
    said, _ = _run(
        _state(),
        [
            S("Food came to 312.50."),
            S("That is about three hundred twelve dollars and sixty cents."),
            S("In words, three hundred twelve dollars and fifty cents."),
            S("You spent twelve bucks on coffee."),
        ],
        gw,
    )
    assert said == [
        "Food came to 312.50.",
        "In words, three hundred twelve dollars and fifty cents.",
    ]


def test_spelled_amounts_read_as_cents():
    from agents.legion.spelled_money import spelled_cents, words_value

    assert words_value("three hundred twelve") == 312
    assert words_value("two thousand four hundred") == 2400
    assert spelled_cents("six dollars and fifty cents for coffee") == [650]
    assert spelled_cents("twelve bucks, then ninety-nine cents") == [1200, 99]
    assert spelled_cents("It is mild.") == []


def test_with_no_money_tool_a_spelled_out_amount_is_left_alone():
    said, _ = _run(_state(), [S("A coffee is about five dollars there.")])
    assert said == ["A coffee is about five dollars there."]


def test_a_torque_figure_in_words_is_dropped_by_the_torque_guard():
    import re

    from agents.legion.speech_rules import TorqueGuard

    guard = TorqueGuard(frozenset({"185"}), re.compile(r"pitman", re.I), whole_turn=False)
    turn = SpokenTurn(_state(), _gateway(), on=True)
    turn.gate = spoken.gates.SentenceGate(
        backed=lambda: None, torque=lambda: guard, actions=lambda: []
    )
    state = turn.state
    line = S("Torque the pitman nut to one hundred eighty five ft-lbs.")
    out = list(turn.run(_graph(state, [line])))
    assert [e.data["text"] for e in out if e.type == "sentence"] == [
        spoken.closing.nothing_said_line(False)
    ]


@pytest.mark.django_db
def test_heard_until_counts_the_untagged_text_the_history_holds(seeded):
    """`heard_until.chars` counts characters of the UNTAGGED sentence text the engine sent, joined
    by single spaces: exactly what `done.final_text` and the stored history hold."""
    model = m.FakeModel([m.say("<chuckle> Fair enough. Nothing is due today.")])
    events, _s, conversation = _turn(seeded, "anything?", model)
    turn = AgentTurn.objects.get(conversation=conversation)
    assert turn.final_text == "Fair enough. Nothing is due today."
    assert turn.history[-1] == {
        "role": "model", "parts": [{"text": "Fair enough. Nothing is due today."}]
    }
    assert events[-1].data["final_text"] == turn.final_text


# ---- review fixes ------------------------------------------------------------------------------


def test_after_a_cancel_no_closing_or_done_line_is_said():
    state = _state()
    turn = SpokenTurn(state, _gateway(), on=True)

    def graph():
        yield ev.Event("tool", {"name": "add_checklist_item", "ok": True,
                                "write_fact": {"tool": "add_checklist_item", "title": "eggs"}})
        state.actions.append({"name": "add_checklist_item", "writes": True, "ok": True})
        yield S("I added eggs and")
        state.status = "cancelled"
        yield S("Stopped: the turn was cancelled.")
        yield ev.done("cancelled", "", True)
        return state

    said = [e.data["text"] for e in turn.run(graph()) if e.type == "sentence"]
    assert said == ["I added eggs and", "Stopped: the turn was cancelled."]


@pytest.mark.parametrize(
    "line,wait",
    [
        ("Just a second.", True), ("Give me a second on that.", True), ("One sec.", True),
        ("Hang on.", True), ("Bear with me.", True), ("Checking.", True), ("One moment.", True),
        ("Let me look.", True), ("Okay, just a moment.", True),
        ("It is mild.", False), ("Just a second ago it rang.", False),
        ("Give me the codes.", False), ("One code is stored.", False), ("I missed that.", False),
        ("Hang on to the receipt.", False),
    ],
)
def test_the_phones_wait_lines_are_filler_answers_are_not(line, wait):
    from agents.legion.trailing_filler import is_wait_line

    assert is_wait_line(line) is wait


def test_after_content_a_wait_line_is_dropped_and_a_leading_one_is_the_opening_rules():
    said, _ = _run(_state(), [S("I missed that."), S("Give me a second on that.")])
    assert said == ["I missed that."]


def test_the_cap_is_the_web_doors():
    from assistant import cascade

    assert spoken.MAX_SPOKEN_SENTENCES == cascade.MAX_SPOKEN_SENTENCES


def test_answered_gives_a_call_that_never_ran_a_failure_response():
    from agents.legion.spoken import NOT_RUN_RESPONSE, answered

    msgs = [{"role": "model", "parts": [{"functionCall": {"name": "x", "args": {}}}]}]
    out = answered(msgs, [])
    assert out[-1]["parts"][0]["functionResponse"]["response"] == NOT_RUN_RESPONSE


@pytest.mark.django_db
def test_the_background_spec_keeps_its_dates_as_the_model_sent_them(seeded):
    from agents.legion.gateway import LegionGateway
    from agents.legion.specs import spec
    from agents.runtime.tools import ToolCall

    state = _state(transcript="and tomorrow?")
    state.client_facts["local"] = {"day": "2026-03-10", "time": "21:00"}
    gw = LegionGateway(member_request(seeded))
    asked = ToolCall("c", "read_calendar", {"from": "tomorrow", "to": "tomorrow"})
    kept, note = gw._his_day(state, spec("background"), asked)
    assert kept.args == asked.args and note is None
    fixed, _ = gw._his_day(state, spec("generalist"), asked)
    assert fixed.args == {"from": "2026-03-11", "to": "2026-03-11"}
