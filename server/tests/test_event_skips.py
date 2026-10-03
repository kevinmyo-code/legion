"""`/api/events/<id>/skips` (web-revamp ticket 08, spec D4).

The routes are idempotent, read the parent event through `visible()` (so
another member's private event and another household's event are both a
404), travel `/api/changes` beside `events`, and DELETE tombstones.
"""
from __future__ import annotations

import pytest

from legacy.models.dates import Event, EventSkip
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"
WEEKLY = {
    "title": "Swim lesson",
    "starts_at": "2026-10-06T23:00:00Z",
    "repeat_kind": "WEEKLY",
    "repeat_every": 1,
    "repeat_days_of_week": "TUESDAY",
    "repeat_end_kind": "NEVER",
}


@pytest.fixture
def mia_client(household_a):
    return _client_for(_member(household_a, "mia@example.com"))


def _series(client, **extra):
    response = client.post("/api/events", {**WEEKLY, **extra}, format="json")
    assert response.status_code == 201, response.data
    return response.data["id"]


def test_post_is_201_then_200_and_one_row(auth_client):
    pk = _series(auth_client)
    first = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    assert first.status_code == 201, first.data
    assert first.data["skip_date"] == "2026-10-13"
    assert str(first.data["event"]) == str(pk)
    assert first.data["deleted_at"] is None
    again = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    assert again.status_code == 200
    assert again.data["id"] == first.data["id"]
    assert EventSkip.objects.filter(event_id=pk).count() == 1
    assert EventSkip.objects.get(event_id=pk).household_id == Event.objects.get(pk=pk).household_id


def test_get_lists_the_skips_by_date(auth_client):
    pk = _series(auth_client)
    for day in ("2026-10-27", "2026-10-13"):
        auth_client.post(f"/api/events/{pk}/skips", {"skip_date": day}, format="json")
    listed = auth_client.get(f"/api/events/{pk}/skips")
    assert listed.status_code == 200
    assert [row["skip_date"] for row in listed.data] == ["2026-10-13", "2026-10-27"]


def test_delete_tombstones_is_idempotent_and_a_repost_revives_the_same_row(auth_client):
    pk = _series(auth_client)
    made = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    assert auth_client.delete(f"/api/events/{pk}/skips/2026-10-13").status_code == 204
    skip = EventSkip.objects.get(pk=made.data["id"])
    assert skip.deleted_at is not None
    assert auth_client.delete(f"/api/events/{pk}/skips/2026-10-13").status_code == 204
    assert auth_client.delete(f"/api/events/{pk}/skips/2026-12-25").status_code == 204

    revived = auth_client.post(
        f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json"
    )
    assert revived.status_code == 200
    assert revived.data["id"] == made.data["id"]
    assert revived.data["deleted_at"] is None
    assert EventSkip.objects.filter(event_id=pk).count() == 1


def test_a_bad_date_is_refused_in_words(auth_client):
    pk = _series(auth_client)
    posted = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "13/10/2026"}, format="json")
    assert posted.status_code == 400
    deleted = auth_client.delete(f"/api/events/{pk}/skips/next-tuesday")
    assert deleted.status_code == 400
    assert "Nothing was changed" in deleted.data["detail"]


def test_skips_travel_the_changes_feed_with_their_tombstones(auth_client):
    pk = _series(auth_client)
    made = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    feed = auth_client.get("/api/changes?aspects=events").data
    rows = {row["id"]: row for row in feed["event_skips"]}
    assert rows[made.data["id"]]["skip_date"] == "2026-10-13"
    assert rows[made.data["id"]]["deleted_at"] is None

    auth_client.delete(f"/api/events/{pk}/skips/2026-10-13")
    feed = auth_client.get("/api/changes?aspects=events").data
    rows = {row["id"]: row for row in feed["event_skips"]}
    assert rows[made.data["id"]]["deleted_at"] is not None
    assert "event_skips" not in auth_client.get("/api/changes?aspects=checklists").data


def test_another_members_private_event_has_no_reachable_skips(auth_client, mia_client):
    pk = _series(auth_client, visibility="private")
    made = auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    for response in (
        mia_client.get(f"/api/events/{pk}/skips"),
        mia_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-20"}, format="json"),
        mia_client.delete(f"/api/events/{pk}/skips/2026-10-13"),
    ):
        assert response.status_code == 404, response.data
    assert EventSkip.objects.get(pk=made.data["id"]).deleted_at is None
    assert not EventSkip.objects.filter(skip_date="2026-10-20").exists()

    feed = mia_client.get("/api/changes?aspects=events").data
    row = {r["id"]: r for r in feed["event_skips"]}[made.data["id"]]
    assert set(row) == {"id", "deleted_at", "updated_at", "redacted"}


def test_another_households_event_has_no_reachable_skips(auth_client, token_b):
    pk = _series(auth_client)
    auth_client.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-13"}, format="json")
    for response in (
        token_b.get(f"/api/events/{pk}/skips"),
        token_b.post(f"/api/events/{pk}/skips", {"skip_date": "2026-10-20"}, format="json"),
        token_b.delete(f"/api/events/{pk}/skips/2026-10-13"),
    ):
        assert response.status_code == 404, response.data
    assert token_b.get("/api/changes?aspects=events").data["event_skips"] == []
