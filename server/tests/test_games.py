"""`/api/games/*`: a Tetris score race between two household members
(retro-games ticket 05, ADR 0060).

Clock-dependent behaviour is tested by moving the row's timestamps, never by
sleeping: the views read `timezone.now()` and the rows are rewound around it.
"""

from __future__ import annotations

import datetime as dt
import io
import uuid

import pytest
from django.core.management import call_command
from django.utils import timezone

from games import matches
from games.models import GameMatch
from push import copy
from push.models import GAME_INVITE, PushPreference, PushSent, PushSubscription
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

VAPID = {
    "VAPID_PUBLIC_KEY": "BAi5o31dKI-test-public",
    "VAPID_PRIVATE_KEY": "test-private",
    "VAPID_SUBJECT": "mailto:kevin@example.com",
}


class FakeSender:
    def __init__(self, status: int = 201):
        self.sent: list[tuple[object, dict]] = []
        self.status = status

    def __call__(self, subscription, payload):
        self.sent.append((subscription.user_id, payload))
        return self.status


@pytest.fixture
def kevin(household_user):
    household_user.first_name = "Kevin"
    household_user.save()
    return household_user


@pytest.fixture
def mia(household_a):
    user = _member(household_a, "mia@example.com")
    user.first_name = "Mia"
    user.save()
    return user


@pytest.fixture
def kevin_client(auth_client, kevin):
    return auth_client


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


@pytest.fixture
def no_vapid(monkeypatch):
    for name in VAPID:
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def sender(monkeypatch):
    for name, value in VAPID.items():
        monkeypatch.setenv(name, value)
    fake = FakeSender()
    monkeypatch.setattr("push.sender.webpush_sender", lambda config: fake)
    return fake


def subscribe(user, household):
    return PushSubscription.objects.create(
        household=household,
        user=user,
        endpoint=f"https://push.example.com/{uuid.uuid4()}",
        p256dh="p",
        auth="a",
    )


def invite(client, opponent) -> dict:
    made = client.post("/api/games/matches/", {"opponent_user_id": str(opponent.pk)}, format="json")
    assert made.status_code == 201, made.data
    return made.data


def rewind(match_id, by: dt.timedelta):
    """Move every timestamp on a match into the past by `by`."""
    row = GameMatch.objects.get(pk=match_id)
    for name in ("created_at", "expires_at", "start_at", "ends_at", "closed_at"):
        value = getattr(row, name)
        if value is not None:
            setattr(row, name, value - by)
    row.save()


def progress(client, match_id, **fields):
    body = {"score": 0, "lines": 0, "level": 1, "topped_out": False, "finished": False}
    body.update(fields)
    return client.post(f"/api/games/matches/{match_id}/progress/", body, format="json")


def started(kevin_client, mia_client, mia) -> str:
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert mia_client.post(f"/api/games/matches/{match_id}/accept/").status_code == 200
    return match_id


# =============================================================================
# Opponents and invites
# =============================================================================


def test_opponents_are_the_other_members_of_my_household(kevin_client, kevin, mia, user_b):
    body = kevin_client.get("/api/games/opponents/").json()
    assert body == [{"user_id": str(mia.pk), "name": "Mia"}]


def test_an_invite_has_a_uint32_seed_a_two_minute_expiry_and_reaches_her_device(
    kevin_client, kevin, mia, household_a, sender
):
    subscribe(mia, household_a)
    made = invite(kevin_client, mia)
    match = made["match"]
    assert match["status"] == "invited" and match["role"] == "inviter"
    assert 0 <= match["seed"] <= 4294967295
    created = dt.datetime.fromisoformat(match["created_at"])
    expires = dt.datetime.fromisoformat(match["expires_at"])
    assert expires - created == dt.timedelta(seconds=120)
    assert match["opponent"]["name"] == "Mia" and match["you"]["name"] == "Kevin"
    assert match["detail"] == "Waiting for Mia to accept."
    assert made["push"] == "sent"
    assert made["detail"] == "Invited Mia. A notification was sent to their device."
    [(user_id, payload)] = sender.sent
    assert user_id == mia.pk
    assert payload["kind"] == "game_invite"
    assert payload["url"] == f"/games/match/{match['id']}"
    assert payload["title"] == "Tetris"
    assert payload["body"] == copy.game_invite("Kevin")
    assert payload["off"] == {"kind": "game_invite", "label": copy.TURN_OFF_ACTION}


def test_the_invite_push_honours_her_preference(kevin_client, kevin, mia, household_a, sender):
    subscribe(mia, household_a)
    PushPreference.objects.create(household=household_a, user=mia, game_invite=False)
    made = invite(kevin_client, mia)
    assert made["push"] == "turned_off"
    assert "they have Tetris invites turned off" in made["detail"]
    assert sender.sent == []


def test_no_device_and_failed_delivery_are_said_in_words(
    kevin_client, kevin, mia, household_a, sender
):
    made = invite(kevin_client, mia)
    assert made["push"] == "no_device"
    assert made["detail"].startswith("Invited Mia. No notification was sent")
    subscribe(mia, household_a)
    sender.status = 500
    made = invite(kevin_client, mia)
    assert made["push"] == "failed"
    assert "did not reach any of their devices" in made["detail"]


def test_with_push_off_on_this_server_the_invite_still_stands(
    kevin_client, kevin, mia, household_a, no_vapid
):
    subscribe(mia, household_a)
    made = invite(kevin_client, mia)
    assert made["push"] == "not_set_up"
    assert "notifications are not set up on this server" in made["detail"]
    assert GameMatch.objects.filter(pk=made["match"]["id"], status="invited").exists()


def test_inviting_yourself_or_a_stranger_is_refused(kevin_client, kevin, user_b, no_vapid):
    for target in (kevin.pk, user_b.pk, uuid.uuid4()):
        refused = kevin_client.post(
            "/api/games/matches/", {"opponent_user_id": str(target)}, format="json"
        )
        assert refused.status_code == 400
        assert refused.data["detail"].startswith("No invite was made.")
    assert not GameMatch.objects.exists()


def test_pending_lists_live_invites_addressed_to_me(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert kevin_client.get("/api/games/matches/pending/").json() == []
    pending = mia_client.get("/api/games/matches/pending/").json()
    assert [m["id"] for m in pending] == [match_id]
    assert pending[0]["role"] == "invitee"
    assert pending[0]["detail"] == "Kevin invited you to a Tetris race."


# =============================================================================
# Accept, decline, cancel, expire
# =============================================================================


def test_accept_starts_in_five_seconds_for_three_minutes(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = invite(kevin_client, mia)["match"]["id"]
    accepted = mia_client.post(f"/api/games/matches/{match_id}/accept/")
    assert accepted.status_code == 200
    body = accepted.json()
    start = dt.datetime.fromisoformat(body["start_at"])
    ends = dt.datetime.fromisoformat(body["ends_at"])
    now = dt.datetime.fromisoformat(body["server_now"])
    assert body["status"] == "playing"
    assert dt.timedelta(seconds=4) < start - now <= dt.timedelta(seconds=5)
    assert ends - start == dt.timedelta(seconds=180)
    # Both sides read the same seed and clock.
    seen = kevin_client.get(f"/api/games/matches/{match_id}/").json()
    assert (seen["seed"], seen["start_at"], seen["ends_at"]) == (
        body["seed"],
        body["start_at"],
        body["ends_at"],
    )
    again = mia_client.post(f"/api/games/matches/{match_id}/accept/")
    assert again.status_code == 409
    assert again.data["detail"].startswith("Nothing was accepted.")


def test_only_the_invitee_accepts_or_declines_and_only_the_inviter_cancels(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert kevin_client.post(f"/api/games/matches/{match_id}/accept/").status_code == 403
    assert kevin_client.post(f"/api/games/matches/{match_id}/decline/").status_code == 403
    assert mia_client.post(f"/api/games/matches/{match_id}/cancel/").status_code == 403
    assert GameMatch.objects.get(pk=match_id).status == "invited"


def test_a_decline_is_said_in_words_to_the_inviter(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert mia_client.post(f"/api/games/matches/{match_id}/decline/").status_code == 200
    seen = kevin_client.get(f"/api/games/matches/{match_id}/").json()
    assert seen["status"] == "declined"
    assert seen["detail"] == "Mia declined. Nothing was played."
    assert seen["result"] is None


def test_a_cancel_is_said_in_words_to_the_invitee(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert kevin_client.post(f"/api/games/matches/{match_id}/cancel/").status_code == 200
    seen = mia_client.get(f"/api/games/matches/{match_id}/").json()
    assert seen["status"] == "cancelled"
    assert seen["detail"] == "Kevin cancelled the invite. Nothing was played."
    assert mia_client.get("/api/games/matches/pending/").json() == []


def test_an_invite_expires_after_two_minutes_and_cannot_be_accepted(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = invite(kevin_client, mia)["match"]["id"]
    rewind(match_id, dt.timedelta(seconds=121))
    assert mia_client.get("/api/games/matches/pending/").json() == []
    late = mia_client.post(f"/api/games/matches/{match_id}/accept/")
    assert late.status_code == 409
    assert "The invite expired" in late.data["detail"]
    assert kevin_client.get(f"/api/games/matches/{match_id}/").json()["status"] == "expired"


# =============================================================================
# The race
# =============================================================================


def test_progress_is_each_players_own_and_the_higher_score_wins(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = started(kevin_client, mia_client, mia)
    mine = progress(kevin_client, match_id, score=1200, lines=8, level=1)
    assert mine.status_code == 200
    assert mine.json()["you"]["score"] == 1200
    hers = progress(mia_client, match_id, score=900, lines=6, level=1)
    assert hers.json()["opponent"]["score"] == 1200
    assert hers.json()["status"] == "playing"
    progress(kevin_client, match_id, score=1500, lines=10, level=2, finished=True)
    done = progress(
        mia_client, match_id, score=1400, lines=9, level=1, topped_out=True, finished=True
    )
    body = done.json()
    assert body["status"] == "ended"
    assert body["result"] == "lost"
    assert body["detail"] == "Kevin won, 1,500 to 1,400."
    assert body["you"]["topped_out"] is True
    kevin_view = kevin_client.get(f"/api/games/matches/{match_id}/").json()
    assert kevin_view["result"] == "won" and kevin_view["detail"] == "You won, 1,500 to 1,400."


def test_equal_scores_draw(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    progress(kevin_client, match_id, score=700, finished=True)
    body = progress(mia_client, match_id, score=700, finished=True).json()
    assert body["result"] == "draw" and body["detail"] == "A draw, 700 each."


def test_a_finished_player_stays_finished(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    progress(kevin_client, match_id, score=10, topped_out=True, finished=True)
    body = progress(kevin_client, match_id, score=10).json()
    assert body["you"]["finished"] is True and body["you"]["topped_out"] is True


def test_progress_is_refused_fifteen_seconds_after_the_end(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = started(kevin_client, mia_client, mia)
    progress(kevin_client, match_id, score=300)
    rewind(match_id, dt.timedelta(seconds=5 + 180 + 16))
    late = progress(kevin_client, match_id, score=99999)
    assert late.status_code == 409
    assert late.data["detail"].startswith("Nothing was saved. The race ended")
    seen = kevin_client.get(f"/api/games/matches/{match_id}/").json()
    assert seen["status"] == "ended" and seen["you"]["score"] == 300
    assert seen["result"] == "won"


def test_progress_inside_the_grace_still_counts(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    rewind(match_id, dt.timedelta(seconds=5 + 180 + 10))
    saved = progress(kevin_client, match_id, score=450, finished=True)
    assert saved.status_code == 200 and saved.json()["you"]["score"] == 450


def test_progress_before_accept_is_refused(kevin_client, kevin, mia, no_vapid):
    match_id = invite(kevin_client, mia)["match"]["id"]
    refused = progress(kevin_client, match_id, score=5)
    assert refused.status_code == 409
    assert refused.data["detail"] == "Nothing was saved. Waiting for Mia to accept."


def test_bad_progress_is_refused_in_words(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    refused = progress(kevin_client, match_id, score=-1)
    assert refused.status_code == 400
    assert refused.data["detail"].startswith("Nothing was saved.")


# =============================================================================
# Boards: each side sees the other's (ADR 0060, amended 2026-10-19)
# =============================================================================

EMPTY_BOARD = "." * 220
# Row 21 (the floor) holds an I across the left four cells; an O falls at the top.
SOME_BOARD = "....OO...." + "....OO...." + "." * 190 + "IIII......"
assert len(SOME_BOARD) == 220


def test_a_board_is_empty_until_posted_then_shown_to_the_other_player(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = started(kevin_client, mia_client, mia)
    before = mia_client.get(f"/api/games/matches/{match_id}/").json()
    assert before["you"]["board"] == "" and before["opponent"]["board"] == ""
    mine = progress(kevin_client, match_id, score=10, board=SOME_BOARD)
    assert mine.status_code == 200, mine.data
    assert mine.json()["you"]["board"] == SOME_BOARD
    hers = mia_client.get(f"/api/games/matches/{match_id}/").json()
    assert hers["opponent"]["board"] == SOME_BOARD
    assert hers["you"]["board"] == ""


def test_a_post_without_a_board_keeps_the_last_one(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    progress(kevin_client, match_id, score=10, board=SOME_BOARD)
    again = progress(kevin_client, match_id, score=20)
    assert again.status_code == 200
    assert again.json()["you"]["board"] == SOME_BOARD
    assert again.json()["you"]["score"] == 20
    cleared = progress(kevin_client, match_id, score=30, board=EMPTY_BOARD)
    assert cleared.json()["you"]["board"] == EMPTY_BOARD


@pytest.mark.parametrize(
    "board",
    [
        "." * 219,  # one cell short
        "." * 221,  # one cell over
        "." * 219 + "G",  # the ghost is never sent
        "." * 219 + "i",  # lower case is not a piece
        "." * 219 + " ",  # nor is a space
    ],
)
def test_a_bad_board_is_refused_in_words_and_nothing_is_saved(
    kevin_client, mia_client, kevin, mia, no_vapid, board
):
    match_id = started(kevin_client, mia_client, mia)
    refused = progress(kevin_client, match_id, score=500, board=board)
    assert refused.status_code == 400
    assert refused.data["detail"] == matches.BOARD_REFUSAL
    assert refused.data["detail"].startswith("Nothing was saved.")
    row = GameMatch.objects.get(pk=match_id)
    assert row.inviter_score == 0 and row.inviter_board == ""


def test_boards_are_deleted_with_the_match(kevin_client, mia_client, kevin, mia, no_vapid):
    match_id = started(kevin_client, mia_client, mia)
    progress(kevin_client, match_id, score=10, board=SOME_BOARD)
    progress(kevin_client, match_id, score=10, finished=True)
    progress(mia_client, match_id, score=5, finished=True)
    rewind(match_id, matches.KEEP + dt.timedelta(seconds=1))
    matches.sweep(timezone.now())
    assert not GameMatch.objects.filter(pk=match_id).exists()


def test_a_third_member_cannot_see_or_touch_the_match(
    kevin_client, mia_client, kevin, mia, household_a, no_vapid
):
    third = _client_for(_member(household_a, "guest@example.com"))
    match_id = invite(kevin_client, mia)["match"]["id"]
    for call in (
        lambda: third.get(f"/api/games/matches/{match_id}/"),
        lambda: third.post(f"/api/games/matches/{match_id}/accept/"),
        lambda: third.post(f"/api/games/matches/{match_id}/cancel/"),
        lambda: progress(third, match_id, score=1),
    ):
        assert call().status_code == 404
    assert third.get("/api/games/matches/pending/").json() == []


# =============================================================================
# Transient: deleted ten minutes after it closes
# =============================================================================


def test_a_closed_match_is_deleted_ten_minutes_later_with_its_push_row(
    kevin_client, mia_client, kevin, mia, household_a, sender
):
    subscribe(mia, household_a)
    match_id = invite(kevin_client, mia)["match"]["id"]
    assert PushSent.objects.filter(kind=GAME_INVITE).count() == 1
    mia_client.post(f"/api/games/matches/{match_id}/decline/")
    rewind(match_id, dt.timedelta(minutes=9))
    assert kevin_client.get(f"/api/games/matches/{match_id}/").status_code == 200
    rewind(match_id, dt.timedelta(minutes=2))
    gone = kevin_client.get(f"/api/games/matches/{match_id}/")
    assert gone.status_code == 404
    assert gone.data["detail"].startswith("No such match.")
    assert not GameMatch.objects.exists()
    assert not PushSent.objects.filter(kind=GAME_INVITE).exists()


def test_a_race_nobody_reopened_closes_by_clock_and_push_dispatch_deletes_it(
    kevin_client, mia_client, kevin, mia, no_vapid
):
    match_id = started(kevin_client, mia_client, mia)
    expired_invite = invite(kevin_client, mia)["match"]["id"]
    rewind(match_id, dt.timedelta(seconds=5 + 180 + 15) + matches.KEEP + dt.timedelta(seconds=1))
    rewind(expired_invite, matches.INVITE_TTL + matches.KEEP + dt.timedelta(seconds=1))
    out = io.StringIO()
    call_command("push_dispatch", stdout=out)
    assert "Deleted 2 finished Tetris match(es)." in out.getvalue()
    assert "Notifications are not set up on this server" in out.getvalue()
    assert not GameMatch.objects.exists()


def test_the_sweep_keeps_live_matches(kevin_client, mia_client, kevin, mia, no_vapid):
    started(kevin_client, mia_client, mia)
    invite(kevin_client, mia)
    assert matches.sweep(timezone.now()) == 0
    assert GameMatch.objects.count() == 2
