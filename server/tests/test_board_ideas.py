"""Backlog intake (`board/ideas.py`, `board/idea_views.py`, `add_backlog_idea`), DARK.

Kevin, 2026-11-22: "yes score each idea" and "read it back first". Proved from the outside: the
idea is scored and read back by code and stored only as a draft; it is saved only on a later yes
(never on silence or a no, never within the same breath, never after the draft went stale); only
saved ideas reach the laptop, through the writer token, which reaches nothing else new; card
fields round-trip; `/mcp` never offers the tool; and "where are we" is short and code-spoken.
Every idea, card and name is invented.
"""

from __future__ import annotations

import json
from datetime import timedelta

import pytest
from django.core.cache import cache
from django.test.client import RequestFactory
from django.utils import timezone
from rest_framework.request import Request
from rest_framework.test import APIClient

from assistant.surface import tools_for
from board import cards as board_cards
from board import ideas, rubric
from board.flags import BOARD_ENV
from board.models import IDEA_DELIVERED, IDEA_DRAFT, IDEA_DROPPED, IDEA_PENDING, BoardIdea
from board.tool import add_backlog_idea
from engine_mcp.server import LATER_YES_ONLY_TOOLS, _tools_for_request
from engine_mcp.tools import ENDS_TURN, SPEAK_VERBATIM
from ingest.drive import Response
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

IDEAS = "/api/board/ideas"
PENDING = "/api/board/ideas/pending"
BOARD = "/api/board"
WRITER = "/api/board/writer"


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(BOARD_ENV, "on")


@pytest.fixture
def scorer(monkeypatch):
    """The scoring call answers with a fixed rubric pick."""
    body = {
        "reach": "one_surface",
        "impact": "medium",
        "confidence": "high",
        "effort": "day",
        "areas": ["phone_screens"],
    }
    payload = {"candidates": [{"content": {"parts": [{"text": json.dumps(body)}]}}]}
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key")
    monkeypatch.setattr(
        rubric, "_transport", lambda *a: Response(200, {}, json.dumps(payload).encode())
    )


def _later(idea, seconds=ideas.MIN_CONFIRM_SECONDS + 1):
    BoardIdea.objects.filter(pk=idea["id"]).update(
        created_at=timezone.now() - timedelta(seconds=seconds)
    )


def _draft(client, text="A button that hides the clock"):
    response = client.post(IDEAS, {"text": text}, format="json")
    assert response.status_code == 201, response.content
    return response.json()


def _as(raw: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


def _request(user):
    django_request = RequestFactory().post("/api/assistant/turn")
    django_request.user = user
    request = Request(django_request)
    request.user = user
    return request


# ---- the switch ---------------------------------------------------------------------------------


def test_off_every_idea_route_is_404_and_the_tool_is_in_no_list(auth_client, household_user):
    assert auth_client.post(IDEAS, {"text": "x"}, format="json").status_code == 404
    assert auth_client.get(PENDING).status_code == 404
    assert "add_backlog_idea" not in {t.name for t in tools_for(household_user)}
    assert add_backlog_idea(_request(household_user), {"text": "x"}).is_error
    assert BoardIdea.objects.count() == 0


def test_mcp_never_offers_or_runs_it(on, household_user):
    assert "add_backlog_idea" in LATER_YES_ONLY_TOOLS
    assert "add_backlog_idea" in {t.name for t in tools_for(household_user)}
    assert "add_backlog_idea" not in {t.name for t in _tools_for_request(_request(household_user))}


# ---- draft, read back, yes ----------------------------------------------------------------------


def test_draft_is_scored_read_back_by_code_and_not_pending(on, scorer, auth_client):
    body = _draft(auth_client)
    assert body["scored"] is True and body["blast"] == "YELLOW"
    assert body["rice"]["score"] == 3.0  # 3 x 1 x 1 / 1
    assert body["readback"] == (
        "Backlog idea: A button that hides the clock. Scored 3.0, medium impact, about a day, "
        "yellow blast forecast. Shall I add it to the backlog?"
    )
    assert body["card_id"].startswith("idea-") and "clock" not in body["card_id"]
    assert BoardIdea.objects.get().status == IDEA_DRAFT


def test_a_failed_score_still_drafts_marked_unscored(on, auth_client):
    body = _draft(auth_client)
    assert body["scored"] is False and body["rice"] is None
    assert "Not scored yet" in body["readback"]


def test_yes_saves_the_draft_he_heard_and_only_that(on, scorer, auth_client):
    body = _draft(auth_client)
    _later(body)
    response = auth_client.post(f"{IDEAS}/{body['id']}/confirm")
    assert response.status_code == 200
    assert response.json()["words"] == ideas.SAVED
    idea = BoardIdea.objects.get()
    assert idea.status == IDEA_PENDING and idea.scores["rice"]["score"] == 3.0
    # A second yes saves nothing more.
    assert auth_client.post(f"{IDEAS}/{body['id']}/confirm").status_code == 400


def test_a_yes_in_the_same_breath_is_refused(on, scorer, auth_client):
    body = _draft(auth_client)
    response = auth_client.post(f"{IDEAS}/{body['id']}/confirm")
    assert response.status_code == 400 and "read back" in response.json()["detail"]
    assert BoardIdea.objects.get().status == IDEA_DRAFT


def test_silence_saves_nothing_and_a_stale_draft_cannot_be_said_yes_to(on, scorer, auth_client):
    body = _draft(auth_client)
    _later(body, seconds=int(ideas.DRAFT_TTL.total_seconds()) + 5)
    assert auth_client.post(f"{IDEAS}/{body['id']}/confirm").status_code == 400
    assert BoardIdea.objects.get().status == IDEA_DROPPED


def test_no_drops_it(on, scorer, auth_client):
    body = _draft(auth_client)
    response = auth_client.delete(f"{IDEAS}/{body['id']}")
    assert response.status_code == 200 and response.json()["words"] == ideas.DROPPED
    _later(body)
    assert auth_client.post(f"{IDEAS}/{body['id']}/confirm").status_code == 400


def test_a_new_draft_drops_the_unanswered_one(on, scorer, auth_client):
    first = _draft(auth_client, "First idea")
    _draft(auth_client, "Second idea")
    _later(first)
    assert auth_client.post(f"{IDEAS}/{first['id']}/confirm").status_code == 400


def test_another_member_cannot_confirm_my_draft(on, scorer, auth_client, household_a):
    body = _draft(auth_client)
    _later(body)
    other = _client_for(_member(household_a, "other@example.com"))
    assert other.post(f"{IDEAS}/{body['id']}/confirm").status_code == 400
    assert BoardIdea.objects.get().status == IDEA_DRAFT


@pytest.mark.parametrize(
    "text",
    ["", "   ", "my key is ghp_abcdefghijklmnopqrstuvwxyz0123", "mail me at someone@example.com"],
)
def test_empty_or_secret_shaped_text_is_refused(on, auth_client, text):
    response = auth_client.post(IDEAS, {"text": text}, format="json")
    assert response.status_code == 400
    assert BoardIdea.objects.count() == 0


def test_text_is_cleaned_and_capped(on, auth_client):
    body = _draft(auth_client, "Make​ the   orb " + "bigger " * 80)
    idea = BoardIdea.objects.get(pk=body["id"])
    assert "​" not in idea.text and "  " not in idea.text
    assert len(idea.text) <= board_cards.FIELD_MAX and len(idea.title) <= ideas.TITLE_MAX + 3


def test_twenty_drafts_a_day(on, auth_client, household_user):
    for i in range(ideas.DRAFTS_PER_DAY):
        BoardIdea.objects.create(
            household=household_user.household_member.household,
            owner_user=household_user,
            title=f"t{i}",
            text=f"t{i}",
            status=IDEA_DROPPED,
        )
    response = auth_client.post(IDEAS, {"text": "one more"}, format="json")
    # The engine's own count, not the throttle (which allows 20 POSTs and none were made).
    assert response.status_code == 400
    assert response.json()["detail"] == ideas.TOO_MANY


# ---- the tool -----------------------------------------------------------------------------------


def _on_turn(user, number, words, conversation="c-1"):
    request = _request(user)
    ideas.set_turn(request, conversation, number, words)
    return request


def _aged(idea_id):
    BoardIdea.objects.filter(pk=idea_id).update(created_at=timezone.now() - timedelta(seconds=30))


def test_tool_reads_back_by_code_and_ends_the_turn(on, scorer, household_user):
    result = add_backlog_idea(_on_turn(household_user, 1, "add it"), {"text": "Sort the garage"})
    assert not result.is_error
    said = result.structured[SPEAK_VERBATIM]
    assert said[0].startswith("Backlog idea: Sort the garage. Scored")
    assert result.structured[ENDS_TURN] is True
    idea_id = result.structured["id"]
    idea = BoardIdea.objects.get(pk=idea_id)
    assert idea.status == IDEA_DRAFT and (idea.draft_conversation, idea.draft_turn) == ("c-1", 1)
    _aged(idea_id)
    # The same turn, even after the time floor: refused, nothing saved.
    again = add_backlog_idea(_on_turn(household_user, 1, "yes"), {"id": idea_id, "confirm": True})
    assert again.is_error and again.text == ideas.SAME_TURN
    assert BoardIdea.objects.get(pk=idea_id).status == IDEA_DRAFT
    saved = add_backlog_idea(_on_turn(household_user, 2, "Yes"), {"id": idea_id, "confirm": True})
    assert saved.structured[SPEAK_VERBATIM] == [ideas.SAVED]
    assert BoardIdea.objects.get(pk=idea_id).status == IDEA_PENDING


@pytest.mark.parametrize("words", ["hmm, not sure", "wait", "", "what score was that?"])
def test_the_next_turn_must_be_a_yes_in_his_words_not_the_models(on, scorer, household_user, words):
    result = add_backlog_idea(_on_turn(household_user, 1, "add it"), {"text": "An idea"})
    _aged(result.structured["id"])
    later = add_backlog_idea(
        _on_turn(household_user, 2, words), {"id": result.structured["id"], "confirm": True}
    )
    assert later.is_error and later.text == ideas.NOT_A_YES
    assert BoardIdea.objects.get().status == IDEA_DRAFT


def test_a_door_that_cannot_tell_turns_apart_never_confirms(on, scorer, household_user):
    """Live and the web tool door: no turn here, so a confirm is refused in words, whatever the
    model says; a draft still reads back. The tool door's own `set_turn` (no carry) is no turn."""
    from assistant import read_through

    request = _request(household_user)
    read_through.set_turn(request, "00000000-0000-0000-0000-000000000001", 3)
    result = add_backlog_idea(request, {"text": "An idea"})
    assert not result.is_error
    _aged(result.structured["id"])
    later = _request(household_user)
    read_through.set_turn(later, "00000000-0000-0000-0000-000000000001", 9)
    refused = add_backlog_idea(later, {"id": result.structured["id"], "confirm": True})
    assert refused.is_error and refused.text == ideas.NO_TURN_DOOR
    assert "phone or in the web chat" in refused.text
    assert BoardIdea.objects.get().status == IDEA_DRAFT


def test_a_yes_in_another_conversation_saves_nothing(on, scorer, household_user):
    result = add_backlog_idea(_on_turn(household_user, 1, "add it"), {"text": "An idea"})
    _aged(result.structured["id"])
    other = _on_turn(household_user, 2, "yes", conversation="c-2")
    refused = add_backlog_idea(other, {"id": result.structured["id"], "confirm": True})
    assert refused.is_error and refused.text == ideas.OTHER_CONVERSATION


def test_only_the_newest_of_two_drafts_is_confirmable(on, scorer, auth_client, household_user):
    """Two drafts at once (a race past the drop): only the newest can be said yes to."""
    first = _draft(auth_client, "First")
    BoardIdea.objects.create(
        household=household_user.household_member.household,
        owner_user=household_user,
        title="Second",
        text="Second",
        status=IDEA_DRAFT,
    )
    _later(first)
    response = auth_client.post(f"{IDEAS}/{first['id']}/confirm")
    assert response.status_code == 400 and response.json()["detail"] == ideas.SUPERSEDED


def test_tool_no_drops_and_nothing_without_words(on, scorer, household_user):
    result = add_backlog_idea(_request(household_user), {"text": "Idea"})
    # A no needs no turn: dropping saves nothing, on any door.
    dropped = add_backlog_idea(
        _request(household_user), {"id": result.structured["id"], "confirm": False}
    )
    assert dropped.structured[SPEAK_VERBATIM] == [ideas.DROPPED]
    assert add_backlog_idea(_request(household_user), {}).is_error


def test_the_read_back_ends_the_turn_on_the_engine_doors_and_asks_one_yes_only():
    """The read-back is spoken by code and ends the turn (cascade and engine brain), so his yes is
    his next turn; the brain's confirm hold is NOT also applied, or he would be asked twice."""
    from agents.legion.guards import CONFIRM_FLAG_TOOLS
    from agents.legion.speech_rules import VERBATIM_TOOLS

    assert "add_backlog_idea" in VERBATIM_TOOLS and "add_backlog_idea" not in CONFIRM_FLAG_TOOLS


def test_a_door_that_cannot_speak_is_told_to_read_it_back_not_that_it_was_said(
    on, scorer, household_user
):
    from engine_mcp.tools import FOR_MODEL

    result = add_backlog_idea(_request(household_user), {"text": "Sort the garage list"})
    assert "Said to him" not in result.text and "Say this to him" in result.text
    assert "said to him word for word" in result.structured[FOR_MODEL]


# ---- the laptop's pull --------------------------------------------------------------------------


def _writer(client) -> str:
    response = client.post(WRITER)
    assert response.status_code == 201
    return response.json()["token"]


def test_only_saved_ideas_reach_the_laptop_and_delivery_is_once(on, scorer, auth_client):
    raw = _writer(auth_client)
    unsaid = _draft(auth_client, "Never said yes")
    assert _as(raw).get(PENDING).json() == {"ideas": []}
    saved = _draft(auth_client, "Said yes")
    _later(saved)
    auth_client.post(f"{IDEAS}/{saved['id']}/confirm")
    pulled = _as(raw).get(PENDING).json()["ideas"]
    assert [i["id"] for i in pulled] == [saved["id"]]
    assert pulled[0]["card_id"] == saved["card_id"] and pulled[0]["rice"]["score"] == 3.0
    assert unsaid["id"] not in json.dumps(pulled)
    assert _as(raw).post(f"{IDEAS}/{saved['id']}/delivered").status_code == 200
    assert BoardIdea.objects.get(pk=saved["id"]).status == IDEA_DELIVERED
    assert _as(raw).get(PENDING).json() == {"ideas": []}


def test_writer_token_reaches_only_its_methods(on, auth_client):
    raw = _writer(auth_client)
    assert _as(raw).post(PENDING).status_code in (403, 405)
    assert _as(raw).post(IDEAS, {"text": "x"}, format="json").status_code == 401
    assert _as(raw).get(BOARD).status_code == 403
    assert _as(raw).get("/api/auth/me").status_code == 401


def test_a_member_sign_in_cannot_pull(on, auth_client):
    assert auth_client.get(PENDING).status_code in (401, 403)


def test_recruiter_cannot_add(on):
    import uuid

    from household.models import DeviceToken, Household, HouseholdMember, User

    visit = Household.objects.create(name="Invented visit", is_demo_visit=True)
    visitor = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:8]}@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    _token, key = DeviceToken.issue(
        visitor,
        "Demo visit",
        DeviceToken.SCOPE_WRITE,
        expires_at=timezone.now() + timedelta(hours=2),
    )
    response = _as(key).post(IDEAS, {"text": "x"}, format="json")
    assert response.status_code == 403
    assert BoardIdea.objects.count() == 0


# ---- card fields --------------------------------------------------------------------------------


def test_idea_cards_keep_kind_rice_blast_and_scored_by():
    clean = board_cards.clean_board(
        {
            "cards": [
                {
                    "id": "idea-0a1b2c3d4e",
                    "col": "queued",
                    "kind": "idea",
                    "title": "An idea",
                    "rice": {"r": "one_surface", "i": "high", "c": "low", "e": "day", "score": 3},
                    "blast": "RED",
                    "scored_by": "rubric-v1 (a model)",
                },
                {
                    "id": "bad-rice",
                    "col": "queued",
                    "title": "Bad",
                    "rice": {"r": "galaxy", "i": "high", "c": "low", "e": "day", "score": 3},
                    "blast": "PURPLE",
                },
            ]
        }
    )
    idea, bad = clean.cards
    assert idea["kind"] == "idea" and idea["blast"] == "RED"
    assert idea["rice"] == {"r": "one_surface", "i": "high", "c": "low", "e": "day", "score": 3.0}
    assert idea["scored_by"] == "rubric-v1 (a model)"
    assert bad["rice"] is None and bad["blast"] == ""


# ---- where are we -------------------------------------------------------------------------------


def _card(card_id, col, title, **extra):
    return {"id": card_id, "col": col, "kind": "agent", "title": title, "age_min": 1, **extra}


def test_brief_is_short_code_spoken_and_the_model_gets_counts_only(on, scorer, auth_client):
    raw = _writer(auth_client)
    board = {
        "cards": [
            _card("a", "needs_kevin", "Pick a teapot", ask="Blue or green?"),
            _card("b", "needs_kevin", "Approve the kettle"),
            _card("c", "needs_kevin", "Third question"),
            _card("d", "working", "Scone recipe"),
            _card("e", "testing", "Jam labels"),
            _card("f", "done", "Bread proofed"),
            _card("g", "queued", "Someday"),
        ]
    }
    pushed = _as(raw).put(BOARD, data=json.dumps(board), content_type="application/json")
    assert pushed.status_code == 200
    saved = _draft(auth_client, "Waiting idea")
    _later(saved)
    auth_client.post(f"{IDEAS}/{saved['id']}/confirm")
    body = auth_client.get(BOARD).json()
    brief = body["brief"]
    assert brief[1] == "2 in flight, 3 waiting on you, 1 shipped today."
    assert brief[2] == "Waiting on you: Pick a teapot and Approve the kettle."
    assert "Third question" not in " ".join(brief)
    assert brief[3] == "Working on Scone recipe and Jam labels; done today, Bread proofed."
    assert brief[4] == "1 idea is not on the laptop yet."
    assert len(brief) <= 5 and len(" ".join(brief).split()) <= 70
    assert body["pending_ideas"] == 1
    for words in ("teapot", "kettle", "Scone", "Jam", "Bread"):
        assert words not in body["brief_for_model"]
    assert "3 waiting on you" in body["brief_for_model"]


def test_brief_with_no_board_says_so(on, auth_client):
    body = auth_client.get(BOARD).json()
    assert body["brief"] and "never sent" in body["brief"][0]


# ---- the web cascade door, end to end -----------------------------------------------------------


@pytest.fixture
def cascade_engine(monkeypatch):
    from agents.runtime.model import FakeModel, say
    from assistant import views
    from tests.test_assistant_cascade import FakeSpeech

    class Engine:
        model = FakeModel([say("Hello.")])
        speech = FakeSpeech()

    monkeypatch.setattr(views, "_cascade_clients", lambda: (Engine.model, Engine.speech))
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key")
    return Engine


def _web_turn(client, text, conversation=None):
    from tests.test_assistant_cascade import _events, _of, _post

    fields = {"text": text}
    if conversation:
        fields["conversation_id"] = conversation
    events = _events(_post(client, **fields))
    return events, _of(events, "turn")[0]["conversation_id"]


def _fixed_idea_ids(monkeypatch, fixed: str) -> None:
    """New ideas get `fixed` as their id, so a fake model's later call can name it. Django caches a
    field's default as `_get_default`, so that is what is replaced."""
    import uuid

    field = BoardIdea._meta.get_field("id")
    monkeypatch.setattr(field, "_get_default", lambda: uuid.UUID(fixed), raising=False)


def test_web_cascade_saves_only_on_his_yes_next_turn(
    on, scorer, household_user, cascade_engine, monkeypatch
):
    from agents.runtime.model import FakeModel, call, say
    from tests.test_assistant import browser
    from tests.test_assistant_cascade import _of

    web = browser(household_user)
    fixed = "11111111-2222-4333-8444-555555555555"
    _fixed_idea_ids(monkeypatch, fixed)
    # Draft and confirm in ONE turn: the read-back ends the turn; nothing is saved.
    cascade_engine.model = FakeModel(
        [
            call("add_backlog_idea", {"text": "A quieter chime"}),
            call("add_backlog_idea", {"id": fixed, "confirm": True}),
            say("Saved."),
        ]
    )
    events, conversation = _web_turn(web, "Add a quieter chime to the backlog.")
    assert BoardIdea.objects.get().status == IDEA_DRAFT
    said = " ".join(e["text"] for e in _of(events, "sentence"))
    assert "Backlog idea: A quieter chime." in said
    _aged(fixed)
    # His next turn, not a yes: nothing saved, whatever the model does.
    cascade_engine.model = FakeModel(
        [call("add_backlog_idea", {"id": fixed, "confirm": True}), say("Done.")]
    )
    _web_turn(web, "Hmm, let me think.", conversation)
    assert BoardIdea.objects.get().status == IDEA_DRAFT
    # His next turn, a yes: saved.
    cascade_engine.model = FakeModel(
        [call("add_backlog_idea", {"id": fixed, "confirm": True}), say("Done.")]
    )
    events, _ = _web_turn(web, "Yes.", conversation)
    idea = BoardIdea.objects.get()
    assert idea.status == IDEA_PENDING, _of(events, "tool")
