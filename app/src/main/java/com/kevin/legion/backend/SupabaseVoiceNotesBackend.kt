package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val VOICE_NOTES_TABLE = "voice_notes"

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }

/**
 * The wire shape sent by [SupabaseVoiceNotesBackend.upsert] - one row worth of every writable
 * column on `public.voice_notes`. Every nullable property here is deliberately REQUIRED (no
 * `= null` default), same trick [EventUpsertDto] documents at length: kotlinx-serialization's
 * `encodeDefaults = false` omits a property equal to its declared default, and null is still a
 * default, so an un-set nullable field would silently vanish from the outgoing JSON and a genuine
 * clear-to-null (renaming away a title, for instance) would leave the OLD value sitting server-side
 * untouched. Forcing every field onto the wire on every write reproduces whole-row-replace
 * semantics, matching [VoiceNoteFields]'s own "every writable column, always" contract.
 */
@Serializable
private data class VoiceNoteUpsertDto(
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String?,
    val title: String?,
    val summary: String?,
    val transcript: String?,
    val kind: String,
    val interrupted: Boolean,
) {
    companion object {
        fun from(fields: VoiceNoteFields) = VoiceNoteUpsertDto(
            startedAt = tsOrNull(fields.startedAtMs)
                ?: error("VoiceNoteFields.startedAtMs must be non-null; it is not a nullable column"),
            endedAt = tsOrNull(fields.endedAtMs),
            title = fields.title,
            summary = fields.summary,
            transcript = fields.transcript,
            kind = fields.kind,
            interrupted = fields.interrupted,
        )
    }
}

/** The wire shape read back off `public.voice_notes` for every operation. */
@Serializable
private data class VoiceNoteRowDto(
    val id: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val transcript: String? = null,
    val kind: String,
    val provenance: String = "LLM_DERIVED",
    val interrupted: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteVoiceNote() = RemoteVoiceNote(
        serverId = id,
        startedAtMs = parseTs(startedAt),
        endedAtMs = parseTsOrNull(endedAt),
        title = title,
        summary = summary,
        transcript = transcript,
        kind = kind,
        provenance = provenance,
        interrupted = interrupted,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for a soft-delete PATCH - a genuine partial update, touching only the tombstone
 * column, same shape as [EventDeleteDto]. */
@Serializable
private data class VoiceNoteDeleteDto(
    @SerialName("deleted_at") val deletedAt: String,
)

/**
 * [VoiceNotesBackend]'s real implementation over Postgrest, against `public.voice_notes`
 * (`supabase/migrations/20260901000100_voice_notes.sql`). This is the deliberately untested seam
 * ticket 02 leaves behind, same posture as [SupabaseEventsBackend] and
 * [SupabasePlacesBackend]/[SupabasePantryBackend] before it - exercising it for real needs a live
 * project, and nothing calls this class yet (ticket 04 wires the controller that does).
 * [VoiceNotesBackend] is the fake-friendly interface; every branch here does nothing but translate
 * exceptions and decode DTOs.
 */
class SupabaseVoiceNotesBackend(private val client: SupabaseClient) : VoiceNotesBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(VoiceNotesBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(VoiceNotesBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(VoiceNotesBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActive(): Result<List<RemoteVoiceNote>> = translating("load your voice notes") {
        client.postgrest.from(VOICE_NOTES_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<VoiceNoteRowDto>()
            .map { it.toRemoteVoiceNote() }
    }

    override suspend fun upsert(serverId: String?, fields: VoiceNoteFields): Result<RemoteVoiceNote> =
        translating(if (serverId == null) "save that recording" else "update that recording") {
            val dto = VoiceNoteUpsertDto.from(fields)
            if (serverId == null) {
                client.postgrest.from(VOICE_NOTES_TABLE)
                    .insert(dto) { select() }
                    .decodeSingle<VoiceNoteRowDto>()
                    .toRemoteVoiceNote()
            } else {
                client.postgrest.from(VOICE_NOTES_TABLE)
                    .update(dto) {
                        select()
                        filter { eq("id", serverId) }
                    }
                    .decodeSingle<VoiceNoteRowDto>()
                    .toRemoteVoiceNote()
            }
        }

    override suspend fun softDelete(serverId: String): Result<Boolean> = translating("remove that recording") {
        client.postgrest.from(VOICE_NOTES_TABLE)
            .update(VoiceNoteDeleteDto(deletedAt = OffsetDateTime.now().toString())) {
                select()
                filter {
                    eq("id", serverId)
                    filter("deleted_at", FilterOperator.IS, "null")
                }
            }
            .decodeList<VoiceNoteRowDto>()
            .isNotEmpty()
    }
}
