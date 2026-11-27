"""`/api/member-memories/turns`: each conversation turn, saved as it ends (issue #450).

Done when: a turn spoken on the phone is readable from the engine within seconds, tool turns
included, with read-through content still excluded. Tested by text-in turns with and without tool
calls.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from household.models import DeviceToken, HouseholdMember, User
from member_memory.models import ConversationTurn
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

URL = "/api/member-memories/turns"


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _turn(client, turn=0, session="s1", **fields):
    body = {"session": session, "turn": turn, "user_text": "", "assistant_text": "", **fields}
    return client.post(URL, body, format="json")


def _listed(client, **params) -> list[dict]:
    response = client.get(URL, params)
    assert response.status_code == 200, response.data
    return response.data["results"]


def test_a_plain_chat_turn_is_readable_right_away(auth_client):
    saved = _turn(
        auth_client,
        user_text="I start at Quanex on Monday",
        assistant_text="Good luck on Monday, sir.",
    )
    assert saved.status_code == 201, saved.data
    assert saved.data["kept"] is True
    rows = _listed(auth_client)
    assert [(r["user_text"], r["assistant_text"]) for r in rows] == [
        ("I start at Quanex on Monday", "Good luck on Monday, sir.")
    ]
    assert rows[0]["source"] == "phone"


def test_a_tool_turn_keeps_alfreds_spoken_words_and_only_the_tool_names(auth_client):
    saved = _turn(
        auth_client,
        user_text="add milk to the list",
        assistant_text="Milk is on the grocery list.",
        tools=["lists", "lists"],
    )
    assert saved.data["kept"] is True
    row = _listed(auth_client)[0]
    assert row["assistant_text"] == "Milk is on the grocery list."
    assert row["tools"] == ["lists"]
    # The request has no field for arguments or results, and an unknown one is not stored.
    assert set(row) == {
        "id",
        "session",
        "turn",
        "source",
        "user_text",
        "assistant_text",
        "tools",
        "spoken_at",
    }


@pytest.mark.parametrize(
    "fields",
    [
        {"read_through": True},
        {"tools": ["search_mail"]},
        {"tools": ["web_search"]},
        {"assistant_text": "[redacted: third-party content]"},
    ],
)
def test_a_read_through_turn_is_not_kept_not_even_his_words(auth_client, fields):
    body = {"user_text": "what did Sam email me", "assistant_text": "Sam says hello.", **fields}
    saved = _turn(auth_client, **body)
    assert saved.status_code == 200
    assert saved.data["kept"] is False
    assert saved.data["reason"] == "read_through"
    assert "third-party" in saved.data["detail"]
    assert ConversationTurn.objects.count() == 0


def test_a_crisis_phrase_deletes_the_whole_conversation(auth_client):
    assert _turn(auth_client, 0, user_text="rough day at work", assistant_text="I'm sorry.").data[
        "kept"
    ]
    assert _turn(auth_client, 1, user_text="I want to die", assistant_text="").data["kept"] is False
    assert _listed(auth_client) == []
    assert ConversationTurn.objects.count() == 0


def test_a_crisis_in_one_conversation_leaves_another_alone(auth_client):
    _turn(
        auth_client, 0, session="other", user_text="play some jazz", assistant_text="Playing jazz."
    )
    _turn(auth_client, 0, session="s1", user_text="I want to die")
    assert [r["session"] for r in _listed(auth_client)] == ["other"]


def test_a_turn_with_a_vault_number_is_not_kept(auth_client):
    saved = _turn(auth_client, user_text="my passport is E1234567", assistant_text="Noted.")
    assert saved.data["kept"] is False
    assert saved.data["reason"] == "sensitive"
    assert ConversationTurn.objects.count() == 0


def test_an_empty_turn_is_not_kept(auth_client):
    saved = _turn(auth_client, user_text="  ", assistant_text="")
    assert saved.data == {
        "kept": False,
        "reason": "empty",
        "detail": "Not kept: the turn had no words.",
        "turn": None,
    }


def test_a_retried_turn_rewrites_its_row_and_never_adds_one(auth_client):
    assert _turn(auth_client, 3, user_text="hi", assistant_text="Hello").status_code == 201
    again = _turn(auth_client, 3, user_text="hi", assistant_text="Hello, sir.")
    assert again.status_code == 200
    assert again.data["kept"] is True
    assert [r["assistant_text"] for r in _listed(auth_client)] == ["Hello, sir."]


def test_newest_first_and_filtered_by_session(auth_client):
    now = timezone.now()
    for turn, text in enumerate(["first", "second", "third"]):
        _turn(
            auth_client,
            turn,
            user_text=text,
            spoken_at=(now + timedelta(seconds=turn)).isoformat(),
        )
    _turn(auth_client, 0, session="s2", user_text="elsewhere", spoken_at=now.isoformat())
    assert [r["user_text"] for r in _listed(auth_client, session="s1")] == [
        "third",
        "second",
        "first",
    ]
    assert [r["user_text"] for r in _listed(auth_client, limit=1)] == ["third"]


def test_turns_are_private_to_the_member_who_spoke_them(auth_client, mia_client):
    _turn(auth_client, user_text="my gym is on Main Street")
    assert _listed(mia_client) == []
    # Mia forgetting the same session id touches only her own turns.
    assert mia_client.delete(f"{URL}?session=s1").data == {"deleted": 0}
    assert len(_listed(auth_client)) == 1


def test_forgetting_a_conversation_is_a_hard_delete(auth_client):
    _turn(auth_client, 0, user_text="one")
    _turn(auth_client, 1, user_text="two")
    _turn(auth_client, 0, session="keep", user_text="three")
    assert auth_client.delete(f"{URL}?session=s1").data == {"deleted": 2}
    assert ConversationTurn.objects.filter(session="s1").count() == 0
    assert [r["user_text"] for r in _listed(auth_client)] == ["three"]
    assert auth_client.delete(URL).status_code == 400


def test_a_bad_request_saves_nothing_and_says_why(auth_client):
    response = auth_client.post(URL, {"session": "s1", "turn": -1}, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was saved.")
    response = auth_client.post(
        URL, {"session": "s1", "turn": 0, "source": "web", "user_text": "x"}, format="json"
    )
    assert response.status_code == 400
    assert ConversationTurn.objects.count() == 0


def test_a_long_turn_keeps_its_end(auth_client):
    text = "a " * 3000 + "the end"
    _turn(auth_client, user_text=text)
    stored = _listed(auth_client)[0]["user_text"]
    assert len(stored) == 4000
    assert stored.endswith("the end")


def test_a_recruiter_is_refused_every_turn_route(db):
    from household.models import Household
    from household.permissions import DEMO_TOKEN_SESSION_KEY

    visit = Household.objects.create(name="Visit", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-x@demo.invalid")
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
    for method, path in (("get", URL), ("post", URL), ("delete", f"{URL}?session=s1")):
        response = getattr(client, method)(path, {"session": "s1", "turn": 0}, format="json")
        assert response.status_code == 403, (method, response.status_code)
    assert ConversationTurn.objects.count() == 0
