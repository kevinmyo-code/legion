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
