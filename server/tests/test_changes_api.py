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
