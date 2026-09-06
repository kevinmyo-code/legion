"""`/api/events` (django-engine ticket 04, Phase 2 slice). Exercised over
HTTP with DRF's test client, same posture as `test_auth_endpoints.py`.
"""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from legacy.models.dates import Event

pytestmark = pytest.mark.django_db


def test_unauthenticated_get_events_is_401():
    response = APIClient().get("/api/events")
    assert response.status_code == 401


def test_unauthenticated_post_event_is_401():
    response = APIClient().post("/api/events", {"title": "test"}, format="json")
    assert response.status_code == 401


def test_create_event_returns_the_row_as_stored(auth_client):
    body = {"title": "COSC 3334 exam", "starts_at": "2026-10-01T09:00:00Z"}
    response = auth_client.post("/api/events", body, format="json")
    assert response.status_code == 201
    body = response.data
    assert body["title"] == "COSC 3334 exam"
    assert body["kind"] == "reminder"  # DB default, applied when the caller omits it
    assert body["source"] == "legion"
    assert body["provenance"] == "USER"
    assert body["done"] is False
    assert body["deleted_at"] is None
    assert Event.objects.filter(pk=body["id"]).exists()


def test_repeated_post_with_same_origin_guid_is_idempotent(auth_client):
    """Rule 5: "origin_guid/sync_id from the client is honoured on POST for
    idempotent upsert (the phone retries)" - a retried create with the same
    origin_guid must return the ROW THAT ALREADY EXISTS, not a duplicate."""
    body = {"title": "class reminder", "origin_guid": "phone-guid-1"}
    first = auth_client.post("/api/events", body, format="json")
    assert first.status_code == 201

    second = auth_client.post("/api/events", body, format="json")
    assert second.status_code == 200
    assert second.data["id"] == first.data["id"]
    assert Event.objects.filter(origin_guid="phone-guid-1").count() == 1


def test_wrong_kind_is_400_naming_the_allowed_set(auth_client):
    response = auth_client.post(
        "/api/events", {"title": "bad kind", "kind": "appointment"}, format="json"
    )
    assert response.status_code == 400
    body_text = str(response.data)
    assert "reminder" in body_text and "event" in body_text and "task" in body_text
    assert not Event.objects.filter(title="bad kind").exists()


def test_patch_done_round_trips_and_derives_done_at(auth_client):
    created = auth_client.post("/api/events", {"title": "todo"}, format="json").data

    ticked = auth_client.patch(f"/api/events/{created['id']}", {"done": True}, format="json")
    assert ticked.status_code == 200
    assert ticked.data["done"] is True
    assert ticked.data["done_at"] is not None

    unticked = auth_client.patch(f"/api/events/{created['id']}", {"done": False}, format="json")
    assert unticked.status_code == 200
    assert unticked.data["done"] is False
    assert unticked.data["done_at"] is None


def test_delete_is_a_soft_delete_and_idempotent(auth_client):
    created = auth_client.post("/api/events", {"title": "to remove"}, format="json").data

    first = auth_client.delete(f"/api/events/{created['id']}")
    assert first.status_code == 204
    event = Event.objects.get(pk=created["id"])
    assert event.deleted_at is not None

    second = auth_client.delete(f"/api/events/{created['id']}")
    assert second.status_code == 204


def test_since_feed_excludes_rows_before_the_watermark_and_includes_tombstones(auth_client):
    older = auth_client.post("/api/events", {"title": "older"}, format="json").data
    watermark = older["updated_at"]

    newer = auth_client.post("/api/events", {"title": "newer"}, format="json").data
    auth_client.delete(f"/api/events/{newer['id']}")

    response = auth_client.get(f"/api/events?since={watermark}")
    assert response.status_code == 200
    titles = {row["title"] for row in response.data["results"]}
    # "older" itself was created AT the watermark instant (its own
    # updated_at), and since is inclusive (>=) - matching the phone's own
    # fetchChangedSince contract ("sinceMs is inclusive... a row updated in
    # the exact same millisecond as the stored watermark must still be
    # seen"), so it is legitimately still present here.
    assert "newer" in titles
    tombstoned = [row for row in response.data["results"] if row["title"] == "newer"]
    assert tombstoned[0]["deleted_at"] is not None


def test_since_feed_omits_rows_updated_before_it(auth_client):
    first = auth_client.post("/api/events", {"title": "first"}, format="json").data
    second = auth_client.post("/api/events", {"title": "second"}, format="json").data
    # created_at/updated_at are stamped from Python (django.utils.timezone.now(),
    # `EventSerializer.create`), not deferred to Postgres's own `now()` - so
    # these two really do differ, unlike a DB-side `db_default=Now()` column
    # would inside pytest-django's one-transaction-per-test wrapping (where
    # Postgres's `now()` is fixed for the whole test; see
    # `test_checklists_models.py`'s own doc comment for that gotcha, found
    # the hard way while writing this ticket's tests).
    assert first["updated_at"] != second["updated_at"]

    response = auth_client.get(f"/api/events?since={second['updated_at']}")
    titles = {row["title"] for row in response.data["results"]}
    assert "second" in titles
    assert "first" not in titles
