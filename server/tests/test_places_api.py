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
