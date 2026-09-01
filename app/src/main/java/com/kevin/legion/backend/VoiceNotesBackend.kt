package com.kevin.legion.backend

/**
 * A `public.voice_notes` row as Postgres reports it
 * (`supabase/migrations/20260901000100_voice_notes.sql`) - the shape [SupabaseVoiceNotesBackend]
 * hands back after every write. Field-for-field mirror of that migration's own header comment,
 * same role [RemoteEvent] plays for `public.events`.
 *
 * **There is no `audioPath` here, deliberately.** The audio file never leaves the phone (ticket 02,
 * `.scratch/voice-notes/issues/02-the-store.md`: "the server holds text; the file stays on the
 * phone") - [com.kevin.legion.data.local.VoiceNote.audioPath] has no server-side counterpart at
 * all, not a nullable one.
 */
data class RemoteVoiceNote(
    val serverId: String,
    val startedAtMs: Long,
    /** Null while the recording is still running, or forever if the process died before a stop
     * was ever observed - see [com.kevin.legion.data.local.VoiceNote.endedAt]'s own doc comment.
     * [interrupted] is the column to check for completeness, never this one's nullness. */
    val endedAtMs: Long?,
    val title: String?,
    val summary: String?,
    val transcript: String?,
    /** [com.kevin.legion.data.local.VoiceNoteKind.SOLO] or
     * [com.kevin.legion.data.local.VoiceNoteKind.MEETING]. */
    val kind: String,
    /** Always [com.kevin.legion.data.local.VoiceNoteProvenance.LLM_DERIVED] - see that object's
     * own doc comment for why this is a domain-local value, not [RecordProvenance]. */
    val provenance: String,
    val interrupted: Boolean,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/**
 * Every writable column on `public.voice_notes` except the ones an upsert never sets directly
 * (`id`/`updated_at`/`deleted_at`/`provenance` - `provenance` is CHECK-constrained to its single
 * legal value server-side, so a caller stating it would only ever restate the default). Shared by
 * [VoiceNotesBackend.upsert]'s create and update branches, same role [EventFields] plays for
 * `public.events`.
 */
data class VoiceNoteFields(
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val title: String? = null,
    val summary: String? = null,
    val transcript: String? = null,
    val kind: String,
    val interrupted: Boolean = false,
)

/**
 * The voice-notes Phase 2 seam, mirroring [EventsBackend]'s shape (narrow, no
 * [io.github.jan.supabase.SupabaseClient] in any signature, every function returns [Result]).
 * ticket 04's `voice/VoiceNoteController.kt` is the intended production caller - unlike
 * [EventsBackend], nothing calls this yet, since ticket 02's own scope is "where a voice note
 * lives", not the controller that reads and writes through it.
 *
 * **The dual path is the CALLER's responsibility, not this interface's** - same posture as
 * [EventsBackend] itself. `SupabaseClientProvider.get(context)` returning null means unconfigured,
 * and the intended caller writes straight to [com.kevin.legion.data.local.VoiceNoteDao] in that
 * case rather than ever constructing a [SupabaseVoiceNotesBackend] at all - matching
 * `notes/NotesController.kt`'s own "else null meaning not configured" branch
 * ([NotesController]'s own doc comment describes the identical shape for events).
 */
interface VoiceNotesBackend {
    /** Every active (not soft-deleted) voice note, server-side. Used to refresh the Room replica -
     * never called from a hot-path read; callers read the replica instead. */
    suspend fun fetchActive(): Result<List<RemoteVoiceNote>>

    /**
     * Creates a new voice note ([serverId] null) or updates an existing one ([serverId] the row's
     * own uuid). Same create-vs-update fork as [EventsBackend.upsert] for the same reason: there is
     * no natural key to upsert on besides the server-generated id itself.
     */
    suspend fun upsert(serverId: String?, fields: VoiceNoteFields): Result<RemoteVoiceNote>

    /** Soft-deletes the voice note at [serverId]. `Result.success(false)` means no active row
     * matched (already deleted, or a stale id) - never reported as a delete having happened,
     * matching [EventsBackend.softDelete]'s own posture. **Text only** - the caller is still
     * responsible for [com.kevin.legion.data.local.VoiceNoteStore]'s own local file+row cascade;
     * this call has nothing to delete on the audio side because nothing of the audio was ever
     * uploaded here. */
    suspend fun softDelete(serverId: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseVoiceNotesBackend] for every failure branch -
 * owned by this package, never a raw supabase-kt/Ktor exception, same posture as
 * [EventsBackendException]. */
class VoiceNotesBackendException(message: String) : Exception(message)
