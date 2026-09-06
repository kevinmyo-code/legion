"""`GET /api/changes` (django-engine ticket 04, Phase 2 slice) - the one
feed the phone's cache is meant to live on.
"""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

pytestmark = pytest.mark.django_db


def test_unauthenticated_is_401():
    assert APIClient().get("/api/changes").status_code == 401


def test_no_since_returns_everything(auth_client):
    auth_client.post("/api/events", {"title": "an event"}, format="json")
    auth_client.post("/api/checklists/", {"name": "a checklist"}, format="json")

    response = auth_client.get("/api/changes")
    assert response.status_code == 200
    body = response.data
    assert "server_time" in body
    assert len(body["events"]) == 1
    assert len(body["checklists"]) == 1
    assert body["checklist_items"] == []
    assert body["checklist_ticks"] == []


def test_aspects_filter_selects_only_the_named_aspect(auth_client):
    auth_client.post("/api/events", {"title": "an event"}, format="json")
    auth_client.post("/api/checklists/", {"name": "a checklist"}, format="json")

    events_only = auth_client.get("/api/changes?aspects=events")
    assert "events" in events_only.data
    assert "checklists" not in events_only.data
    assert "checklist_items" not in events_only.data

    checklists_only = auth_client.get("/api/changes?aspects=checklists")
    assert "checklists" in checklists_only.data
    assert "checklist_items" in checklists_only.data
    assert "checklist_ticks" in checklists_only.data
    assert "events" not in checklists_only.data


def test_unknown_aspect_is_400_naming_the_allowed_set(auth_client):
    response = auth_client.get("/api/changes?aspects=ledger")
    assert response.status_code == 400
    assert "ledger" in str(response.data)
    assert "events" in str(response.data)
    assert "checklists" in str(response.data)


def test_server_time_round_trips_as_a_usable_since_watermark(auth_client):
    """The exact footgun found while writing this ticket's checklist tests
    (`test_checklists_api.py`'s own doc comment on the watermark test) -
    `server_time` must be safe to hand straight back as `?since=<value>`
    without the caller needing to know to percent-encode a `+`."""
    from urllib.parse import quote

    first = auth_client.get("/api/changes")
    watermark = first.data["server_time"]
    assert "+" not in watermark  # Z-suffixed, matching every other timestamp in this API

    auth_client.post("/api/events", {"title": "after the watermark"}, format="json")

    second = auth_client.get(f"/api/changes?since={quote(watermark)}")
    titles = {row["title"] for row in second.data["events"]}
    assert "after the watermark" in titles


# ---------------------------------------------------------------------------
# Phase 5: places, voice notes, body and memory join the vocabulary. They come
# from `api/registry.py`, the same list `api/urls.py` routes from, so a table
# cannot be routable and invisible here (or the reverse) - which is what these
# tests are actually checking.
# ---------------------------------------------------------------------------

PLACE = {"latitude": 29.76, "longitude": -95.37}
BODYWEIGHT = {
    "weight_value": 181.4,
    "weight_unit": "lbs",
    "logged_at": "2026-09-01T07:00:00Z",
    "trust_tier": "REPORTED",
}
MEMORY = {"text": "the spare key is in the tin", "logged_at": "2026-09-01T07:00:00Z"}
VOICE_NOTE = {"started_at": "2026-09-01T09:00:00Z", "kind": "SOLO", "title": "a thought"}


def test_aspects_places_and_body_returns_both(auth_client):
    auth_client.put("/api/places/home/", PLACE, format="json")
    auth_client.put("/api/body/bodyweight_logs/guid-1/", BODYWEIGHT, format="json")

    response = auth_client.get("/api/changes?aspects=places,body")
    assert response.status_code == 200
    body = response.data

    assert len(body["places"]) == 1
    assert body["places"][0]["label"] == "home"
    # `body` is one aspect and eight tables - every one of them is a key,
    # the same relationship `checklists` has with its three.
    assert len(body["bodyweight_logs"]) == 1
    for table in (
        "meal_logs",
        "meal_targets",
        "sleep_logs",
        "sleep_targets",
        "workout_plans",
        "workout_plan_items",
        "workout_set_logs",
    ):
        assert body[table] == [], table

    # Nothing that was not asked for.
    assert "events" not in body
    assert "memories" not in body
    assert "voice_notes" not in body


def test_memory_aspect_returns_its_three_tables(auth_client):
    auth_client.put("/api/memory/memories/guid-1/", MEMORY, format="json")

    body = auth_client.get("/api/changes?aspects=memory").data
    assert len(body["memories"]) == 1
    assert body["companion_memories"] == []
    assert body["memory_audit"] == []


def test_voice_notes_aspect_is_one_table(auth_client):
    auth_client.post("/api/voice_notes/", VOICE_NOTE, format="json")

    body = auth_client.get("/api/changes?aspects=voice_notes").data
    assert len(body["voice_notes"]) == 1
    assert body["voice_notes"][0]["title"] == "a thought"


def test_no_aspects_returns_every_known_one(auth_client):
    auth_client.put("/api/places/home/", PLACE, format="json")
    auth_client.put("/api/memory/memories/guid-1/", MEMORY, format="json")

    body = auth_client.get("/api/changes").data
    # The Phase 2 pair, plus every table on the generic shape.
    for key in ("events", "checklists", "places", "voice_notes", "memories", "bodyweight_logs"):
        assert key in body, key
    assert len(body["places"]) == 1
    assert len(body["memories"]) == 1


def test_the_feed_carries_tombstones(auth_client):
    """Same rule as the per-table routes: a phone that has not synced since
    still has to learn a row is gone."""
    auth_client.put("/api/places/home/", PLACE, format="json")
    auth_client.delete("/api/places/home/")

    rows = auth_client.get("/api/changes?aspects=places").data["places"]
    assert len(rows) == 1
    assert rows[0]["deleted_at"] is not None


def test_an_unknown_aspect_names_the_new_ones_too(auth_client):
    response = auth_client.get("/api/changes?aspects=fleet")
    assert response.status_code == 400
    text = str(response.data)
    assert "fleet" in text
    for known in ("events", "checklists", "places", "voice_notes", "body", "memory"):
        assert known in text, known
