"""Control room C2 (`board/asks.py`, `board/answer_views.py`), DARK behind `LEGION_BOARD`.

Proved from the outside: an answer is taken only for the question the phone showed (409 when it
changed or already has an answer, 410 when the card left or stopped asking); answers are
append-only; a question or a typed answer about a deploy, a publish, a flag or a spend is refused
and never answerable; the laptop pulls answers and votes with its writer token only; votes sort the
whole backlog by his priority first, then RICE; and the hash matches the laptop's fixed vector.
Every card, idea and name is invented.
"""

from __future__ import annotations

import json

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient

from board import asks
from board.flags import BOARD_ENV
from board.models import BoardAnswer, BoardVote
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

BOARD = "/api/board"
WRITER = "/api/board/writer"
ANSWERS = "/api/board/answers"
VOTES = "/api/board/votes"
INBOX = "/api/board/inbox"
DELIVERED = "/api/board/inbox/delivered"


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(BOARD_ENV, "on")


def _card(card_id, col="needs_kevin", **extra):
    return {"id": card_id, "col": col, "kind": "agent", "title": card_id.title(), **extra}


TEAPOT = _card("teapot", ask="Blue or green teapot?", options=["blue", "green"], ask_kind="choice")
KETTLE_SHIP = _card(
    "kettle", ask="Kettle firmware is green. Ship it?", options=["yes", "no"], ask_kind="choice"
)
APPROVAL = _card("toaster", ask="Toaster upgrade?", options=["go", "wait"], ask_kind="approval")
SILENT = _card("scones", col="working")


def _board(*cards, backlog=None, backlog_total=None):
    body = {"generated": "2031-04-02T10:00:00+00:00", "cards": list(cards)}
    if backlog is not None:
        body["backlog"] = backlog
    if backlog_total is not None:
        body["backlog_total"] = backlog_total
    return body


def _writer(client) -> APIClient:
    response = client.post(WRITER)
    assert response.status_code == 201, response.content
    laptop = APIClient()
    laptop.credentials(HTTP_AUTHORIZATION=f"Token {response.json()['token']}")
    return laptop


def _push(laptop, body):
    response = laptop.put(BOARD, data=json.dumps(body), content_type="application/json")
    assert response.status_code == 200, response.content
    return response


def _shown(client, card_id) -> dict:
    cards = client.get(BOARD).json()["snapshot"]["cards"]
    return next(c for c in cards if c["id"] == card_id)


def _answer(client, card_id, ask_sha256=None, **body):
    sha = ask_sha256 if ask_sha256 is not None else _shown(client, card_id)["ask_sha256"]
    return client.post(ANSWERS, {"card_id": card_id, "ask_sha256": sha, **body}, format="json")


# ---- the switch --------------------------------------------------------------------------------


def test_off_every_new_route_is_404(auth_client):
    for path in (ANSWERS, VOTES, DELIVERED):
        assert auth_client.post(path, {}, format="json").status_code == 404
    assert auth_client.get(INBOX).status_code == 404


# ---- the hash ----------------------------------------------------------------------------------


def test_ask_hash_matches_the_laptops_fixed_vector():
    """The same vector is pinned in `.legion/board/test_controlroom_sync.py`; change both or
    neither."""
    card = {
        "id": "tea-order",
        "title": "Pick a teapot",
        "ask": "Blue or green?",
        "options": ["blue", "green"],
        "ask_kind": "choice",
        "free_text": True,
    }
    assert asks.ask_hash(card) == FIXED_VECTOR
    # Any change to what he was asked is a different hash.
    assert asks.ask_hash({**card, "options": ["blue", "green", "red"]}) != FIXED_VECTOR


FIXED_VECTOR = "74ee0dab0d11825939eb5f83eaab9aa6c15f40ceb69f07f7ac642a6492f70a02"


# ---- answers -----------------------------------------------------------------------------------


def test_a_shown_question_carries_its_hash_and_is_answerable(on, auth_client):
    _push(_writer(auth_client), _board(TEAPOT, SILENT))
    teapot = _shown(auth_client, "teapot")
    assert len(teapot["ask_sha256"]) == 64 and teapot["answerable"] is True
    silent = _shown(auth_client, "scones")
    assert silent["ask_sha256"] == "" and silent["answerable"] is False


def test_an_answer_is_taken_and_reaches_the_laptops_inbox(on, auth_client):
    laptop = _writer(auth_client)
    _push(laptop, _board(TEAPOT))
    response = _answer(auth_client, "teapot", choice="green")
    assert response.status_code == 201, response.content
    assert _shown(auth_client, "teapot")["answer_pending"] is True
    inbox = laptop.get(INBOX).json()
    assert [(a["card_id"], a["choice"]) for a in inbox["answers"]] == [("teapot", "green")]
    done = laptop.post(
        DELIVERED,
        {"answers": [{"id": inbox["answers"][0]["id"], "outcome": "applied"}]},
        format="json",
    )
    assert done.json() == {"answers": 1, "votes": 0}
    assert laptop.get(INBOX).json()["answers"] == []
    assert BoardAnswer.objects.get().outcome == "applied"


def test_a_changed_question_is_409(on, auth_client):
    laptop = _writer(auth_client)
    _push(laptop, _board(TEAPOT))
    old = _shown(auth_client, "teapot")["ask_sha256"]
    _push(laptop, _board({**TEAPOT, "options": ["blue", "green", "red"]}))
    response = _answer(auth_client, "teapot", ask_sha256=old, choice="green")
    assert response.status_code == 409
    assert "changed" in response.json()["detail"]
    assert not BoardAnswer.objects.exists()


def test_a_second_answer_to_the_same_question_is_409(on, auth_client):
    _push(_writer(auth_client), _board(TEAPOT))
    assert _answer(auth_client, "teapot", choice="green").status_code == 201
    again = _answer(auth_client, "teapot", choice="blue")
    assert again.status_code == 409
    assert BoardAnswer.objects.count() == 1


def test_a_card_that_left_or_stopped_asking_is_410(on, auth_client):
    laptop = _writer(auth_client)
    _push(laptop, _board(TEAPOT))
    sha = _shown(auth_client, "teapot")["ask_sha256"]
    _push(laptop, _board({**TEAPOT, "ask": "", "options": []}))
    assert _answer(auth_client, "teapot", ask_sha256=sha, choice="green").status_code == 410
    _push(laptop, _board(SILENT))
    assert _answer(auth_client, "teapot", ask_sha256=sha, choice="green").status_code == 410


def test_no_board_yet_is_410(on, auth_client):
    response = _answer(auth_client, "teapot", ask_sha256="a" * 64, choice="green")
    assert response.status_code == 410


@pytest.mark.parametrize("card", [KETTLE_SHIP, APPROVAL])
def test_a_deploy_question_or_an_approval_is_never_answerable_here(on, auth_client, card):
    _push(_writer(auth_client), _board(card))
    shown = _shown(auth_client, card["id"])
    assert shown["answerable"] is False
    response = _answer(auth_client, card["id"], choice=card["options"][0])
    assert response.status_code == 400
    assert "answer that one in chat" in response.json()["detail"]
    assert not BoardAnswer.objects.exists()


@pytest.mark.parametrize(
    "words", ["deploy it", "please publish", "turn the flag on", "spend $20", "merge and ship"]
)
def test_a_typed_answer_with_an_action_word_is_refused(on, auth_client, words):
    _push(_writer(auth_client), _board(TEAPOT))
    response = _answer(auth_client, "teapot", text=words)
    assert response.status_code == 400
    assert not BoardAnswer.objects.exists()


def test_an_answer_must_be_one_of_the_choices(on, auth_client):
    _push(_writer(auth_client), _board({**TEAPOT, "free_text": False}))
    assert _answer(auth_client, "teapot", choice="purple").status_code == 400
    assert _answer(auth_client, "teapot", text="purple please").status_code == 400
    assert _answer(auth_client, "teapot").status_code == 400


def test_answers_are_append_only():
    from household.models import Household

    household = Household.objects.first() or Household.objects.create(name="Tea house")
    user = _member(household, "append@example.com")
    row = BoardAnswer.objects.create(
        household=household, owner_user=user, card_id="teapot", ask_sha256="b" * 64, choice="blue"
    )
    row.choice = "green"
    with pytest.raises(ValueError):
        row.save()
    with pytest.raises(ValueError):
        row.save(update_fields=["choice"])
    with pytest.raises(ValueError):
        row.delete()
    row.outcome = "applied"
    row.save(update_fields=["outcome"])
    assert BoardAnswer.objects.get().choice == "blue"


def test_the_inbox_is_the_writers_alone(on, auth_client):
    _writer(auth_client)
    assert auth_client.get(INBOX).status_code == 403
    assert auth_client.post(DELIVERED, {}, format="json").status_code == 403


def test_a_housemates_answers_never_reach_my_laptop(on, auth_client, household_a):
    laptop = _writer(auth_client)
    _push(laptop, _board(TEAPOT))
    other = _client_for(_member(household_a, "housemate@example.com"))
    _push(_writer(other), _board(TEAPOT))
    assert _answer(other, "teapot", choice="blue").status_code == 201
    assert laptop.get(INBOX).json()["answers"] == []


# ---- backlog and votes -------------------------------------------------------------------------

RICE_HI = {"r": "everything", "i": "high", "c": "high", "e": "hours", "score": 90.0}
RICE_LO = {"r": "one_surface", "i": "low", "c": "low", "e": "week", "score": 3.0}


def _ideas(n):
    return [
        {"id": f"idea-{i:010x}", "title": f"Idea {i}", "rice": RICE_LO, "blast": "GREEN"}
        for i in range(n)
    ]


def test_the_whole_backlog_rides_beside_the_cards(on, auth_client):
    backlog = _ideas(40)
    _push(_writer(auth_client), _board(SILENT, backlog=backlog, backlog_total=55))
    snap = auth_client.get(BOARD).json()["snapshot"]
    assert len(snap["backlog"]) == 40 and snap["backlog_total"] == 55
    assert {"id", "title", "rice", "blast", "age_min", "kevin_vote"} <= set(snap["backlog"][0])


def test_a_bad_backlog_item_is_skipped_not_refused(on, auth_client):
    backlog = [{"id": "BAD ID"}, "nope", {"id": "idea-00000000aa", "title": "bw1_" + "x" * 30}]
    _push(_writer(auth_client), _board(SILENT, backlog=backlog))
    snap = auth_client.get(BOARD).json()["snapshot"]
    assert [i["id"] for i in snap["backlog"]] == ["idea-00000000aa"]
    assert snap["backlog"][0]["title"] == "[removed]"


def test_his_priority_sorts_above_rice(on, auth_client):
    low, high, other = (
        {"id": "idea-000000000a", "title": "Low", "rice": RICE_LO},
        {"id": "idea-000000000b", "title": "High", "rice": RICE_HI},
        {"id": "idea-000000000c", "title": "Down", "rice": RICE_HI},
    )
    _push(_writer(auth_client), _board(SILENT, backlog=[low, high, other]))
    order = [i["title"] for i in auth_client.get(BOARD).json()["snapshot"]["backlog"]]
    assert order == ["High", "Down", "Low"]
    assert (
        auth_client.post(VOTES, {"card_id": low["id"], "vote": "p2"}, format="json").status_code
        == 201
    )
    assert (
        auth_client.post(VOTES, {"card_id": other["id"], "vote": "down"}, format="json").status_code
        == 201
    )
    backlog = auth_client.get(BOARD).json()["snapshot"]["backlog"]
    assert [i["title"] for i in backlog] == ["Low", "High", "Down"]
    assert backlog[0]["kevin_vote"] == "p2"
    auth_client.post(VOTES, {"card_id": low["id"], "vote": "clear"}, format="json")
    order = [i["title"] for i in auth_client.get(BOARD).json()["snapshot"]["backlog"]]
    assert order == ["High", "Low", "Down"]


def test_a_vote_on_something_not_in_the_backlog_is_refused(on, auth_client):
    _push(_writer(auth_client), _board(TEAPOT))
    response = auth_client.post(VOTES, {"card_id": "teapot", "vote": "p1"}, format="json")
    assert response.status_code == 410
    bad = auth_client.post(VOTES, {"card_id": "teapot", "vote": "p9"}, format="json")
    assert bad.status_code == 400


def test_the_laptop_takes_the_newest_vote_per_idea(on, auth_client):
    laptop = _writer(auth_client)
    idea = _ideas(1)[0]
    _push(laptop, _board(SILENT, backlog=[idea]))
    for vote in ("up", "p3", "p1"):
        auth_client.post(VOTES, {"card_id": idea["id"], "vote": vote}, format="json")
    votes = laptop.get(INBOX).json()["votes"]
    assert [(v["card_id"], v["vote"]) for v in votes] == [(idea["id"], "p1")]
    laptop.post(DELIVERED, {"votes": [votes[0]["id"]]}, format="json")
    assert laptop.get(INBOX).json()["votes"] == []
    assert BoardVote.objects.filter(delivered_at__isnull=True).count() == 0
