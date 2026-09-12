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


def test_repeated_aspects_params_work_as_well_as_the_comma_form(auth_client):
    """**The regression guard for a silent empty calendar, 2026-09-12.**

    `aspects` is declared in `openapi.yaml` as `type: array` with no
    `style`/`explode`, so OpenAPI's default applies: `explode: true`, meaning
    repeated params. A generated TypeScript client sent exactly that, and this
    endpoint read `query_params.get("aspects")` - which returns only the LAST
    occurrence - so `?aspects=events&aspects=checklists` asked for both and got
    `["checklists"]`.

    The response was a 200 with no `events` key at all, which the web app
    rendered as "Nothing on the calendar today" on every single day while the
    database held 467 events. **A contract mismatch that returns a cheerful 200
    is worse than one that 400s**, which is why both spellings are pinned here
    rather than only the one the server happened to implement.
    """
    auth_client.post("/api/events", {"title": "an event"}, format="json")
    auth_client.post("/api/checklists/", {"name": "a checklist"}, format="json")

    repeated = auth_client.get("/api/changes?aspects=events&aspects=checklists")
    assert repeated.status_code == 200
    assert "events" in repeated.data, "repeated params dropped the first aspect"
    assert "checklists" in repeated.data
    assert len(repeated.data["events"]) == 1

    # The documented comma form keeps working - this fix widens what is
    # accepted, it does not move the contract.
    comma = auth_client.get("/api/changes?aspects=events,checklists")
    assert set(comma.data.keys()) == set(repeated.data.keys())

    # And the two spellings may be mixed, which is what a client assembling a
    # query from several places will eventually send.
    mixed = auth_client.get("/api/changes?aspects=events&aspects=checklists,body")
    assert "events" in mixed.data
    assert "checklists" in mixed.data
    assert "bodyweight_logs" in mixed.data


def test_unknown_aspect_is_400_naming_the_allowed_set(auth_client):
    """**This test used to send `aspects=ledger`**, then `aspects=fleet`, and
    both are real aspects now - ledger when its routes landed, fleet when its
    own did. Each time, this test failing was the correct answer and the
    example moved on.

    It has now run out of unrouted aspects: fleet was the last one, so every
    aspect this app has is routed and no real name is a genuine unknown any
    more. The example is therefore a name that is not an aspect and is not
    going to become one, rather than the next thing on the roadmap - which
    also means this test stops needing an edit every time a ticket lands.
    """
    response = auth_client.get("/api/changes?aspects=telemetry")
    assert response.status_code == 400
    assert "telemetry" in str(response.data)
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


def test_server_time_is_a_boundary_in_the_databases_own_clock(auth_client):
    """`server_time` is the value a client stores as "everything before here
    is already mine", and it is compared against `updated_at` columns that
    Postgres stamps. So it has to come from Postgres, and this test pins
    that PROPERTY rather than any timing: a row written before the
    watermark is absent from a feed keyed on it, and a row written after it
    is present. Both halves hold because all three timestamps come from one
    monotonic clock - `statement_timestamp()` advances per statement even
    inside pytest's single wrapping transaction, so this needs no
    `transaction=True`.

    `ChangesView` used to compute `server_time` with `timezone.now()`, on a
    machine measured 0.53s away from the database's clock (2026-09-06,
    Python behind). Behind is the harmless direction: the client re-fetches
    rows it has. Ahead, `server_time` lands in the database's future and
    every row written inside that window is skipped by the next pull,
    permanently and silently, because the client was told it already had
    them. That is the failure this asserts against; see `api/changes.py`'s
    own comment for the whole reasoning.
    """
    from urllib.parse import quote

    auth_client.post("/api/events", {"title": "before the watermark"}, format="json")

    watermark = auth_client.get("/api/changes?aspects=events").data["server_time"]

    auth_client.post("/api/events", {"title": "after the watermark"}, format="json")

    feed = auth_client.get(f"/api/changes?aspects=events&since={quote(watermark)}")
    assert feed.status_code == 200
    titles = {row["title"] for row in feed.data["events"]}
    assert "after the watermark" in titles
    # The discriminating half. A Python-clock watermark that trails the
    # database's would let this row back into the feed; one drawn from
    # Postgres cannot, because the row's own `updated_at` was stamped by
    # the same clock, one statement earlier.
    assert "before the watermark" not in titles


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
    """The refusal lists everything that DOES exist, so a client that guessed
    wrong is not left guessing again. Every aspect ever added has to appear
    here, which is what makes this test the one that fails when someone routes
    a table and forgets `api/registry.py`.

    **This sent `aspects=fleet` until fleet was routed**, which is when it
    failed and was corrected - see the sibling test above for why the unknown
    name is now one that will never be an aspect."""
    response = auth_client.get("/api/changes?aspects=telemetry")
    assert response.status_code == 400
    text = str(response.data)
    assert "telemetry" in text
    for known in (
        "events",
        "checklists",
        "places",
        "voice_notes",
        "body",
        "memory",
        # The ledger/pantry ticket's three.
        "ledger",
        "pantry",
        "ingest",
        # The fleet ticket's one. `obd_samples` is deliberately NOT an aspect
        # name and never appears in this list: it is a TABLE inside fleet, and
        # one this feed excludes - `api/registry.py` and `api/changes.py` both
        # say why.
        "fleet",
    ):
        assert known in text, known
