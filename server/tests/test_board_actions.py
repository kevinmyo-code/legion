"""Control room C3 (`board/actions.py`, `board/action_views.py`), DARK behind
`LEGION_BOARD_ACTIONS` and `LEGION_BOARD`.

Proved from the outside: only the laptop's writer token asks; typed fields only (an unknown field,
an over-cap spend, a review of another sha is refused); his tap must carry the hash he saw (409
changed), works once (409 replay), not after 30 minutes on the engine's clock (410 expired); a
browser session cannot tap; every tap is in the append-only log; and only the writer pulls.
Every sha, payee and purpose is invented.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient

from board.flags import ACTIONS_ENV, BOARD_ENV
from board.models import BoardAction, BoardActionTap

pytestmark = pytest.mark.django_db

WRITER = "/api/board/writer"
ACTIONS = "/api/board/actions"
REQUEST = "/api/board/actions/request"
APPROVED = "/api/board/actions/approved"
SHA = "1a2b3c4d" * 5


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(BOARD_ENV, "on")
    monkeypatch.setenv(ACTIONS_ENV, "on")


def _laptop(client) -> APIClient:
    response = client.post(WRITER)
    assert response.status_code == 201, response.content
    laptop = APIClient()
    laptop.credentials(HTTP_AUTHORIZATION=f"Token {response.json()['token']}")
    return laptop


DEPLOY = {
    "kind": "deploy_engine",
    "sha": SHA,
    "tier": "RED",
    "review_ok_sha": SHA,
    "ci_green": True,
}
SPEND = {
    "kind": "spend",
    "amount_cents": 1999,
    "payee": "Tea Supply Co",
    "purpose": "Kettle descaler",
}


def _ask(laptop, body=DEPLOY):
    return laptop.post(REQUEST, body, format="json")


def _tap(client, action, verb="allow", sha=None):
    return client.post(
        f"{ACTIONS}/{action['id']}/{verb}",
        {"record_sha256": sha if sha is not None else action["record_sha256"]},
        format="json",
    )


def test_off_is_404_even_with_the_board_on(monkeypatch, auth_client):
    monkeypatch.setenv(BOARD_ENV, "on")
    laptop = _laptop(auth_client)
    assert _ask(laptop).status_code == 404
    assert auth_client.get(ACTIONS).status_code == 404


def test_only_the_writer_asks(on, auth_client):
    _laptop(auth_client)
    assert auth_client.post(REQUEST, DEPLOY, format="json").status_code == 403
    assert not BoardAction.objects.exists()


@pytest.mark.parametrize(
    "body",
    [
        {**DEPLOY, "note": "please"},
        {**DEPLOY, "review_ok_sha": "f" * 40},
        {**DEPLOY, "ci_green": False},
        {**DEPLOY, "tier": "PURPLE"},
        {**DEPLOY, "sha": "1a2b3c4d"},
        {**SPEND, "amount_cents": 2501},
        {**SPEND, "amount_cents": 0},
        {**SPEND, "amount_cents": True},
        {**SPEND, "amount_cents": 19.99},
        {**SPEND, "payee": "Tea <script>"},
        {**DEPLOY, "amount_cents": 100},
        {
            "kind": "flag_on",
            "sha": SHA,
            "tier": "RED",
            "review_ok_sha": SHA,
            "ci_green": True,
            "flags": ["not_a_flag"],
        },
        {"kind": "rm_rf"},
    ],
)
def test_typed_fields_only_and_the_cap(on, auth_client, body):
    response = _ask(_laptop(auth_client), body)
    assert response.status_code == 400, response.content
    assert not BoardAction.objects.exists()


def test_a_spend_at_the_cap_and_a_flag_record_are_taken(on, auth_client):
    laptop = _laptop(auth_client)
    assert _ask(laptop, {**SPEND, "amount_cents": 2500}).status_code == 201
    flag = {**DEPLOY, "kind": "flag_on", "flags": ["LEGION_BOARD"]}
    assert _ask(laptop, flag).status_code == 201


def test_allow_once_with_the_hash_he_saw(on, auth_client):
    laptop = _laptop(auth_client)
    action = _ask(laptop).json()
    assert [a["id"] for a in auth_client.get(ACTIONS).json()["actions"]] == [action["id"]]
    response = _tap(auth_client, action)
    assert response.status_code == 200, response.content
    assert response.json()["status"] == "approved"
    replay = _tap(auth_client, action)
    assert replay.status_code == 409
    assert [r.result for r in BoardActionTap.objects.order_by("created_at")] == [
        "applied",
        "replay",
    ]
    pulled = laptop.get(APPROVED).json()["actions"]
    assert [a["id"] for a in pulled] == [action["id"]]
    assert laptop.post(f"{ACTIONS}/{action['id']}/pulled").status_code == 200
    assert laptop.get(APPROVED).json()["actions"] == []


def test_a_changed_hash_is_409_and_logged(on, auth_client):
    action = _ask(_laptop(auth_client)).json()
    assert _tap(auth_client, action, sha="0" * 64).status_code == 409
    assert BoardAction.objects.get().status == "pending"
    assert BoardActionTap.objects.get().result == "changed"


def test_a_record_edited_under_him_is_409(on, auth_client):
    action = _ask(_laptop(auth_client), SPEND).json()
    BoardAction.objects.filter(pk=action["id"]).update(amount_cents=2400)
    assert _tap(auth_client, action).status_code == 409


def test_expiry_is_the_engines_clock(on, auth_client):
    action = _ask(_laptop(auth_client)).json()
    row = BoardAction.objects.get()
    BoardAction.objects.filter(pk=row.pk).update(expires_at=timezone.now() - timedelta(seconds=1))
    response = _tap(auth_client, action)
    assert response.status_code == 410
    assert BoardActionTap.objects.get().result == "expired"
    assert auth_client.get(ACTIONS).json()["actions"][0]["status"] == "expired"


def test_decline_is_final(on, auth_client):
    action = _ask(_laptop(auth_client)).json()
    assert _tap(auth_client, action, "decline").json()["status"] == "declined"
    assert _tap(auth_client, action).status_code == 409


def test_a_browser_session_cannot_tap(on, auth_client, household_user):
    action = _ask(_laptop(auth_client)).json()
    browser = APIClient()
    browser.force_login(household_user)
    assert _tap(browser, action).status_code == 403
    assert BoardAction.objects.get().status == "pending"


def test_the_writer_cannot_tap_and_members_cannot_pull(on, auth_client):
    laptop = _laptop(auth_client)
    action = _ask(laptop).json()
    assert _tap(laptop, action).status_code in (401, 403)
    assert auth_client.get(APPROVED).status_code == 403


def test_the_tap_log_is_append_only(on, auth_client):
    action = _ask(_laptop(auth_client)).json()
    _tap(auth_client, action)
    tap = BoardActionTap.objects.get()
    tap.result = "changed"
    with pytest.raises(ValueError):
        tap.save()
    with pytest.raises(ValueError):
        tap.delete()
