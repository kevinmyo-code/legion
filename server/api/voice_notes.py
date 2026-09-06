"""`/api/voice_notes` - a recording Kevin deliberately started, as text.

## The audio never leaves the phone, and that is why there is no field for it

ADR 0041 makes a recording Kevin starts first-party content, but ticket 02
of the voice-notes map draws the line elsewhere: "the server holds text;
the file stays on the phone". `public.voice_notes` has no audio column at
all - not a nullable one - and neither does this serializer. There is
nothing here to omit, filter or forget to exclude, which is the point:
`VoiceNotesBackend.kt`'s `RemoteVoiceNote` says the same thing in its own
words ("There is no `audioPath` here, deliberately"), and
`data/local/VoiceNote.audioPath` is the phone-local column that has no
counterpart on this side.

The wire shape is therefore exactly `RemoteVoiceNote`: text, timestamps,
kind, provenance, `interrupted`.

## Identity is the server's own id, so PUT never inserts

Ticket 04's exceptions table groups `voice_notes` with `events` and
`vehicles`: "phone upserts by server `id`, creates by `origin_guid` ... the
`id` form never inserts". Only half of that is available here - **`voice_notes`
has no `origin_guid` column**, confirmed against the live schema on
2026-09-06 and against `20260901000100_voice_notes.sql`, which never
declares one and which `20260826000100_origin_guid.sql` (the migration that
added the column to five tables) predates. So the create path is `POST
/api/voice_notes/`, matching `VoiceNotesBackend.upsert(serverId = null)`,
and `PUT /api/voice_notes/<id>/` updates an existing note only. That
divergence from the ticket's own table is deliberate and is called out in
this ticket's report.

## provenance is a one-value CHECK, not the shared enum

`voice_notes.provenance` is plain `text` pinned by
`voice_notes_provenance_check` to the single literal `LLM_DERIVED` - the
only column in this schema shaped that way. `20260901000100_voice_notes.sql`'s
header comment argues it at length: none of the four `public.provenance`
values is TRUE of a voice note, because ADR 0041 replaces section 4's
numeric gate with an anchor CHAIN (summary anchored by transcript,
transcript anchored by audio). The serializer states the same single value
rather than importing `Provenance`, so nothing here can quietly widen it.
"""
from __future__ import annotations

from rest_framework import serializers

from api.synced import SyncedModelViewSet, SyncedSerializer, choice_error
from legacy.models.notes import VoiceNote

# `legacy/CONSTRAINTS.md`'s own `## voice_notes` section.
KIND_CHOICES = ("SOLO", "MEETING")
LLM_DERIVED = "LLM_DERIVED"


class VoiceNoteSerializer(SyncedSerializer):
    """Field-for-field `RemoteVoiceNote`. See this module's doc comment for
    the audio column that is absent from both."""

    default_provenance = LLM_DERIVED

    class Meta:
        model = VoiceNote
        fields = [
            "id",
            "started_at",
            "ended_at",
            "title",
            "summary",
            "transcript",
            "kind",
            "provenance",
            "interrupted",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            # `interrupted` has a DB default of false and is explicit rather
            # than inferred from `ended_at`'s nullness - see that column's
            # own comment in the migration. Optional on the wire so a caller
            # may omit it and get the default an INSERT with the column left
            # out would have produced.
            "interrupted": {"required": False, "default": False},
        }

    def validate_kind(self, value: str) -> str:
        if value not in KIND_CHOICES:
            raise choice_error("kind", value, KIND_CHOICES)
        return value

    def validate(self, attrs: dict) -> dict:
        """`voice_notes_summary_needs_transcript`: never a summary without
        the transcript it was built from. Checked against the row as it
        WOULD BE after this write, not against the payload alone - a PUT
        that sets only a summary on a note that already has a transcript is
        legal, and one that sets a summary on a note that has none is not.
        """
        transcript = attrs.get("transcript", getattr(self.instance, "transcript", None))
        summary = attrs.get("summary", getattr(self.instance, "summary", None))
        if summary is not None and transcript is None:
            raise serializers.ValidationError(
                "Nothing was written. A voice note cannot carry a summary with no transcript "
                "to have summarized: the transcript is what anchors the summary (ADR 0041). "
                "Store the transcript first."
            )
        return attrs


class VoiceNoteViewSet(SyncedModelViewSet):
    aspect = "voice_notes"
    table = "voice_notes"
    serializer_class = VoiceNoteSerializer
    identity_field = "id"
    identity_url_converter = "uuid"
    identity_is_primary_key = True
