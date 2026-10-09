"""`/api/places` - what is TRUE OF PLACES and not of the other eleven
tables in `tests/test_synced_contract.py`: the label is the identity, and a
re-tagged label brings a forgotten place back.
"""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from legacy.models.places import Place

pytestmark = pytest.mark.django_db

HOME = {"latitude": 29.7604, "longitude": -95.3698}


def test_the_url_label_is_the_identity_and_the_body_cannot_override_it(auth_client):
    response = auth_client.put(
        "/api/places/home/", HOME | {"label": "somewhere else"}, format="json"
    )
    assert response.status_code == 200
    assert response.data["label"] == "home"
    assert Place.objects.filter(label="home").count() == 1
    assert not Place.objects.filter(label="somewhere else").exists()


def test_re_tagging_a_forgotten_label_revives_it(auth_client):
    """The one aspect where a PUT clears an existing tombstone.
    `SupabasePlacesBackend`'s `PlaceUpsertDto` puts an explicit
    `deleted_at: null` on the wire for exactly this - see that class's own
    doc comment, and `api/synced.py`'s `put_revives_tombstone`."""
    created = auth_client.put("/api/places/home/", HOME, format="json").data
    auth_client.delete("/api/places/home/")
    assert Place.objects.get(label="home").deleted_at is not None

    revived = auth_client.put(
        "/api/places/home/", {"latitude": 1.0, "longitude": 2.0}, format="json"
    )
    assert revived.status_code == 200
    assert revived.data["id"] == created["id"]  # the same row, not a second one
    assert revived.data["deleted_at"] is None
    assert revived.data["latitude"] == 1.0


def test_a_blank_label_is_refused_in_words(auth_client):
    """`places` has `check (length(trim(label)) > 0)`, and a label of
    spaces would also make a geofence requestId nothing at all."""
    response = auth_client.put("/api/places/%20%20/", HOME, format="json")
    assert response.status_code == 400
    assert "label" in str(response.data)


def test_longitude_out_of_range_is_400_naming_the_bounds(auth_client):
    response = auth_client.put(
        "/api/places/home/", {"latitude": 29.7, "longitude": 999.0}, format="json"
    )
    assert response.status_code == 400
    text = str(response.data)
    assert "longitude" in text and "-180" in text and "180" in text
    assert not Place.objects.filter(label="home").exists()


def test_provenance_is_the_servers_to_state(auth_client):
    """A caller cannot claim a row came through the section 4 gate. Every
    row this API authors is USER, and `provenance` is read-only, so a
    caller stating something else is told it is not a field it may set."""
    response = auth_client.put(
        "/api/places/home/", HOME | {"provenance": "DETERMINISTIC"}, format="json"
    )
    # `provenance` IS a known field - it is simply read-only - so this is
    # not the unknown-field 400; the value is ignored and the server's own
    # is stored.
    assert response.status_code == 200
    assert response.data["provenance"] == "USER"


def test_unauthenticated_delete_is_401():
    """The contract module checks GET and PUT for all twelve tables; the
    permission classes are global, so one DELETE here is enough to show the
    third verb is not an exception."""
    assert APIClient().delete("/api/places/home/").status_code == 401


def test_there_is_no_post_route_for_a_client_keyed_table(auth_client):
    """One way in, not two. Where the client mints the identity, PUT is
    both create and update - a POST would be a second create path that
    could not be idempotent, since it has no identity in the URL to be
    idempotent ON."""
    response = auth_client.post("/api/places/", HOME | {"label": "home"}, format="json")
    assert response.status_code == 405


def test_a_label_longer_than_thirty_characters_is_refused_in_words(auth_client):
    """django-engine ticket 14. This cap lived only in
    `location/PlaceController.normalizeLabel` until 2026-09-07, so a second
    Android app (the head unit is a separate app, ADR 0044) simply would not
    have had it. The refusal names the length, the limit, and what a label
    that long usually is."""
    long_label = "a" * 31
    response = auth_client.put(f"/api/places/{long_label}/", HOME, format="json")

    assert response.status_code == 400
    text = str(response.data)
    assert "31" in text and "30" in text
    assert "Nothing was saved" in text
    # Nothing partial: the refusal is before any write.
    assert not Place.objects.filter(label=long_label).exists()


def test_a_thirty_character_label_is_accepted(auth_client):
    """The boundary is inclusive - `PlaceController.normalizeLabel` refused
    on `s.length > 30`, so thirty is a legal name and thirty-one is not.
    Without this the cap could quietly become 29 and no test would notice."""
    label = "a" * 30
    response = auth_client.put(f"/api/places/{label}/", HOME, format="json")

    assert response.status_code == 200, response.data
    assert response.data["label"] == label


# -- places by address (2026-10-09) --------------------------------------

KATY = "123 Main St, Katy, TX 77494"


def test_an_address_rides_with_the_place_and_reads_back(auth_client):
    response = auth_client.put("/api/places/home/", HOME | {"address": KATY}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["address"] == KATY
    listed = auth_client.get("/api/places/?active=1").data["results"]
    assert listed[0]["address"] == KATY


def test_a_put_without_an_address_leaves_the_stored_one_alone(auth_client):
    """A phone one release behind never sends `address`; re-tagging from it
    must not wipe what a newer phone saved."""
    auth_client.put("/api/places/home/", HOME | {"address": KATY}, format="json")
    response = auth_client.put("/api/places/home/", HOME, format="json")
    assert response.status_code == 200
    assert response.data["address"] == KATY


def test_a_null_or_blank_address_is_stored_as_none(auth_client):
    auth_client.put("/api/places/home/", HOME | {"address": KATY}, format="json")
    cleared = auth_client.put("/api/places/home/", HOME | {"address": None}, format="json")
    assert cleared.data["address"] is None
    blank = auth_client.put("/api/places/gym/", HOME | {"address": "   "}, format="json")
    assert blank.status_code == 200
    assert blank.data["address"] is None


def test_an_over_long_address_is_refused_in_words(auth_client):
    response = auth_client.put("/api/places/home/", HOME | {"address": "x" * 301}, format="json")
    assert response.status_code == 400
    assert "Nothing was saved" in str(response.data)
    assert not Place.objects.filter(label="home").exists()


def test_rename_keeps_coordinates_and_address_and_tombstones_the_old_label(auth_client):
    auth_client.put("/api/places/home/", HOME | {"address": KATY}, format="json")
    response = auth_client.post("/api/places/home/rename/", {"to": "katie house"}, format="json")

    assert response.status_code == 200, response.data
    place = response.data["place"]
    assert place["label"] == "katie house"
    assert place["latitude"] == HOME["latitude"]
    assert place["longitude"] == HOME["longitude"]
    assert place["address"] == KATY
    assert place["deleted_at"] is None
    assert "Renamed" in response.data["detail"]
    assert Place.objects.get(label="home").deleted_at is not None
    # The feed carries both halves, so another phone drops "home" and gains the new one.
    feed = {r["label"]: r for r in auth_client.get("/api/places/").data["results"]}
    assert feed["home"]["deleted_at"] is not None
    assert feed["katie house"]["deleted_at"] is None


def test_rename_onto_a_live_label_is_refused_and_changes_nothing(auth_client):
    auth_client.put("/api/places/home/", HOME, format="json")
    auth_client.put("/api/places/work/", {"latitude": 1.0, "longitude": 2.0}, format="json")
    response = auth_client.post("/api/places/home/rename/", {"to": "work"}, format="json")

    assert response.status_code == 409
    assert "already a saved place called 'work'" in response.data["detail"]
    assert Place.objects.get(label="home").deleted_at is None
    assert Place.objects.get(label="work").latitude == 1.0


def test_rename_to_the_same_label_is_refused(auth_client):
    auth_client.put("/api/places/home/", HOME, format="json")
    response = auth_client.post("/api/places/home/rename/", {"to": "home"}, format="json")
    assert response.status_code == 409
    assert Place.objects.get(label="home").deleted_at is None


def test_rename_of_a_missing_place_is_404(auth_client):
    response = auth_client.post("/api/places/nowhere/rename/", {"to": "x"}, format="json")
    assert response.status_code == 404
    assert "no saved place called 'nowhere'" in response.data["detail"]


def test_rename_onto_a_forgotten_label_revives_that_row(auth_client):
    """`(household, label)` is unique tombstones included, so the new name
    reuses its own old row rather than colliding with it."""
    old = auth_client.put("/api/places/gym/", {"latitude": 5.0, "longitude": 6.0}, format="json")
    auth_client.delete("/api/places/gym/")
    auth_client.put("/api/places/home/", HOME | {"address": KATY}, format="json")

    response = auth_client.post("/api/places/home/rename/", {"to": "gym"}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["place"]["id"] == old.data["id"]
    assert response.data["place"]["latitude"] == HOME["latitude"]
    assert response.data["place"]["address"] == KATY
    assert Place.objects.filter(label="gym").count() == 1


def test_rename_refuses_a_blank_or_over_long_new_label(auth_client):
    auth_client.put("/api/places/home/", HOME, format="json")
    blank = auth_client.post("/api/places/home/rename/", {"to": "  "}, format="json")
    long = auth_client.post("/api/places/home/rename/", {"to": "a" * 31}, format="json")
    assert blank.status_code == 400
    assert long.status_code == 400
    assert Place.objects.get(label="home").deleted_at is None


def test_rename_moves_live_place_reminders_with_it(auth_client):
    from legacy.models.dates import Event

    auth_client.put("/api/places/home/", HOME, format="json")
    made = auth_client.post(
        "/api/events",
        {"title": "grab the mail", "trigger_place_label": "home"},
        format="json",
    )
    assert made.status_code == 201, made.data

    response = auth_client.post("/api/places/home/rename/", {"to": "katie house"}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["reminders_moved"] == 1
    assert "1 reminder moved" in response.data["detail"]
    assert Event.objects.get(id=made.data["id"]).trigger_place_label == "katie house"


def test_rename_is_household_scoped(auth_client, token_b):
    """Household B cannot rename A's place, and its own same-named place is
    the only one it can touch."""
    auth_client.put("/api/places/home/", HOME, format="json")
    response = token_b.post("/api/places/home/rename/", {"to": "mine"}, format="json")
    assert response.status_code == 404
    assert Place.objects.get(label="home").deleted_at is None


def test_unauthenticated_rename_is_401():
    response = APIClient().post("/api/places/home/rename/", {"to": "x"}, format="json")
    assert response.status_code == 401
