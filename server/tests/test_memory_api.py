"""`/api/memory/<table>` - the two departures `MemoryBackend.kt` names, and
nothing else that `tests/test_synced_contract.py` already covers for all
three tables.

1. `memory_audit` is append-only: no DELETE route at all.
2. `companion_memories` carries no embedding, on either side of the wire.
"""
from __future__ import annotations

import pytest

from api.memory import CompanionMemorySerializer
from legacy.models.memory import CompanionMemory, MemoryAudit

pytestmark = pytest.mark.django_db

AUDIT = {
    "event": "written",
    "store": "memories",
    "detail": "remembered the garage code",
    "ref_id": 7,
    "logged_at": "2026-09-01T10:00:00Z",
}
COMPANION = {
    "vehicle_id": "AA:BB:CC:DD:EE:FF",
    "text": "he takes the long way home when it rains",
    "category": "driver",
    "source": "reflection",
    "importance": 7,
    "logged_at": "2026-09-01T10:00:00Z",
}


def test_memory_audit_has_no_delete_route_and_says_why(auth_client):
    """`MemoryBackend` has no `softDeleteMemoryAudit` - "No delete function
    for `RemoteMemoryAudit` at all" - because a trail with rows removed
    from it is not a trail. The refusal is a 405 that says that, not DRF's
    bare default and not a silent success."""
    created = auth_client.put("/api/memory/memory_audit/guid-1/", AUDIT, format="json")
    assert created.status_code == 200, created.data

    refused = auth_client.delete("/api/memory/memory_audit/guid-1/")
    assert refused.status_code == 405
    text = str(refused.data)
    assert "append-only" in text
    assert "Nothing was deleted" in text

    # And the row is untouched - not tombstoned by a route that half ran.
    assert MemoryAudit.objects.get(origin_guid="guid-1").deleted_at is None


def test_memory_audit_still_accepts_an_upsert(auth_client):
    """Append-only means no DELETE, not read-only: the phone's backfill
    pushes these rows in, so PUT has to work."""
    first = auth_client.put("/api/memory/memory_audit/guid-1/", AUDIT, format="json")
    assert first.status_code == 200
    again = auth_client.put("/api/memory/memory_audit/guid-1/", AUDIT, format="json")
    assert again.status_code == 200
    assert MemoryAudit.objects.count() == 1


def test_companion_memory_carries_no_embedding(auth_client):
    """`RemoteCompanionMemory`'s own doc comment: no `embeddingVector` /
    `embeddingModel`, because the embedding never leaves the device.
    `public.companion_memories` has no such column either, so there is
    nothing here to filter - which is the stronger guarantee. This test
    fails if a later edit adds one."""
    fields = set(CompanionMemorySerializer().fields)
    assert not [field for field in fields if "embed" in field]
    assert not [field for field in fields if "vector" in field]

    stored = auth_client.put(
        "/api/memory/companion_memories/guid-1/", COMPANION, format="json"
    )
    assert stored.status_code == 200
    assert not [field for field in stored.data if "embed" in field]

    # A caller that sends one is told this server does not know it - the
    # value is never quietly dropped on the floor, which would look
    # identical to a successful write.
    refused = auth_client.put(
        "/api/memory/companion_memories/guid-2/",
        COMPANION | {"embedding_vector": [0.1, 0.2]},
        format="json",
    )
    assert refused.status_code == 400
    assert "embedding_vector" in str(refused.data)


def test_importance_defaults_to_the_column_default_when_omitted(auth_client):
    body = {key: value for key, value in COMPANION.items() if key != "importance"}
    response = auth_client.put(
        "/api/memory/companion_memories/guid-1/", body, format="json"
    )
    assert response.status_code == 200, response.data
    assert response.data["importance"] == 5


def test_importance_out_of_range_is_400_naming_the_bounds(auth_client):
    response = auth_client.put(
        "/api/memory/companion_memories/guid-1/", COMPANION | {"importance": 11}, format="json"
    )
    assert response.status_code == 400
    text = str(response.data)
    assert "importance" in text and "1" in text and "10" in text
    assert CompanionMemory.objects.count() == 0


def test_a_bad_source_is_400_naming_the_allowed_set(auth_client):
    response = auth_client.put(
        "/api/memory/companion_memories/guid-1/", COMPANION | {"source": "invented"}, format="json"
    )
    assert response.status_code == 400
    text = str(response.data)
    for allowed in ("consolidated", "reflection", "stated"):
        assert allowed in text


# django-engine ticket 14: the recall rule, moved off
# `CompanionMemoryDao.getRecallScan`'s WHERE clause and onto this server.
JEEP = "AA:BB:CC:DD:EE:FF"
OUTLANDER = "car:11111111-2222-3333-4444-555555555555"


def _store(client, guid, **overrides):
    response = client.put(
        f"/api/memory/companion_memories/{guid}/", COMPANION | overrides, format="json"
    )
    assert response.status_code == 200, response.data
    return response.data


def _texts(response):
    return {row["text"] for row in response.data["results"]}


def test_a_car_anchored_memory_is_not_returned_for_another_vehicle(auth_client):
    """The defect this ticket names: Django served every row unfiltered, so
    a second client recalled the Outlander's memories while in the Jeep."""
    _store(
        auth_client, "jeep-car", vehicle_id=JEEP, category="car_anchored", text="jeep oil at 60k"
    )
    _store(
        auth_client,
        "outlander-car",
        vehicle_id=OUTLANDER,
        category="car_anchored",
        text="outlander needs a cabin filter",
    )

    scan = auth_client.get("/api/memory/companion_memories/", {"vehicle": JEEP})

    assert scan.status_code == 200
    assert _texts(scan) == {"jeep oil at 60k"}


def test_driver_and_relationship_memories_cross_every_vehicle(auth_client):
    """The other half of the same rule, and the bug that produced it:
    scoping the whole table by vehicle stranded 46 memories about Kevin the
    moment the Jeep became the active car. A person does not change car to
    car."""
    _store(
        auth_client,
        "driver-1",
        vehicle_id=OUTLANDER,
        category="driver",
        text="he likes bossa nova",
    )
    _store(
        auth_client,
        "rel-1",
        vehicle_id=OUTLANDER,
        category="relationship",
        text="he calls his mother on sundays",
    )
    _store(auth_client, "car-1", vehicle_id=OUTLANDER, category="car_anchored", text="cabin filter")

    scan = auth_client.get("/api/memory/companion_memories/", {"vehicle": JEEP})

    assert _texts(scan) == {"he likes bossa nova", "he calls his mother on sundays"}


def test_omitting_the_vehicle_returns_every_row_because_this_is_also_the_replica_feed(auth_client):
    """Stated as a test so it cannot be "fixed" by someone reading the
    filter and assuming it should be the default. The Room replica is whole
    on purpose - the driver-facing memory screen reads across all cars, and
    a tombstone for another car's row still has to arrive. See
    `api/memory.py`'s module doc."""
    _store(
        auth_client, "jeep-car", vehicle_id=JEEP, category="car_anchored", text="jeep oil at 60k"
    )
    _store(
        auth_client, "out-car", vehicle_id=OUTLANDER, category="car_anchored", text="cabin filter"
    )

    everything = auth_client.get("/api/memory/companion_memories/")

    assert _texts(everything) == {"jeep oil at 60k", "cabin filter"}


def test_the_vehicle_filter_composes_with_active_and_since(auth_client):
    """`?vehicle=` narrows the same feed `?active=1` narrows, rather than
    replacing it - a tombstoned row for THIS vehicle is still gone."""
    _store(
        auth_client, "jeep-car", vehicle_id=JEEP, category="car_anchored", text="jeep oil at 60k"
    )
    _store(auth_client, "jeep-old", vehicle_id=JEEP, category="car_anchored", text="jeep old fact")
    auth_client.delete("/api/memory/companion_memories/jeep-old/")

    live = auth_client.get(
        "/api/memory/companion_memories/", {"vehicle": JEEP, "active": "1"}
    )

    assert _texts(live) == {"jeep oil at 60k"}


def test_a_blank_vehicle_is_read_as_not_asking_rather_than_as_a_vehicle(auth_client):
    """`?vehicle=` with nothing after it is a caller who did not supply the
    fact, not a caller in a car named "". Same posture `api/synced.py` takes
    for `?active=`: a caller who cannot be understood sees too much, never
    silently nothing - which for THIS parameter is also the safe direction,
    since the alternative is a replica that quietly loses rows."""
    _store(
        auth_client, "jeep-car", vehicle_id=JEEP, category="car_anchored", text="jeep oil at 60k"
    )

    blank = auth_client.get("/api/memory/companion_memories/", {"vehicle": "   "})

    assert _texts(blank) == {"jeep oil at 60k"}


def test_the_vehicle_filter_is_only_on_companion_memories(auth_client):
    """`memories` has no `vehicle_id` column at all and `memory_audit` is a
    trail, not a recall source. A `?vehicle=` on either is an unknown query
    parameter and is ignored, exactly as an unknown one is everywhere else
    in this API - the unknown-FIELD refusal is about write bodies, not query
    strings."""
    stored = auth_client.put(
        "/api/memory/memories/guid-1/",
        {"text": "the garage code is 4417", "logged_at": "2026-09-01T10:00:00Z"},
        format="json",
    )
    assert stored.status_code == 200, stored.data

    scan = auth_client.get("/api/memory/memories/", {"vehicle": JEEP})
    assert scan.status_code == 200
    assert _texts(scan) == {"the garage code is 4417"}
