package com.kevin.legion.backend.engine

import com.kevin.legion.backend.RemoteVoiceNote
import com.kevin.legion.backend.VoiceNoteFields
import com.kevin.legion.backend.VoiceNotesBackend
import com.kevin.legion.backend.VoiceNotesIncrementalPull
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val VOICE_NOTES_PATH = "/api/voice_notes/"

private fun vnTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun vnTsOrNull(ms: Long?): String? = ms?.let { vnTs(it) }
private fun vnParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun vnParseTsOrNull(s: String?): Long? = s?.let { vnParseTs(it) }

/**
 * One `public.voice_notes` row exactly as `server/api/voice_notes.py`'s `VoiceNoteSerializer`
 * renders it - **measured against the live engine on 2026-09-07**, not inferred from the Python:
 * `{"id": "ed4f9548-...", "started_at": "2026-09-04T22:50:25.899000Z", "ended_at": "...",
 * "title": "Cooking Instructions for Sauteed Apples", "summary": "...", "transcript": "...",
 * "kind": "SOLO", "provenance": "LLM_DERIVED", "interrupted": false, "created_at": "...",
 * "updated_at": "...", "deleted_at": null}`.
 *
 * **There is no audio field here, and there is none on the server either.** ADR 0041 makes a
 * recording Kevin starts first-party content, and the voice-notes map's ticket 02 still draws the
 * line at the file: "the server holds text; the file stays on the phone".
 * [com.kevin.legion.data.local.VoiceNote.audioPath] has no counterpart in
 * `public.voice_notes`, in `VoiceNoteSerializer.Meta.fields`, in [RemoteVoiceNote] or in
 * [VoiceNoteFields] - not a nullable one, not a filtered one. Nothing here has to remember to
 * exclude it, which is the stronger guarantee CLAUDE.md section 7 asks for by name.
 */
@Serializable
private data class DjangoVoiceNoteRow(
    val id: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val transcript: String? = null,
    val kind: String,
    // Pinned server-side by `voice_notes_provenance_check` to the single literal LLM_DERIVED, so
    // the default here can never be the wrong value - it exists only so a hand-written fixture
    // that omits the column still decodes. See RemoteVoiceNote.provenance's own doc comment.
    val provenance: String = "LLM_DERIVED",
    val interrupted: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemote() = RemoteVoiceNote(
        serverId = id,
        startedAtMs = vnParseTs(startedAt),
        endedAtMs = vnParseTsOrNull(endedAt),
        title = title,
        summary = summary,
        transcript = transcript,
        kind = kind,
        provenance = provenance,
        interrupted = interrupted,
        updatedAtMs = vnParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/**
 * The `POST /api/voice_notes/` and `PUT /api/voice_notes/<id>/` body - every writable column
 * `VoiceNoteSerializer` declares, and nothing else (`SyncedSerializer.to_internal_value` answers
 * a 400 naming an unknown field rather than dropping it).
 *
 * Every nullable property is deliberately REQUIRED (no `= null` default), for the reason
 * [engineSyncedJson]'s own doc comment and `SupabaseVoiceNotesBackend`'s `VoiceNoteUpsertDto`
 * both spell out: an omitted key leaves the stored value in place, so clearing a title would
 * silently not happen.
 */
@Serializable
private data class DjangoVoiceNoteWrite(
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String?,
    val title: String?,
    val summary: String?,
    val transcript: String?,
    val kind: String,
    val interrupted: Boolean,
) {
    companion object {
        fun from(fields: VoiceNoteFields) = DjangoVoiceNoteWrite(
            startedAt = vnTs(fields.startedAtMs),
            endedAt = vnTsOrNull(fields.endedAtMs),
            title = fields.title,
            summary = fields.summary,
            transcript = fields.transcript,
            kind = fields.kind,
            interrupted = fields.interrupted,
        )
    }
}

/**
 * [VoiceNotesBackend] over the household Django engine (`server/api/voice_notes.py` on
 * `server/api/synced.py`'s generic shape).
 *
 * **The create/update fork is real here and is not the `origin_guid` upsert body and memory use.**
 * `voice_notes` has no `origin_guid` column - confirmed against the live schema by that module's
 * own doc comment, and by `20260901000100_voice_notes.sql`, which predates the migration that
 * added the column to five other tables. So `identity_field = "id"`,
 * `identity_is_primary_key = True`, and `PUT` to an id the server never minted answers 404 in
 * words rather than inventing a row under a caller-chosen uuid. That is exactly
 * [VoiceNotesBackend.upsert]'s own `serverId == null` fork, so nothing about the interface
 * changes; POST creates, PUT updates.
 *
 * **A summary without a transcript is refused by the engine, in words** (`VoiceNoteSerializer.validate`,
 * mirroring the `voice_notes_summary_needs_transcript` CHECK): the transcript is what anchors the
 * summary under ADR 0041, so storing one without the other would be storing a claim with its own
 * evidence discarded. The refusal arrives here as [EngineFailure.Refused] carrying the server's
 * sentence verbatim - [prefixEngineFailure] deliberately does not prefix that branch.
 */
class DjangoVoiceNotesBackend(http: EngineHttp) : VoiceNotesBackend, VoiceNotesIncrementalPull {

    private val table = EngineSyncedTable(
        http = http,
        path = VOICE_NOTES_PATH,
        rowSerializer = DjangoVoiceNoteRow.serializer(),
        idOf = { it.id },
    )

    override suspend fun fetchActive(): Result<List<RemoteVoiceNote>> =
        translatingEngineCall("load your recordings") {
            table.fetchActive().map { it.toRemote() }
        }

    /**
     * The incremental pull, `GET /api/voice_notes/?since=<iso>` - **tombstones included**, paged.
     *
     * **This is NOT on [VoiceNotesBackend], and the reason has changed.** This comment used to say
     * the function had no caller at all - "there is no `VoiceNotesSync`... the capability is built
     * and tested here, on the transport that has it, so the sync this aspect still owes can be
     * written against a pull that already exists". [com.kevin.legion.backend.VoiceNotesSync] is
     * that owed sync and it exists now (built 2026-09-07, after the A25 run found this function
     * with no caller and voice notes with no server-to-phone path at all). What has NOT changed is
     * why it stays off [VoiceNotesBackend]: adding it there would oblige
     * [com.kevin.legion.backend.SupabaseVoiceNotesBackend] to grow an implementation with no caller
     * on that transport. It is declared on [VoiceNotesIncrementalPull] instead, which only this
     * class implements - see that interface's own doc comment.
     *
     * **Tombstones are not filtered.** A soft-deleted row is precisely what a merge's tombstone
     * branch exists to receive - the bug [com.kevin.legion.backend.EventsBackend.fetchChangedSince]'s
     * own doc comment traces at length for the `fetchActive`-only shape, avoided here from the
     * start.
     */
    override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteVoiceNote>> =
        translatingEngineCall("load changed recordings") {
            table.fetchChangedSince(Instant.ofEpochMilli(sinceMs).toString()).map { it.toRemote() }
        }

    override suspend fun upsert(serverId: String?, fields: VoiceNoteFields): Result<RemoteVoiceNote> =
        translatingEngineCall(if (serverId == null) "save that recording" else "update that recording") {
            val body = engineSyncedJson.encodeToString(
                DjangoVoiceNoteWrite.serializer(),
                DjangoVoiceNoteWrite.from(fields),
            )
            val row = if (serverId == null) table.post(body) else table.put(serverId, body)
            row.toRemote()
        }

    /** `DELETE /api/voice_notes/<id>/`. `Result.success(false)` means only "no row with that id"
     * (404) - see [DjangoPlacesBackend.softDelete]'s own doc comment for why this transport cannot
     * report "was already tombstoned" separately. **Text only**: there is nothing to delete on the
     * audio side because nothing of the audio was ever uploaded, so the caller still owns
     * [com.kevin.legion.data.local.VoiceNoteStore]'s local file-and-row cascade. */
    override suspend fun softDelete(serverId: String): Result<Boolean> =
        deletingEngineRow("remove that recording") { table.deleteRow(serverId) }
}
