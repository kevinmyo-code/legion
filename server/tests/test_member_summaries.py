"""A short summary of each conversation, written as it ends (issue #451).

Done when: within a minute of a chat ending, by hand or by timeout, the engine holds a short
summary of it. Tested with scripted sessions ended both ways, and one whose end never arrives.

The model is never reached: each test answers through `consolidate._transport`, the one call to
Google, and reads the prompt it was sent.
"""

from __future__ import annotations

import io
import json
from datetime import timedelta

import pytest
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from household.models import DeviceToken, HouseholdMember, User
from ingest.drive import Response
from member_memory import consolidate, summaries
from member_memory.models import ConversationSummary, ConversationTurn
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

TURNS = "/api/member-memories/turns"
END = "/api/member-memories/turns/end"
SUMMARIES = "/api/member-memories/summaries"

SUMMARY = "Kevin said his gym moved to Elm Street and asked for milk on the grocery list."


class FakeModel:
    """Answers every call with `summary` (or `status`) and keeps each prompt it was sent."""

    def __init__(self, summary=SUMMARY, status=200):
        self.summary = summary
        self.status = status
        self.prompts: list[str] = []

    def __call__(self, method, url, headers, body):
        self.prompts.append(json.loads(body)["contents"][0]["parts"][0]["text"])
        payload = {
            "candidates": [
                {"content": {"parts": [{"text": json.dumps({"summary": self.summary})}]}}
            ]
        }
        return Response(self.status, {}, json.dumps(payload).encode())


@pytest.fixture
def model(monkeypatch):
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key")
    fake = FakeModel()
    monkeypatch.setattr(consolidate, "_transport", fake)
    return fake


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _turn(client, turn=0, session="s1", **fields):
    body = {"session": session, "turn": turn, "user_text": "", "assistant_text": "", **fields}
    response = client.post(TURNS, body, format="json")
    assert response.status_code in (200, 201), response.data
    return response


def _chat(client, session="s1", spoken_at=None):
    at = spoken_at or timezone.now()
    _turn(
        client,
        0,
        session,
        user_text="My gym moved to Elm Street.",
        assistant_text="Noted, Elm Street.",
        spoken_at=at.isoformat(),
    )
    _turn(
        client,
        1,
        session,
        user_text="Add milk to the list.",
        assistant_text="Milk is on the grocery list, and the store closes at nine.",
        tools=["lists"],
        spoken_at=(at + timedelta(seconds=5)).isoformat(),
    )


def _end(client, session="s1", ended_by="closed"):
    response = client.post(END, {"session": session, "ended_by": ended_by}, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _listed(client, **params):
    response = client.get(SUMMARIES, params)
    assert response.status_code == 200, response.data
    return response.data["results"]


@pytest.mark.parametrize("ended_by", ["closed", "timeout", "dropped"])
def test_a_chat_ended_by_hand_or_by_timeout_is_summarized_before_the_answer(
    auth_client, model, ended_by
):
    _chat(auth_client)
    answer = _end(auth_client, ended_by=ended_by)
    assert answer["summarized"] is True and answer["reason"] is None
    assert answer["summary"]["text"] == SUMMARY
    assert answer["summary"]["ended_by"] == ended_by
    assert answer["summary"]["turns_read"] == 2
    # Stored by the time the end call answers: well inside a minute.
    assert [r["text"] for r in _listed(auth_client)] == [SUMMARY]


def test_the_model_sees_his_words_and_only_tool_free_replies(auth_client, model):
    _chat(auth_client)
    _end(auth_client)
    (prompt,) = model.prompts
    assert "User: My gym moved to Elm Street." in prompt
    assert "Assistant: Noted, Elm Street." in prompt
    assert "User: Add milk to the list." in prompt
    # A tool turn's reply may be quoting the tool: the same rule as consolidation.
    assert "store closes at nine" not in prompt


def test_a_chat_whose_end_never_arrived_is_summarized_by_the_hourly_job(
    auth_client, model, household_user
):
    _chat(auth_client, spoken_at=timezone.now() - timedelta(minutes=summaries.IDLE_MINUTES + 5))
    _chat(auth_client, session="live")  # still going: left alone
    out = io.StringIO()
    call_command("consolidate_memories", stdout=out)
    assert "Summarized 1 of 1 quiet conversations" in out.getvalue()
    row = ConversationSummary.objects.get()
    assert (row.session, row.ended_by, row.member_id) == ("s1", "idle", household_user.pk)


def test_ending_one_chat_also_summarizes_an_earlier_one_that_never_ended(auth_client, model):
    _chat(auth_client, "killed", spoken_at=timezone.now() - timedelta(minutes=30))
    _chat(auth_client, "s1")
    _end(auth_client, "s1")
    assert {(r["session"], r["ended_by"]) for r in _listed(auth_client)} == {
        ("s1", "closed"),
        ("killed", "idle"),
    }


def test_an_old_chat_outside_the_lookback_is_left_alone(auth_client, model):
    old = timezone.now() - timedelta(hours=summaries.SWEEP_LOOKBACK_HOURS + 1)
    _chat(auth_client, spoken_at=old)
    assert summaries.sweep(key="k").read == 0
    assert model.prompts == []


def test_a_chat_that_resumes_is_summarized_again_in_the_same_row(auth_client, model):
    _chat(auth_client)
    _end(auth_client)
    assert len(model.prompts) == 1
    # Nothing new: the stored summary stands and the model is not asked again.
    assert _end(auth_client)["summarized"] is True
    assert len(model.prompts) == 1
    _turn(auth_client, 2, user_text="Also eggs.", assistant_text="Eggs too.")
    model.summary = "A longer chat about the gym, milk and eggs."
    assert _end(auth_client)["summary"]["turns_read"] == 3
    assert [r["text"] for r in _listed(auth_client)] == [model.summary]
    assert ConversationSummary.objects.count() == 1


def test_no_key_writes_nothing_and_says_so(auth_client, monkeypatch):
    monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    _chat(auth_client)
    answer = _end(auth_client)
    assert (answer["summarized"], answer["reason"]) == (False, "no_key")
    assert "LEGION_GEMINI_KEY" in answer["detail"]
    assert ConversationSummary.objects.count() == 0


def test_a_failed_call_stores_nothing_and_the_sweep_tries_again(auth_client, model):
    _chat(auth_client, spoken_at=timezone.now() - timedelta(minutes=20))
    model.status = 500
    answer = _end(auth_client)
    assert (answer["summarized"], answer["reason"]) == (False, "failed")
    assert ConversationSummary.objects.count() == 0
    model.status = 200
    assert summaries.sweep(key="k").written == 1
    assert ConversationSummary.objects.get().ended_by == "idle"


def test_a_chat_with_none_of_his_words_is_not_summarized(auth_client, model):
    _turn(auth_client, 0, assistant_text="Good morning, sir.")
    answer = _end(auth_client)
    assert (answer["summarized"], answer["reason"]) == (False, "no_words")
    assert model.prompts == []


def test_an_end_with_no_saved_turns_says_so(auth_client, model):
    answer = _end(auth_client, "nothing-saved")
    assert (answer["summarized"], answer["reason"]) == (False, "no_turns")


def test_a_summary_that_looks_like_it_holds_a_vault_number_is_not_kept(auth_client, model):
    _chat(auth_client)
    model.summary = "Kevin said his SSN is 123-45-6789."
    answer = _end(auth_client)
    assert (answer["summarized"], answer["reason"]) == (False, "sensitive")
    assert ConversationSummary.objects.count() == 0


def test_forgetting_a_conversation_forgets_its_summary(auth_client, model):
    _chat(auth_client)
    _chat(auth_client, "keep")
    _end(auth_client)
    _end(auth_client, "keep")
    assert auth_client.delete(f"{TURNS}?session=s1").data == {"deleted": 2}
    assert [r["session"] for r in _listed(auth_client)] == ["keep"]


def test_a_crisis_phrase_forgets_the_summary_too(auth_client, model):
    _chat(auth_client)
    _end(auth_client)
    _turn(auth_client, 2, user_text="I want to die")
    assert _listed(auth_client) == []
    assert ConversationTurn.objects.count() == 0


def test_a_conversation_forgotten_while_the_model_reads_it_keeps_no_summary(
    auth_client, model, monkeypatch
):
    _chat(auth_client)

    def forget_then_answer(method, url, headers, body):
        ConversationTurn.objects.filter(session="s1").delete()
        return model(method, url, headers, body)

    monkeypatch.setattr(consolidate, "_transport", forget_then_answer)
    answer = _end(auth_client)
    assert (answer["summarized"], answer["reason"]) == (False, "forgotten")
    assert ConversationSummary.objects.count() == 0


def test_summaries_are_private_to_the_member_who_spoke(auth_client, mia_client, model):
    _chat(auth_client)
    _end(auth_client)
    assert _listed(mia_client) == []
    # Mia ending the same session id reads only her own turns.
    assert _end(mia_client)["reason"] == "no_turns"
    assert ConversationSummary.objects.count() == 1


def test_newest_first_and_limited(auth_client, model):
    now = timezone.now()
    for index, session in enumerate(["a", "b", "c"]):
        _chat(auth_client, session, spoken_at=now + timedelta(minutes=index))
        _end(auth_client, session)
    assert [r["session"] for r in _listed(auth_client)] == ["c", "b", "a"]
    assert [r["session"] for r in _listed(auth_client, limit=1)] == ["c"]


def test_a_bad_end_says_why(auth_client, model):
    response = auth_client.post(END, {"session": "s1", "ended_by": "idle"}, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was saved.")


def test_a_recruiter_is_refused_both_routes(db):
    from household.models import Household
    from household.permissions import DEMO_TOKEN_SESSION_KEY

    visit = Household.objects.create(name="Visit", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-y@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    token, _raw = DeviceToken.issue(
        visitor,
        "Demo visit",
        DeviceToken.SCOPE_READ,
        expires_at=timezone.now() + timedelta(hours=2),
    )
    client = APIClient()
    client.force_login(visitor)
    session = client.session
    session[DEMO_TOKEN_SESSION_KEY] = token.pk
    session.save()
    assert client.get(SUMMARIES).status_code == 403
    assert client.post(END, {"session": "s1"}, format="json").status_code == 403
