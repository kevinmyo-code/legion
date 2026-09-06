"""`/api/voice_notes` - the aspect whose identity is the server's own id,
and the one where what is ABSENT from the wire shape is the point.

The audio file never leaves the phone (voice-notes ticket 02: "the server
holds text; the file stays on the phone"). `test_the_wire_shape_carries_no_audio_path`
is the guard against a later edit adding one by reflex.
"""
from __future__ import annotations

import uuid

import pytest
from rest_framework.test import APIClient

from legacy.models.notes import VoiceNote

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"
NOTE = {
    "started_at": "2026-09-01T09:00:00Z",
    "ended_at": "2026-09-01T09:32:00Z",
    "title": "Standup",
    "kind": "MEETING",
}


def _create(client, **overrides):
    return client.post("/api/voice_notes/", NOTE | overrides, format="json")


def test_unauthenticated_is_401():
    assert APIClient().get("/api/voice_notes/").status_code == 401
    assert APIClient().post("/api/voice_notes/", NOTE, format="json").status_code == 401


def test_post_creates_and_returns_the_row_as_stored(auth_client):
    response = _create(auth_client)
    assert response.status_code == 201, response.data
    body = response.data
    assert body["title"] == "Standup"
    assert body["kind"] == "MEETING"
    # The column's CHECK pins it to this one literal - see
    # `20260901000100_voice_notes.sql`'s header for why it is not the
    # shared `provenance` enum.
    assert body["provenance"] == "LLM_DERIVED"
    assert body["interrupted"] is False  # DB default, applied when omitted
    assert body["deleted_at"] is None
    assert VoiceNote.objects.filter(pk=body["id"]).exists()


def test_the_wire_shape_carries_no_audio_path(auth_client):
    """`RemoteVoiceNote` has no `audioPath` and `public.voice_notes` has no
    audio column at all - not a nullable one. Nothing here should ever grow
    a field whose name suggests one."""
    created = _create(auth_client).data
    for field in created:
        assert "audio" not in field.lower(), field
        assert "path" not in field.lower(), field
        assert "file" not in field.lower(), field

    # And a caller who sends one is told this server does not know it,
    # rather than having it silently dropped.
    refused = auth_client.post(
        "/api/voice_notes/", NOTE | {"audio_path": "/sdcard/note.m4a"}, format="json"
    )
    assert refused.status_code == 400
    assert "audio_path" in str(refused.data)


def test_put_updates_an_existing_note_and_is_idempotent(auth_client):
    created = _create(auth_client).data
    url = f"/api/voice_notes/{created['id']}/"

    first = auth_client.put(url, NOTE | {"title": "Standup, renamed"}, format="json")
    assert first.status_code == 200
    assert first.data["id"] == created["id"]
    assert first.data["title"] == "Standup, renamed"

    second = auth_client.put(url, NOTE | {"title": "Standup, renamed"}, format="json")
    assert second.status_code == 200
    assert second.data["id"] == created["id"]
    assert VoiceNote.objects.count() == 1


def test_put_to_an_id_the_server_never_issued_does_not_insert(auth_client):
    """Ticket 04's own exceptions table: "the `id` form never inserts". A
    uuid a caller invented is not an identity this server will adopt, and
    the refusal says where to POST instead."""
    invented = uuid.uuid4()
    response = auth_client.put(f"/api/voice_notes/{invented}/", NOTE, format="json")
    assert response.status_code == 404
    text = str(response.data)
    assert "POST" in text and "/api/voice_notes/" in text
    assert VoiceNote.objects.count() == 0


def test_a_summary_with_no_transcript_is_refused_in_words(auth_client):
    """`voice_notes_summary_needs_transcript`. ADR 0041 replaces section
    4's numeric gate here with an anchor chain, and a summary with nothing
    under it is exactly the break that chain exists to prevent."""
    response = _create(auth_client, summary="They agreed to ship on Friday.")
    assert response.status_code == 400
    text = str(response.data)
    assert "transcript" in text
    assert VoiceNote.objects.count() == 0


def test_a_summary_lands_once_a_transcript_is_there(auth_client):
    created = _create(auth_client, transcript="... the whole thing ...").data
    updated = auth_client.put(
        f"/api/voice_notes/{created['id']}/",
        NOTE | {"transcript": "... the whole thing ...", "summary": "Ship Friday."},
        format="json",
    )
    assert updated.status_code == 200
    assert updated.data["summary"] == "Ship Friday."


def test_wrong_kind_is_400_naming_the_allowed_set(auth_client):
    response = _create(auth_client, kind="INTERVIEW")
    assert response.status_code == 400
    text = str(response.data)
    assert "SOLO" in text and "MEETING" in text
    assert VoiceNote.objects.count() == 0


def test_delete_tombstones_and_the_since_feed_still_carries_it(auth_client):
    created = _create(auth_client).data

    assert auth_client.delete(f"/api/voice_notes/{created['id']}/").status_code == 204
    assert VoiceNote.objects.get(pk=created["id"]).deleted_at is not None

    feed = auth_client.get(f"/api/voice_notes/?since={EPOCH}")
    rows = {row["id"]: row for row in feed.data["results"]}
    assert rows[created["id"]]["deleted_at"] is not None

    active = auth_client.get("/api/voice_notes/?active=1")
    assert active.data["results"] == []

    # Idempotent.
    assert auth_client.delete(f"/api/voice_notes/{created['id']}/").status_code == 204


def test_since_feed_omits_notes_changed_before_the_watermark(auth_client):
    _create(auth_client, title="older")
    newer = _create(auth_client, title="newer").data

    feed = auth_client.get(f"/api/voice_notes/?since={newer['updated_at']}")
    assert [row["title"] for row in feed.data["results"]] == ["newer"]
