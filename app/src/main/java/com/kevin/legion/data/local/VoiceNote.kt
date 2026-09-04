package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * The two shapes Kevin records (`.scratch/voice-notes/map.md`: "either group meetings, or solo
 * thoughts"). Plain `String` constants, not a Kotlin enum, matching [PantryReceipt.provenance]'s
 * own convention for a column that also has to round-trip through the Supabase wire shape as free
 * text - see [VoiceNoteProvenance]'s doc comment for the fuller reasoning, which applies here too.
 */
object VoiceNoteKind {
    const val SOLO = "SOLO"
    const val MEETING = "MEETING"
}

/**
 * The one legal value of [VoiceNote.provenance]. **Deliberately NOT a member of
 * [RecordProvenance]/`public.provenance`** even though every other provenance-carrying table in
 * this codebase uses that shared vocabulary (CLAUDE.md §4 rule 4). That enum's four values -
 * `DETERMINISTIC`, `LLM_RECONCILED`, `UNRECONCILED`, `USER` - all describe a row's relationship to
 * §4's NUMERIC reconciliation gate: parsed by code, extracted-and-verified, extracted-but-
 * unverified, or hand-typed. A voice note's transcript and summary have no numeric anchor to
 * reconcile against at all - ADR 0041 replaces the gate wholesale here with the anchor CHAIN
 * (summary anchored by transcript, transcript anchored by audio), which is a different guarantee
 * than any of those four words claims. Tagging a voice note `LLM_RECONCILED` would assert it
 * passed a gate that never ran against it; tagging it `UNRECONCILED` would borrow rule 7's
 * transient-and-superseded machinery for a row this ADR never intends to delete on that trigger;
 * tagging it `USER` would misdescribe the model-authored transcript and summary as hand-typed.
 * `LLM_DERIVED` says only what is true: a model produced this text, anchored by the audio kept
 * beside it. **Never `DETERMINISTIC` - nothing here is** (ticket 02's own words).
 */
object VoiceNoteProvenance {
    const val LLM_DERIVED = "LLM_DERIVED"
}

/**
 * One voice note - Kevin turns on a recording before a meeting or a solo thought, talks, turns it
 * off, and this is what is left behind (`.scratch/voice-notes/map.md`). Notes came off the aspect
 * engine on 2026-08-27 (ticket 02's own framing note), so this is an ordinary typed Room table -
 * not a `RecordType` seeder, and not a new `kind` on `events`: a voice note has no due date, no
 * recurrence, no alarm request code, and nothing about the `events` id contract applies to it.
 *
 * [id] follows [SleepLog]/[MealLog]'s own minting convention (`autoGenerate` `Long`), not a
 * client-minted UUID - this is a device-local log row exactly like those two, not a
 * multi-device-authored one like [Event]/[TaggedPlace] where a UUID avoids an id collision between
 * two phones creating rows offline at once. [serverId] carries the round trip to
 * `public.voice_notes` once uploaded, same role as [SleepLog.serverId]... except SleepLog has none
 * yet; this is the same role [Event.serverId] plays for its own table.
 *
 * **The anchor chain (ADR 0041), encoded directly in nullability:** [audioPath] is never null
 * while [transcript] is null (there is nothing yet to anchor a transcript to anything else, so the
 * audio is the only evidence and must still be on disk), and [summary] is never non-null while
 * [transcript] is null (a summary anchored by nothing is exactly the failure the ADR forbids).
 * Nothing in this data class enforces that at compile time - it is a contract for every writer to
 * honour, verified by [VoiceNoteRecorderTest]/the delete-cascade test in `VoiceNoteStoreTest`,
 * not a Room `CHECK` constraint (SQLite's per-column nullability can't express a relationship
 * between two columns, and hand-rolling one in a raw `CREATE TABLE` string is exactly the kind of
 * thing that silently stops matching this class the next time either column changes).
 *
 * [endedAt] is set the moment recording genuinely stopped, whether that was an ordinary user-
 * initiated stop or `VoiceNoteRecorder` gracefully winding down after losing the microphone to
 * `RING_LISTENING` (ticket 01) - in both cases the recorder KNOWS when it stopped and records it.
 * It stays null only when nothing ever ran that code at all: the process died mid-recording and
 * there was no chance to observe a stop time. **[interrupted] is therefore the one column callers
 * must read to find out whether a note is complete - never inferred from [endedAt]'s nullness**,
 * because a mic-preemption interruption has a perfectly real [endedAt] and would otherwise read as
 * complete. This is deliberately more conservative than ticket 02's own one-line summary of the
 * column ("null means interrupted") - that line described the crash-recovery case, which is real
 * and true, but the ticket's own next column, [interrupted], exists as "an explicit boolean, not
 * inferred at read time" specifically because a second interruption path (mic preemption) has a
 * real timestamp and would otherwise slip through a rule keyed on [endedAt] alone.
 *
 * **[transcriptionFailureReason]/[transcriptionAttemptStartedAt] (added v65, `.scratch` follow-up
 * to CLAUDE.md §7's outcome-verb rule: "a spinner that never resolves is the claim §7 forbids") -
 * the failure and in-flight halves of the SAME gap: a stopped note with a null [transcript] used to
 * read as "still transcribing" whether the call was genuinely in flight, had already failed, or
 * belonged to a process that died mid-call, and none of those three is the same sentence.**
 * [transcriptionFailureReason] carries the WORDS of the last failure, never just a boolean - the
 * same "store the reason, not the verdict" posture [ListItem.exactDowngraded] set for a refusal in
 * this schema and CLAUDE.md §4 rule 8 states for a gate's own inputs: a bare "failed" flag with no
 * reason is an assertion nobody can act on. Null means no failure is currently on record for this
 * row - cleared the instant a new attempt starts, so a stale reason from an earlier attempt can
 * never linger next to a since-succeeded transcript. [transcriptionAttemptStartedAt] is set the
 * moment [com.kevin.legion.voice.VoiceNoteController.transcribeAndPersist] begins and cleared the
 * moment it finishes (success OR failure) - a non-null value read back at the NEXT app start (by
 * [com.kevin.legion.voice.VoiceNoteController.reconcileAfterProcessDeath]) can only mean the process
 * died mid-call, the same "a timestamp nobody ever cleared means the write that would have cleared
 * it never ran" shape [interrupted]'s own crash-recovery half uses for [endedAt].
 */
@Entity(tableName = "voice_notes")
data class VoiceNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val title: String? = null,
    val summary: String? = null,
    val transcript: String? = null,
    val audioPath: String? = null,
    /** [VoiceNoteKind.SOLO] or [VoiceNoteKind.MEETING]. */
    val kind: String,
    /** Always [VoiceNoteProvenance.LLM_DERIVED] - see that object's own doc comment for why this
     * is not [RecordProvenance]. */
    val provenance: String = VoiceNoteProvenance.LLM_DERIVED,
    /** True the moment the recording is known to have ended other than by a clean, deliberate
     * stop - a mic preemption (ticket 01's `RING_LISTENING` rule) or a process death discovered on
     * the next app start. Never inferred from [endedAt] - see this class's own doc comment. */
    val interrupted: Boolean = false,
    /** Words, not a flag - see this class's own doc comment. Null means no failure is on record. */
    val transcriptionFailureReason: String? = null,
    /** Non-null exactly while a transcription attempt is genuinely in flight - see this class's
     * own doc comment for how a stale value here is read as an abandoned attempt. */
    val transcriptionAttemptStartedAt: Long? = null,
)

/** Data access for [VoiceNote]. Deliberately narrow - file cleanup and the ADR 0041 delete
 * cascade live in [VoiceNoteStore], not here, because a `@Dao` interface has no [android.content.Context]
 * and should not reach for one; this stays SQL-only, same posture as every other DAO in this
 * package. */
@Dao
interface VoiceNoteDao {
    /** Returns the new row's id, which [VoiceNoteRecorder] needs immediately to name the on-disk
     * file and to look the row back up on preemption/stop. */
    @Insert
    suspend fun insert(note: VoiceNote): Long

    @Update
    suspend fun update(note: VoiceNote)

    /**
     * Narrow, column-only updates for the two transcription-attempt-tracking columns - deliberately
     * NOT a read-then-`.copy()`-then-[update], because that shape has a real lost-update race:
     * [com.kevin.legion.voice.VoiceNoteController.transcribeAndPersist] reads the row, then (after a
     * network round trip) writes a `.copy()` of that STALE snapshot - if a rename or any other write
     * to the SAME row lands in between, the stale snapshot's copy silently reverts it. Found
     * 2026-09-04 via a genuinely flaky `rename` test: the row's title came back null because a
     * concurrent transcription attempt's own attempt-started stamp, built from a snapshot fetched
     * before the rename, overwrote it back to null moments later. A plain `UPDATE ... SET col = :x
     * WHERE id = :id` touches ONLY the named columns no matter what else changed on the row between
     * read and write, because there is no read.
     */
    @Query("UPDATE voice_notes SET transcriptionAttemptStartedAt = :startedAt, transcriptionFailureReason = NULL WHERE id = :id")
    suspend fun markTranscriptionAttemptStarted(id: Long, startedAt: Long)

    /** The failure/no-audio/abandoned-attempt exit path's own narrow update - see
     * [markTranscriptionAttemptStarted]'s own doc comment for why this is a column-only `UPDATE`
     * rather than a read-`.copy()`-write. */
    @Query("UPDATE voice_notes SET transcriptionFailureReason = :reason, transcriptionAttemptStartedAt = NULL WHERE id = :id")
    suspend fun markTranscriptionFailed(id: Long, reason: String)

    /** The success exit path's own narrow update - same [markTranscriptionAttemptStarted] reasoning,
     * extended to the columns a successful transcription actually writes. `COALESCE(title, :title)`
     * is the SQL form of [VoiceNote.title]'s own "a user-supplied titleHint is never overwritten by
     * the model's guess" rule: only an untitled row (still NULL at write time) picks up [title]. */
    @Query(
        "UPDATE voice_notes SET title = COALESCE(title, :title), summary = :summary, " +
            "transcript = :transcript, transcriptionFailureReason = NULL, " +
            "transcriptionAttemptStartedAt = NULL WHERE id = :id",
    )
    suspend fun applyTranscriptionSuccess(id: Long, title: String, summary: String, transcript: String)

    @Query("SELECT * FROM voice_notes WHERE id = :id")
    suspend fun getById(id: Long): VoiceNote?

    @Query("SELECT * FROM voice_notes ORDER BY startedAt DESC")
    suspend fun getAll(): List<VoiceNote>

    /** Every note whose [VoiceNote.startedAt] falls in `[startInclusive, endExclusive)` - the day
     * view's RECORDED section join (`ui/CalendarScreen.kt`). A new `@Query`, not a schema change:
     * no column added or dropped, so no migration or version bump follows this (CLAUDE.md §5). A
     * recording is bucketed by when it genuinely started, the same real-epoch-millis convention
     * every other timestamp column on this table already uses - unlike [Event.startsAt]'s all-day
     * UTC-midnight convention, there is no separate zone handling to get wrong here, only the
     * ordinary caller-supplied window. */
    @Query("SELECT * FROM voice_notes WHERE startedAt >= :startInclusive AND startedAt < :endExclusive ORDER BY startedAt ASC")
    suspend fun getInRange(startInclusive: Long, endExclusive: Long): List<VoiceNote>

    /** Every row whose recording never observed a stop - the crash-recovery scan
     * [VoiceNoteRecorder.reconcileAfterProcessDeath] runs once at startup. See [VoiceNote]'s own
     * doc comment for why this is [VoiceNote.endedAt], not [VoiceNote.interrupted]: a row already
     * marked interrupted by a clean mic-preemption stop DOES have an [VoiceNote.endedAt] and must
     * not be re-scanned as if it crashed. */
    @Query("SELECT * FROM voice_notes WHERE endedAt IS NULL")
    suspend fun getUnended(): List<VoiceNote>

    /** Every row whose transcription attempt was left in flight - the crash-recovery scan
     * [com.kevin.legion.voice.VoiceNoteController.reconcileAfterProcessDeath] runs alongside
     * [getUnended] at startup. See [VoiceNote.transcriptionAttemptStartedAt]'s own doc comment: a
     * non-null value read back on the NEXT app start can only mean the process died mid-call,
     * because a normal completion (success or failure) always clears it before that call returns. */
    @Query("SELECT * FROM voice_notes WHERE transcriptionAttemptStartedAt IS NOT NULL")
    suspend fun getStalledTranscriptions(): List<VoiceNote>

    /** Every audio path this table currently claims - the orphan-file scan's "what is spoken for"
     * side. Nullable column, but a row past recording always has one until its audio is dropped
     * (see [VoiceNote.audioPath]'s own doc comment), so the `IS NOT NULL` filter only ever excludes
     * rows that intentionally have no audio left, never a row still relying on it. */
    @Query("SELECT audioPath FROM voice_notes WHERE audioPath IS NOT NULL")
    suspend fun getAllAudioPaths(): List<String>

    @Query("DELETE FROM voice_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
