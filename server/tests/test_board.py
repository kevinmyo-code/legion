"""The factory board's off-laptop copy (`board/`), DARK behind `LEGION_BOARD`.

Proved from the outside: the switch (404 before anything, the tool in no list), the writer token
(shown once, hash only, PUT only, one per member, revocable), the cleaning and secret guard (real
card shapes pass, key shapes and emails refuse), member privacy, freshness on the engine's clock,
and the tool's code-spoken contract (card text only in the spoken lines, never in what the model is
handed). Every card, name and value is invented.
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
from board.flags import BOARD_ENV
from board.models import TOKEN_PREFIX, BoardSnapshot, hash_token
from board.tool import board_status
from engine_mcp.server import _tools_for_request
from engine_mcp.tools import ENDS_TURN, FOR_MODEL, REPLAY_AS, SPEAK_VERBATIM
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

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


def _card(card_id, col, title, **extra):
    return {"id": card_id, "col": col, "kind": "agent", "title": title, "age_min": 2, **extra}


def _board(*cards, **extra):
    return {
        "generated": "2031-04-02T10:00:00+00:00",
        "columns": [{"id": "queued", "title": "Queued"}],
        "build_slot": "FREE",
        "counts": {"working": 99},
        "needs_kevin": ["ignored"],
        "cards": list(cards),
        **extra,
    }


SAMPLE = _board(
    _card("tea-order", "needs_kevin", "Pick a teapot", ask="Choose blue or green, then say go."),
    _card(
        "kettle",
        "testing",
        "Kettle firmware",
        status="CI run 12345678901 at 1a2b3c4d",
        link="C:/somewhere/local.txt",
        created="2031-04-02T09:00:00+00:00",
    ),
    _card(
        "scones",
        "working",
        "Scone recipe",
        status="Reading notes/scones_batch_two_final.md",
        waiting="the oven",
    ),
    _card("jam", "queued", "Jam labels"),
    _card("bread", "done", "Bread proofed"),
)


def _writer(client) -> str:
    response = client.post(WRITER)
    assert response.status_code == 201, response.content
    return response.json()["token"]


def _as(raw: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


def _push(raw: str, body=SAMPLE):
    return _as(raw).put(BOARD, data=json.dumps(body), content_type="application/json")


def _request(user):
    django_request = RequestFactory().post("/api/assistant/turn")
    django_request.user = user
    request = Request(django_request)
    request.user = user
    return request


# ---- the switch -------------------------------------------------------------------------------


def test_off_every_route_is_404_and_the_tool_is_in_no_list(auth_client, household_user):
    assert auth_client.get(BOARD).status_code == 404
    assert auth_client.post(WRITER).status_code == 404
    assert auth_client.delete(WRITER).status_code == 404
    assert (
        _as(TOKEN_PREFIX + "x" * 43)
        .put(BOARD, data="{}", content_type="application/json")
        .status_code
        == 404
    )
    assert "board_status" not in {t.name for t in tools_for(household_user)}
    assert "board_status" not in {t.name for t in _tools_for_request(_request(household_user))}
    assert board_status(_request(household_user), {}).is_error


def test_on_the_tool_is_offered_on_the_web_but_never_on_mcp(on, household_user):
    assert "board_status" in {t.name for t in tools_for(household_user)}
    assert "board_status" not in {t.name for t in _tools_for_request(_request(household_user))}


# ---- the writer token -------------------------------------------------------------------------


def test_mint_shows_the_token_once_and_keeps_only_its_hash(on, auth_client, household_user):
    raw = _writer(auth_client)
    assert raw.startswith(TOKEN_PREFIX) and len(raw) > 40
    row = BoardSnapshot.objects.get(owner_user=household_user)
    assert row.writer_key_hash == hash_token(raw)
    assert raw not in json.dumps(auth_client.get(BOARD).json(), default=str)


def test_minting_again_kills_the_old_token(on, auth_client):
    old = _writer(auth_client)
    new = _writer(auth_client)
    assert _push(old).status_code == 401
    assert _push(new).status_code == 200


def test_writer_token_reaches_put_only(on, auth_client):
    raw = _writer(auth_client)
    assert _as(raw).get(BOARD).status_code == 403
    assert _as(raw).post(WRITER).status_code == 401
    assert _as(raw).get("/api/auth/me").status_code == 401


def test_a_member_sign_in_cannot_put(on, auth_client):
    response = auth_client.put(BOARD, data=json.dumps(SAMPLE), content_type="application/json")
    assert response.status_code == 403


def test_revoke_stops_the_laptop(on, auth_client):
    raw = _writer(auth_client)
    assert auth_client.delete(WRITER).status_code == 204
    assert _push(raw).status_code == 401
    assert auth_client.delete(WRITER).status_code == 404


def test_writer_of_a_removed_member_is_dead(on, auth_client, household_user):
    raw = _writer(auth_client)
    household_user.is_active = False
    household_user.save(update_fields=["is_active"])
    assert _push(raw).status_code == 401


# ---- what is stored ---------------------------------------------------------------------------


def test_push_keeps_known_fields_only_and_counts_on_the_engine(on, auth_client):
    raw = _writer(auth_client)
    assert _push(raw).status_code == 200
    body = auth_client.get(BOARD).json()
    snap = body["snapshot"]
    kettle = next(c for c in snap["cards"] if c["id"] == "kettle")
    assert "link" not in kettle and "created" not in kettle
    assert snap["counts"] == {"queued": 1, "working": 1, "needs_kevin": 1, "testing": 1, "done": 1}
    assert snap["needs_kevin"] == ["tea-order"]
    assert body["live"] is True and body["writer"] is not None


@pytest.mark.parametrize(
    "text",
    [
        "token is ghp_" + "a1B2" * 9,
        "mail someone@example.com",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig",
        "-----BEGIN PRIVATE KEY-----",
        "password=hunter2hunter2",
        "blob Zx9Qw3ErTy7UiOp1AsDf5GhJk2LzXc8VbNm4",
        "hash " + "ab" * 32,
        "bw1_" + "Q" * 20,
    ],
)
def test_a_key_shaped_field_is_blanked_and_named_never_echoed(
    on, auth_client, household_user, text
):
    raw = _writer(auth_client)
    response = _push(raw, _board(_card("x", "working", "Invented", status=text)))
    assert response.status_code == 200
    assert text not in response.content.decode()
    assert response.json()["removed"] == [{"card": "x", "field": "status"}]
    stored = BoardSnapshot.objects.get(owner_user=household_user).cards[0]
    assert stored["status"] == board_cards.REMOVED and stored["title"] == "Invented"


def test_one_poisoned_card_among_many_leaves_the_rest_stored(on, auth_client):
    raw = _writer(auth_client)
    poisoned = _card(
        "poison",
        "needs_kevin",
        "Looks fine",
        ask="mail someone@example.com",
        options=["ok", "ghp_" + "a1B2" * 9],
    )
    body = _board(*SAMPLE["cards"], poisoned)
    response = _push(raw, body)
    assert response.status_code == 200
    assert response.json()["removed"] == [
        {"card": "poison", "field": "ask"},
        {"card": "poison", "field": "options[1]"},
    ]
    snap = auth_client.get(BOARD).json()["snapshot"]
    assert len(snap["cards"]) == len(SAMPLE["cards"]) + 1
    card = next(c for c in snap["cards"] if c["id"] == "poison")
    assert card["ask"] == board_cards.REMOVED and card["options"] == ["ok", board_cards.REMOVED]


def test_a_zero_width_character_cannot_hide_a_token():
    assert board_cards.looks_secret(
        board_cards._INVISIBLE.sub("", "ghp" + chr(0x200B) + "_" + "a1B2" * 9)
    )


def test_projects_options_and_answers_survive(on, auth_client, household_user):
    raw = _writer(auth_client)
    body = _board(
        _card(
            "pot",
            "needs_kevin",
            "Pick a teapot",
            project="home",
            ask="Blue or green?",
            options=["Blue", "Green"],
            free_text=False,
            ask_kind="choice",
            say="Blue or green teapot?",
        ),
        _card(
            "ship",
            "needs_kevin",
            "Ship the kettle",
            project="engine",
            ask="Ship it?",
            ask_kind="approval",
        ),
        _card(
            "jam",
            "needs_kevin",
            "Jam labels",
            project="home",
            ask="Which font?",
            options=["Serif"],
            ask_kind="choice",
            answer={
                "choice": "Serif",
                "text": "",
                "at": "2031-04-02T10:05:00+00:00",
                "by": "phone",
            },
        ),
        _card("x", "working", "Mystery", project="spaceship"),
    )
    assert _push(raw, body).status_code == 200
    got = auth_client.get(BOARD).json()
    cards = {c["id"]: c for c in got["snapshot"]["cards"]}
    assert cards["pot"]["options"] == ["Blue", "Green"] and cards["pot"]["free_text"] is False
    assert cards["ship"]["ask_kind"] == "approval" and cards["x"]["project"] == "other"
    assert cards["jam"]["answer"]["choice"] == "Serif"
    assert got["snapshot"]["answered_waiting"] == ["jam"]
    assert {p["id"]: p["needs"] for p in got["snapshot"]["projects"]} == {
        "engine": 1,
        "home": 2,
        "other": 0,
    }
    spoken = got["spoken"]
    assert "Waiting on you: 1 on LEGION engine, 1 on Home." in spoken
    assert any(
        line.startswith("A card for Home asks: Pick a teapot. Blue or green teapot?")
        and "Choices: Blue, Green." in line
        for line in spoken
    )
    assert "You answered Jam labels: Serif. Waiting for it to be picked up." in spoken
    result = board_status(_request(household_user), {})
    assert "1 on LEGION engine, 1 on Home" in result.structured[FOR_MODEL]
    assert "teapot" not in result.structured[FOR_MODEL]


def test_mint_response_is_never_cached(on, auth_client):
    response = auth_client.post(WRITER)
    assert response["Cache-Control"] == "no-store"


def test_a_housemates_laptop_never_makes_my_board_say_on(
    on, auth_client, household_user, household_a
):
    from claude_session.models import SCOPE_FULL, ClaudeMachine
    from household.models import DeviceToken

    _push(_writer(auth_client))
    BoardSnapshot.objects.filter(owner_user=household_user).update(
        received_at=timezone.now() - timedelta(hours=1)
    )
    housemate = _member(household_a, "housemate@example.com")
    their_token, _ = DeviceToken.issue(housemate, "claude-session-Theirs")
    ClaudeMachine.objects.create(
        household=household_a,
        name="Their laptop",
        scope=SCOPE_FULL,
        last_seen_at=timezone.now(),
        token=their_token,
    )
    assert auth_client.get(BOARD).json()["laptop_on"] is False


def test_deleting_the_member_at_the_database_deletes_the_board(household_a):
    from django.db import connection

    from household.models import HouseholdMember

    member = _member(household_a, "leaver@example.com")
    BoardSnapshot.mint_writer(household=household_a, owner_user=member)
    HouseholdMember.objects.filter(user=member).delete()
    with connection.cursor() as cursor:
        # What code that predates the table does: it knows nothing of board_snapshots.
        cursor.execute("delete from household_user where id = %s", [member.pk])
    assert not BoardSnapshot.objects.filter(owner_user_id=member.pk).exists()


@pytest.mark.parametrize(
    "text",
    [
        "CI run 10000000001 (first compile)",
        "Reviewed at 0a1b2c3d, full sha 0123456789abcdef0123456789abcdef01234567",
        "APK at builds/out/teapot-100000001.apk",
        "run_the_invented_teapot_release_0401.sh ready",
        "feat/invented-teapot-branch, checks owed",
    ],
)
def test_real_card_shapes_pass_the_guard(text):
    assert not board_cards.looks_secret(text)


def test_too_big_is_413(on, auth_client):
    raw = _writer(auth_client)
    big = _board(
        *[_card(f"c{i}", "queued", "x" * 200, status="y" * 200) for i in range(70)],
        pad="z" * board_cards.MAX_BYTES,
    )
    assert _push(raw, big).status_code == 413


def test_bad_shapes_are_400(on, auth_client):
    raw = _writer(auth_client)
    assert _push(raw, {"cards": "nope"}).status_code == 400
    assert _push(raw, _board(_card("a", "elsewhere", "T"))).status_code == 400
    assert (
        _push(raw, _board(_card("a", "working", "T"), _card("a", "working", "T"))).status_code
        == 400
    )


def test_another_member_never_reads_my_board(on, auth_client, household_a):
    _push(_writer(auth_client))
    other = _client_for(_member(household_a, "other@example.com"))
    assert other.get(BOARD).json()["snapshot"] is None


# ---- freshness and speech ---------------------------------------------------------------------


def test_old_snapshot_with_the_laptop_off_says_so(on, auth_client, household_user):
    _push(_writer(auth_client))
    BoardSnapshot.objects.filter(owner_user=household_user).update(
        received_at=timezone.now() - timedelta(hours=2)
    )
    body = auth_client.get(BOARD).json()
    assert body["live"] is False and body["laptop_on"] is False
    assert "The laptop is off or offline." in body["spoken"][0]
    scones = next(c for c in body["snapshot"]["cards"] if c["id"] == "scones")
    assert scones["age_min"] >= 120 and scones["stale"] is True


def test_old_snapshot_with_the_laptop_on_says_when_it_was_sent(
    on, auth_client, household_user, household_a
):
    from claude_session.models import SCOPE_FULL, ClaudeMachine

    _push(_writer(auth_client))
    BoardSnapshot.objects.filter(owner_user=household_user).update(
        received_at=timezone.now() - timedelta(hours=1)
    )
    from household.models import DeviceToken

    mine, _ = DeviceToken.issue(household_user, "claude-session-Mine")
    ClaudeMachine.objects.create(
        household=household_a,
        name="Invented laptop",
        scope=SCOPE_FULL,
        last_seen_at=timezone.now(),
        token=mine,
    )
    body = auth_client.get(BOARD).json()
    assert body["laptop_on"] is True
    assert body["spoken"][0].startswith("The laptop is on. Its board last changed")


def test_never_sent_is_a_sentence(on, auth_client):
    body = auth_client.get(BOARD).json()
    assert body["snapshot"] is None and body["spoken"] and "never sent" in body["spoken"][0]


def test_tool_speaks_cards_by_code_and_hands_the_model_counts_only(on, auth_client, household_user):
    _push(_writer(auth_client))
    result = board_status(_request(household_user), {})
    assert not result.is_error
    said = " ".join(result.structured[SPEAK_VERBATIM])
    assert "Pick a teapot" in said and "Choose blue or green" in said and "Kettle firmware" in said
    assert result.structured[ENDS_TURN] is True
    for model_text in (result.text, result.structured[FOR_MODEL], result.structured[REPLAY_AS]):
        for card_words in ("teapot", "Kettle", "Scone", "oven", "blue or green"):
            assert card_words not in model_text
    assert "1 waiting on you" in result.structured[FOR_MODEL]
    # Only the cascade speaks; a door that cannot is never told anything was said.
    assert "read aloud" not in result.text and "1 waiting on you" in result.text


# ---- registering a laptop-made token by its hash ------------------------------------------------


def test_board_writer_hash_registers_a_laptop_made_token(on, household_user, auth_client):
    from io import StringIO

    from django.core.management import call_command

    raw = TOKEN_PREFIX + "L" * 43
    out = StringIO()
    call_command(
        "board_writer_hash", email=household_user.email, sha256=hash_token(raw), stdout=out
    )
    assert household_user.email not in out.getvalue()
    assert _push(raw).status_code == 200
    assert auth_client.get(BOARD).json()["snapshot"] is not None


def test_board_writer_hash_refuses_a_bad_hash_or_a_stranger(household_user):
    from django.core.management import call_command
    from django.core.management.base import CommandError

    with pytest.raises(CommandError):
        call_command("board_writer_hash", email=household_user.email, sha256="nope")
    with pytest.raises(CommandError):
        call_command("board_writer_hash", email="nobody@example.com", sha256="a" * 64)


# ---- dark means unchanged ---------------------------------------------------------------------


def test_off_the_offered_tool_list_is_byte_identical_to_the_registry_without_the_board(
    household_user, monkeypatch
):
    """How a dark tool is hidden: `board_status` stays IN the registry (`TOOLS`, `TOOLS_BY_NAME`,
    `WEB_TOOL_NAMES`) and is left out of every OFFERED list (`surface.tools_for`, which the web
    cascade, Live and the agents gateway read, and `/mcp`'s tools/list) while `LEGION_BOARD` is
    off. So with the switch off, what any model is declared is exactly the registry minus it,
    which is what the engine offered before this slice."""
    from agents.legion.gateway import rest_declarations
    from assistant.surface import WEB_TOOL_NAMES
    from engine_mcp.tools import TOOLS_BY_NAME

    assert "board_status" in TOOLS_BY_NAME and "board_status" in WEB_TOOL_NAMES
    offered = tools_for(household_user)
    monkeypatch.setenv(BOARD_ENV, "on")
    # The same per-member list with the switch on, less the one tool: only the board differs.
    before = [t for t in tools_for(household_user) if t.name != "board_status"]
    monkeypatch.delenv(BOARD_ENV)
    assert [t.name for t in offered] == [t.name for t in before]
    assert json.dumps(rest_declarations(offered), sort_keys=True) == json.dumps(
        rest_declarations(before), sort_keys=True
    )
    mcp = {t.name for t in _tools_for_request(_request(household_user))}
    assert "board_status" not in mcp


def test_the_board_never_taints_a_turn():
    """Not third-party: the model is never handed card text (the phone's list must match too)."""
    from assistant.read_through import THIRD_PARTY_TOOLS

    assert "board_status" not in THIRD_PARTY_TOOLS
