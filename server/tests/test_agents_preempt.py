"""Draft-start on the streamed door (`agents/legion/preempt.py`, dark behind
`LEGION_AGENTS_PREEMPT`): the model's first round asked on the running guess of his words, used
only when the final words match and the turn's own first request is the draft's. No tool runs on a
draft; nothing is stored for one before the final words are known; a wasted draft is metered and
counted against the conversation's cap. Fake model, fake socket; every word invented."""

# ruff: noqa: F811 - the fixtures are imported from the stream and turn tests

from __future__ import annotations

import pytest

from agents.evals.runner import member_request
from agents.legion import preempt
from agents.legion.flags import PREEMPT_ENV
from agents.legion.turn import open_conversation, run_turn
from agents.models import AgentConversation, AgentModelCall, AgentToolCall, AgentTurn
from agents.runtime import model as m
from llm.models import LlmUsage
from tests.test_engine_voice_live_stt import DONE, FakeSocket, _final, _interim
from tests.test_engine_voice_stream import _events, _post, stream, utterance  # noqa: F401
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db

ASKED = "What is on the calendar today?"


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(PREEMPT_ENV, "on")
    # Stable at once: the second time the same guess is heard.
    monkeypatch.setattr(preempt, "STABLE_S", 0.0)


class Models:
    """Hands out fake models, one scripted list of rounds each, and keeps them."""

    def __init__(self, *rounds):
        self.rounds = list(rounds)
        self.made: list = []

    def __call__(self):
        model = m.FakeModel(self.rounds.pop(0) if self.rounds else [])
        self.made.append(model)
        return model


def drafter(request, models, conversation=None, wasted_before=0):
    return preempt.Drafter(
        request,
        conversation=conversation,
        facts={},
        model_for=models,
        wasted_before=wasted_before,
    )


def draft(d, guess):
    """The guess heard twice (stable), the draft's round run to its end."""
    d.heard(guess)
    d.heard(guess)
    assert d.current is not None
    assert d.current.done.wait(5)


def answer(request, d, final, turn_model, conversation=None):
    conversation = conversation or open_conversation(request, None, "phone")
    model, now = d.primed(final, turn_model)
    events = list(d.watch(run_turn(request, final, model, conversation=conversation, now=now)))
    d.close()
    return events, conversation


def said(events) -> list[str]:
    return [e.data["text"] for e in events if e.type == "sentence"]


# ---- the switch ----


def test_no_drafter_unless_its_switch_is_on(household_user, monkeypatch):
    monkeypatch.delenv(PREEMPT_ENV, raising=False)
    request = member_request(household_user)
    data = {"client": "phone", "conversation_id": None}
    assert preempt.drafter_for(request, data, {"client_facts": {}}, Models()) is None
    monkeypatch.setenv(PREEMPT_ENV, "on")
    assert preempt.drafter_for(request, data, {"client_facts": {}}, Models()) is not None


def test_no_drafter_with_late_answers_on_or_for_a_barge_in_turn(household_user, on, monkeypatch):
    request = member_request(household_user)
    data = {"client": "phone", "conversation_id": None}
    cut = {"client_facts": {"heard_until": {"turn_id": "x", "chars": 3}}}
    assert preempt.drafter_for(request, data, cut, Models()) is None
    monkeypatch.setenv("LEGION_LATE_ANSWERS", "on")
    assert preempt.drafter_for(request, data, {"client_facts": {}}, Models()) is None


# ---- a draft that matches ---------------------------------------------------------------------


def test_a_matching_draft_answers_the_first_round_and_the_turn_asks_nothing_more(
    household_user, on
):
    request = member_request(household_user)
    models = Models([m.say("Nothing is on today.")])
    d = drafter(request, models)
    draft(d, "what is on the calendar today")
    turn_model = m.FakeModel([])
    events, _c = answer(request, d, ASKED, turn_model)
    assert said(events)[0] == "Nothing is on today."
    assert turn_model.requests == []
    assert len(models.made) == 1 and len(models.made[0].requests) == 1
    # The draft's round is the turn's round 0, metered once, as the turn's own; nothing wasted.
    calls = AgentModelCall.objects.all()
    assert [(c.node, c.round) for c in calls] == [("agent", 0)]
    assert not LlmUsage.objects.filter(feature=preempt.FEATURE).exists()
    # His final words are what the turn keeps.
    assert AgentTurn.objects.get().transcript == ASKED


def test_the_draft_request_is_the_turns_own_first_request(household_user, on):
    """Built from reads only, it equals what the loop sends (his words aside)."""
    request = member_request(household_user)
    conversation = open_conversation(request, None, "phone")
    first = list(
        run_turn(request, "hello there", m.FakeModel([m.say("Hello.")]), conversation=conversation)
    )
    assert first
    models = Models([m.say("Two things today.")])
    d = drafter(request, models, conversation=conversation)
    draft(d, "what is on the calendar today")
    turn_model = m.FakeModel([])
    events, _c = answer(request, d, ASKED, turn_model, conversation=conversation)
    assert turn_model.requests == [], "the draft was not used: its request differed"
    assert "Two things today." in said(events)


# ---- a draft that does not match --------------------------------------------------------------


def test_a_draft_on_other_words_is_dropped_paid_for_and_counted(household_user, on):
    request = member_request(household_user)
    models = Models([m.say("Nothing is on today.")])
    d = drafter(request, models)
    draft(d, "what is on the calendar today")
    turn_model = m.FakeModel([m.say("Tomorrow is clear.")])
    events, conversation = answer(request, d, "What is on the calendar tomorrow?", turn_model)
    assert said(events)[0] == "Tomorrow is clear."
    assert len(turn_model.requests) == 1
    wasted = AgentModelCall.objects.filter(node=preempt.NODE)
    assert [(c.finish_reason, c.round) for c in wasted] == [(preempt.WASTED, -1)]
    assert wasted[0].turn.conversation_id == conversation.pk
    assert LlmUsage.objects.filter(feature=preempt.FEATURE).count() == 1


def test_a_changed_guess_cancels_the_draft_and_drafts_again(household_user, on):
    request = member_request(household_user)
    models = Models([m.say("A first answer.")], [m.say("Nothing is on today.")])
    d = drafter(request, models)
    draft(d, "what is on the")
    draft(d, "what is on the calendar today")
    turn_model = m.FakeModel([])
    events, _c = answer(request, d, ASKED, turn_model)
    assert said(events)[0] == "Nothing is on today."
    assert len(models.made) == 2 and turn_model.requests == []
    assert AgentModelCall.objects.filter(node=preempt.NODE).count() == 1


# ---- what a draft may do: nothing but ask the model ----


def test_a_tool_call_on_a_draft_runs_nothing_until_the_final_words_match(household_user, on):
    request = member_request(household_user)
    models = Models([m.call("remember", {"text": "likes window seats"})])
    d = drafter(request, models)
    draft(d, "remember i like window seats")
    # The draft's round is over: it asked for a write, and nothing ran or was stored.
    assert not AgentTurn.objects.exists()
    assert not AgentToolCall.objects.exists()
    assert not AgentConversation.objects.exists()
    # The final words differ: the planned call never runs.
    turn_model = m.FakeModel([m.say("Noted.")])
    answer(request, d, "remember i like aisle seats", turn_model)
    assert not AgentToolCall.objects.filter(tool="remember").exists()
    assert AgentModelCall.objects.filter(node=preempt.NODE).count() == 1


def test_a_matching_drafts_call_runs_after_the_final_words_through_the_gateway(household_user, on):
    request = member_request(household_user)
    models = Models([m.call("remember", {"text": "likes window seats"})])
    d = drafter(request, models)
    draft(d, "remember i like window seats")
    turn_model = m.FakeModel([m.say("Noted.")])
    events, _c = answer(request, d, "Remember I like window seats.", turn_model)
    # The call ran once, in the turn, recorded as the turn's own (its guard decided the rest).
    assert AgentToolCall.objects.filter(tool="remember").count() == 1
    assert len(turn_model.requests) == 1


@pytest.mark.parametrize("guess", ["i want to die", "that's all thanks"])
def test_no_draft_on_a_crisis_or_stop_phrase(household_user, on, guess):
    request = member_request(household_user)
    models = Models([m.say("x")])
    d = drafter(request, models)
    d.heard(guess)
    d.heard(guess)
    assert d.current is None and models.made == []


def test_the_conversations_cap_stops_drafting(household_user, on):
    request = member_request(household_user)
    conversation = open_conversation(request, None, "phone")
    list(run_turn(request, "hi", m.FakeModel([m.say("Hi.")]), conversation=conversation))
    turn = AgentTurn.objects.get()
    for _ in range(preempt.MAX_WASTED_PER_CONVERSATION):
        AgentModelCall.objects.create(
            turn=turn,
            node=preempt.NODE,
            agent="generalist",
            model="m",
            round=-1,
            finish_reason=preempt.WASTED,
            household_id=turn.household_id,
            owner_user_id=turn.owner_user_id,
        )
    data = {"client": "phone", "conversation_id": conversation.pk}
    assert preempt.drafter_for(request, data, {"client_facts": {}}, Models()) is None
    # One under the cap: a drafter, which stops at the cap.
    AgentModelCall.objects.filter(node=preempt.NODE).first().delete()
    d = preempt.drafter_for(request, data, {"client_facts": {}}, Models([m.say("a")]))
    assert d is not None and d.wasted_before == preempt.MAX_WASTED_PER_CONVERSATION - 1
    draft(d, "one guess")
    d.heard("another guess")
    d.heard("another guess")
    assert len(d.drafts) == 1
    d.close()


def test_an_utterance_that_never_became_a_turn_still_pays_for_its_draft(household_user, on):
    request = member_request(household_user)
    d = drafter(request, Models([m.say("x")]))
    draft(d, "what is on")
    d.close()
    d.close()  # idempotent
    assert not AgentModelCall.objects.exists()
    assert LlmUsage.objects.filter(feature=preempt.FEATURE).count() == 1


def test_words_match_whatever_their_case_and_punctuation():
    assert preempt.normalise("What's ON,  today?") == preempt.normalise("what’s on today")
    assert preempt.normalise("on today") != preempt.normalise("on tomorrow")


# ---- through the streamed door ----------------------------------------------------------------


def _guessing(stream, guess, final=ASKED):
    stream.socket = FakeSocket(before_end=[_interim(guess)], after_end=[_final(final), DONE])


def test_the_streamed_door_answers_from_a_matching_draft(auth_client, stream, engine, on):
    engine.rounds = [m.say("Nothing is on today.")]
    _guessing(stream, "What is on the calendar today")
    events = _events(_post(auth_client, utterance()))
    sentences = [d["text"] for k, d in events if k == "sentence"]
    assert sentences[0] == "Nothing is on today."
    drafted, turned = engine.models
    assert len(drafted.requests) == 1 and turned.requests == []
    assert not AgentModelCall.objects.filter(node=preempt.NODE).exists()


def test_the_streamed_door_drops_a_draft_on_other_words(auth_client, stream, engine, on):
    engine.rounds = [m.say("Here is the answer.")]
    _guessing(stream, "What is on the calendar tomorrow")
    events = _events(_post(auth_client, utterance()))
    assert events[-1][0] == "done"
    drafted, turned = engine.models
    assert len(drafted.requests) == 1 and len(turned.requests) == 1
    assert AgentModelCall.objects.filter(node=preempt.NODE).count() == 1


def test_the_streamed_door_drafts_nothing_with_the_switch_off(
    auth_client, stream, engine, monkeypatch
):
    monkeypatch.delenv(PREEMPT_ENV, raising=False)
    engine.rounds = [m.say("Nothing is on today.")]
    _guessing(stream, "What is on the calendar today")
    _events(_post(auth_client, utterance()))
    assert len(engine.models) == 1
