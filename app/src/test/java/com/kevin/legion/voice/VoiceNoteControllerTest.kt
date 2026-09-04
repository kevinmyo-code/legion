package com.kevin.legion.voice

import com.kevin.legion.backend.RemoteVoiceNote
import com.kevin.legion.backend.VoiceNoteFields
import com.kevin.legion.backend.VoiceNotesBackend
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.service.MicArbiter
import com.kevin.legion.testutil.RoomTestReset
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [VoiceNoteController] - ticket 04's own load-bearing claim (ADR 0035: "both paths call this one
 * controller"), pinned by exercising the controller directly rather than either caller. A real
 * [CarDatabase] (Robolectric, [RoomTestReset]) backs every test, same posture as
 * [com.kevin.legion.ui.body.BodyWriteSameFunctionTest] - [VoiceNoteController] has no injectable
 * DAO seam of its own (it always resolves [CarDatabase.getDatabase] directly, matching
 * [com.kevin.legion.location.PlaceController]'s own shape), so a real database is the only way to
 * exercise it end to end. [VoiceNoteController.recorderOverride] stands in a real [VoiceNoteRecorder]
 * wired to a fake [AudioCaptureFactory] (no [android.media.MediaRecorder]) against that SAME real
 * DAO, so start/stop/reconcile all land in the one database every other assertion reads back from.
 *
 * **No Gemini key is configured in this environment**, so [VoiceNoteAgent.transcribeAndSummarize]
 * always takes its own [com.kevin.legion.ai.GeminiKeyProvider.hasKey] pre-flight-check path (added
 * alongside the FAILED-state ticket - previously this fell through to a caught real-network-call
 * exception instead, which was slow and, worse, is what originally made this whole suite flaky:
 * see [VoiceNoteController.controllerScopeOverride]'s own doc comment) - deterministic and FAST
 * either way, same posture [BodyWriteSameFunctionTest]'s own class doc states for
 * [com.kevin.legion.meals.MealController.logMeal]'s LLM call. That makes
 * [VoiceNoteController.transcribeAndPersist] deterministically hit [VoiceNoteAgent.Result.Failed]
 * here - this suite is not exercising a Success transcription (that is
 * [com.kevin.legion.ai.VoiceNoteAgentParseResponseTest]'s job), only what the CONTROLLER does with
 * whichever [VoiceNoteAgent.Result] comes back.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceNoteControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Same fake shape [VoiceNoteRecorderTest] uses - writes real bytes so "file exists and is
     * non-empty" stays a genuine assertion for anything that reads [VoiceNote.audioPath]. */
    private class FakeAudioCapture(private val outputPath: String) : AudioCapture {
        override fun start() {
            File(outputPath).writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        override fun stop() {}
        override fun release() {}
    }

    private class FakeVoiceNotesBackend : VoiceNotesBackend {
        val upserts = mutableListOf<Pair<String?, VoiceNoteFields>>()
        val softDeletes = mutableListOf<String>()
        var upsertResult: Result<RemoteVoiceNote>? = null
        var softDeleteResult: Result<Boolean> = Result.success(true)

        override suspend fun fetchActive(): Result<List<RemoteVoiceNote>> = Result.success(emptyList())

        override suspend fun upsert(serverId: String?, fields: VoiceNoteFields): Result<RemoteVoiceNote> {
            upserts += serverId to fields
            return upsertResult ?: Result.success(
                RemoteVoiceNote(
                    serverId = serverId ?: UUID.randomUUID().toString(),
                    startedAtMs = fields.startedAtMs,
                    endedAtMs = fields.endedAtMs,
                    title = fields.title,
                    summary = fields.summary,
                    transcript = fields.transcript,
                    kind = fields.kind,
                    provenance = "LLM_DERIVED",
                    interrupted = fields.interrupted,
                    updatedAtMs = fields.startedAtMs,
                    deleted = false,
                )
            )
        }

        override suspend fun softDelete(serverId: String): Result<Boolean> {
            softDeletes += serverId
            return softDeleteResult
        }
    }

    private fun dao() = CarDatabase.getDatabase(context).voiceNoteDao()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        MicArbiter.Claimant.entries.forEach { MicArbiter.release(it) }
        VoiceNoteController.recorderOverride = VoiceNoteRecorder(
            context = context,
            dao = dao(),
            audioCaptureFactory = { path -> FakeAudioCapture(path) },
        )
        VoiceNoteController.backendOverride = null
        // controllerScopeOverride itself is set per-test by runControllerTest below, not here -
        // see that function's own doc comment for why it has to be the TEST's own TestScope
        // rather than one built in @Before, which runs outside any coroutine.
    }

    @After
    fun tearDown() {
        VoiceNoteController.recorderOverride = null
        VoiceNoteController.backendOverride = null
        VoiceNoteController.controllerScopeOverride = null
        MicArbiter.Claimant.entries.forEach { MicArbiter.release(it) }
        File(context.cacheDir, "voicenotes").deleteRecursively()
        RoomTestReset.drainArchDiskIoPool()
    }

    /**
     * `runTest`, but with [VoiceNoteController.controllerScopeOverride] pointed at THIS call's own
     * [TestScope] before the test body runs. Every test in this class that touches [stop] or
     * [VoiceNoteController.retryTranscription] must go through this, not a bare `runTest {}` -
     * see [VoiceNoteController.controllerScopeOverride]'s own doc comment for the failure this
     * closes: a fire-and-forget `controllerScope.launch` on a REAL [kotlinx.coroutines.Dispatchers.IO]
     * thread pool has no deterministic finish time a test can wait on, and doubling the writes each
     * background transcription performs (this ticket's own attempt-started/failure-reason columns)
     * was enough to turn that latent gap into cross-test corruption. Routing the launch through
     * `this` [TestScope] instead makes it a genuine CHILD of the test's own coroutine hierarchy -
     * `runTest` does not return until every child of its own scope has completed, on WHATEVER
     * dispatcher that child actually suspends on, which is what a bespoke `UnconfinedTestDispatcher`
     * scope (tried first, insufficient - it still leaked once in testing) cannot promise: an
     * eagerly-started coroutine can still suspend on a REAL dispatcher hop (Room's own connection
     * pool) that resumes on its own schedule, off any clock `runTest` controls.
     */
    private fun runControllerTest(block: suspend TestScope.() -> Unit) = runTest {
        VoiceNoteController.controllerScopeOverride = this
        block()
    }

    // -------------------------------------------------------------------- start / stop

    @Test
    fun `start delegates to the shared recorder and refuses a second concurrent start`() = runControllerTest {
        val first = VoiceNoteController.start(context, VoiceNoteKind.SOLO)
        assertTrue(first is VoiceNoteStartResult.Started)

        val second = VoiceNoteController.start(context, VoiceNoteKind.SOLO)
        assertTrue("a second start while one is already recording must be refused, not silently replace it",
            second is VoiceNoteStartResult.Refused)
    }

    @Test
    fun `stop returns Saved and NothingRecording is reported when nothing was recording`() = runControllerTest {
        assertEquals(VoiceNoteController.StopOutcome.NothingRecording, VoiceNoteController.stop(context))

        val started = VoiceNoteController.start(context, VoiceNoteKind.MEETING) as VoiceNoteStartResult.Started
        val stopped = VoiceNoteController.stop(context)
        assertTrue(stopped is VoiceNoteController.StopOutcome.Saved)
        assertEquals(started.noteId, (stopped as VoiceNoteController.StopOutcome.Saved).noteId)
    }

    // -------------------------------------------------------------------- transcribeAndPersist

    @Test
    fun `a failed transcription leaves summary and transcript null, audio untouched`() = runControllerTest {
        val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        VoiceNoteController.stop(context)

        // Direct call, not through stop()'s own fire-and-forget launch - see
        // transcribeAndPersist's own doc comment for why this seam exists.
        VoiceNoteController.transcribeAndPersist(context, started.noteId, started.audioPath)

        val note = dao().getById(started.noteId)!!
        assertNull("no Gemini key in this environment - transcription must fail, never fabricate a summary",
            note.summary)
        assertNull(note.transcript)
        assertTrue("a failed transcription must never delete the audio a retry would need",
            File(started.audioPath).exists())
    }

    @Test
    fun `a failed transcription stores its reason in words and clears the in-flight marker`() = runControllerTest {
        val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        VoiceNoteController.stop(context)

        VoiceNoteController.transcribeAndPersist(context, started.noteId, started.audioPath)

        val note = dao().getById(started.noteId)!!
        assertTrue("a failure must be readable as words, never only a boolean",
            note.transcriptionFailureReason?.isNotBlank() == true)
        assertNull("the row must not read as still in flight once the attempt has finished",
            note.transcriptionAttemptStartedAt)
        assertEquals("a null-transcript row with a failure reason must read as FAILED, never TRANSCRIBING",
            com.kevin.legion.ui.voicenotes.VoiceNoteRowState.FAILED,
            com.kevin.legion.ui.voicenotes.voiceNoteRowState(note))
    }

    @Test
    fun `transcribeAndPersist with no audio path is a safe no-op that still records why`() = runControllerTest {
        val id = dao().insert(VoiceNote(startedAt = 1_000L, kind = VoiceNoteKind.SOLO, audioPath = null))

        VoiceNoteController.transcribeAndPersist(context, id, null)

        val note = dao().getById(id)!!
        assertNull(note.summary)
        assertNull(note.transcript)
        assertTrue("no audio to transcribe is still a failure that must be said in words, never a silent no-op",
            note.transcriptionFailureReason?.isNotBlank() == true)
    }

    /** A real, non-empty audio file on disk - `retryTranscription`/`NoAudio` both need to tell
     * "audio present" apart from "audio missing" by [VoiceNote.audioPath]'s own nullness, same
     * fake-bytes shape [FakeAudioCapture] uses elsewhere in this suite. Not routed through
     * `start`/`stop` (which would ALSO schedule a `controllerScope`-launched background transcribe
     * of its own, on top of the one this test's own [VoiceNoteController.retryTranscription] call
     * launches) - two independent fire-and-forget launches per test method is exactly the
     * concurrent-Room-teardown race `RoomTestReset`'s own class doc describes, and this test only
     * needs to prove ONE of them: [VoiceNoteController.retryTranscription]'s own immediate return
     * value, never its background outcome. */
    private fun realAudioFile(): String {
        val file = File(context.cacheDir, "voicenotes/retry-test-${System.nanoTime()}.m4a")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return file.absolutePath
    }

    @Test
    fun `retryTranscription reports Retrying for a failed note with audio, NotFound for no row, NoAudio for a row with none`() = runControllerTest {
        val id = dao().insert(
            VoiceNote(
                startedAt = 1_000L, endedAt = 2_000L, kind = VoiceNoteKind.SOLO,
                audioPath = realAudioFile(),
                transcriptionFailureReason = "an earlier attempt failed",
            ),
        )

        // retryTranscription launches its re-attempt fire-and-forget on controllerScope, the SAME
        // shape [stop] itself uses for the original attempt (see that function's own doc comment) -
        // routed onto THIS test's own TestScope by runControllerTest, so runTest's own structured
        // concurrency waits for that launch to finish before the test method returns; no manual
        // await needed here.
        assertEquals(VoiceNoteController.RetryResult.Retrying,
            VoiceNoteController.retryTranscription(context, id))

        assertEquals("an id with no row must never report a retry as started",
            VoiceNoteController.RetryResult.NotFound, VoiceNoteController.retryTranscription(context, 999_999L))

        val noAudioId = dao().insert(VoiceNote(startedAt = 1_000L, kind = VoiceNoteKind.SOLO, audioPath = null,
            transcriptionFailureReason = "No audio was saved for this recording, so it can't be transcribed."))
        assertEquals("a row with no audio left must report NoAudio, never a retry that cannot possibly work",
            VoiceNoteController.RetryResult.NoAudio, VoiceNoteController.retryTranscription(context, noAudioId))
    }

    @Test
    fun `a new transcription attempt clears a stale failure reason from a previous attempt`() = runControllerTest {
        val id = dao().insert(
            VoiceNote(
                startedAt = 1_000L, endedAt = 2_000L, kind = VoiceNoteKind.SOLO,
                audioPath = realAudioFile(),
                // A reason an actual attempt in THIS test could never have produced, so the
                // assertion below cannot pass by coincidence.
                transcriptionFailureReason = "STALE - must not survive a new attempt",
            ),
        )

        // Called directly (the internal seam), not via retryTranscription's fire-and-forget launch
        // - this is the exact call retryTranscription makes, awaited synchronously so the
        // assertion below is deterministic rather than racing a background coroutine.
        VoiceNoteController.transcribeAndPersist(context, id, dao().getById(id)!!.audioPath)

        val note = dao().getById(id)!!
        assertTrue("a new attempt must overwrite a stale reason from a previous one, never leave it lingering",
            note.transcriptionFailureReason != "STALE - must not survive a new attempt")
        assertNull("the row must not read as still in flight once the new attempt has also finished",
            note.transcriptionAttemptStartedAt)
    }

    @Test
    fun `reconcileAfterProcessDeath marks an abandoned in-flight transcription as failed, distinct from one genuinely still running`() = runControllerTest {
        val stalled = dao().insert(
            VoiceNote(
                startedAt = 1_000L, endedAt = 2_000L, kind = VoiceNoteKind.SOLO,
                audioPath = "/tmp/stalled.m4a",
                // Set directly, simulating a process that died between transcribeAndPersist
                // stamping this and ever clearing it - see VoiceNote.transcriptionAttemptStartedAt's
                // own doc comment.
                transcriptionAttemptStartedAt = 1_500L,
            ),
        )
        val genuinelyTranscribing = dao().insert(
            VoiceNote(
                startedAt = 1_000L, endedAt = 2_000L, kind = VoiceNoteKind.SOLO,
                audioPath = "/tmp/still-running.m4a",
                // No attempt marker at all - this row has simply never been picked up yet, the
                // ordinary TRANSCRIBING case, and must NOT be swept.
            ),
        )

        VoiceNoteController.reconcileAfterProcessDeath(context)

        val stalledNote = dao().getById(stalled)!!
        assertTrue("an abandoned attempt must be surfaced as a failure in words, not left silent",
            stalledNote.transcriptionFailureReason?.isNotBlank() == true)
        assertNull(stalledNote.transcriptionAttemptStartedAt)
        assertEquals(com.kevin.legion.ui.voicenotes.VoiceNoteRowState.FAILED,
            com.kevin.legion.ui.voicenotes.voiceNoteRowState(stalledNote))

        val runningNote = dao().getById(genuinelyTranscribing)!!
        assertNull("a row with no attempt marker was never actually abandoned - must be left alone",
            runningNote.transcriptionFailureReason)
        assertEquals("a genuinely-still-transcribing row must keep reading as TRANSCRIBING, never FAILED",
            com.kevin.legion.ui.voicenotes.VoiceNoteRowState.TRANSCRIBING,
            com.kevin.legion.ui.voicenotes.voiceNoteRowState(runningNote))
    }

    // -------------------------------------------------------------------- rename

    @Test
    fun `rename updates the title and reports false for an unknown id`() = runControllerTest {
        val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        VoiceNoteController.stop(context)

        assertTrue(VoiceNoteController.rename(context, started.noteId, "Standup notes"))
        assertEquals("Standup notes", dao().getById(started.noteId)!!.title)

        assertFalse("a rename against an id with no row must report false, never a rename that happened",
            VoiceNoteController.rename(context, 999_999L, "Nothing"))
    }

    // -------------------------------------------------------------------- delete (ADR 0041 cascade)

    @Test
    fun `delete with no backend removes the row and its audio file`() = runControllerTest {
        val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        VoiceNoteController.stop(context)
        val audioFile = File(started.audioPath)
        assertTrue(audioFile.exists())

        val result = VoiceNoteController.delete(context, started.noteId)

        assertEquals(VoiceNoteController.DeleteResult.Deleted, result)
        assertNull("the row must be gone", dao().getById(started.noteId))
        assertFalse("ADR 0041: the audio must go with the row, never left orphaned", audioFile.exists())
    }

    @Test
    fun `delete reports NotFound for an id with no row, never a delete that did not happen`() = runControllerTest {
        assertEquals(VoiceNoteController.DeleteResult.NotFound, VoiceNoteController.delete(context, 999_999L))
    }

    @Test
    fun `a backend delete failure leaves both the server row and the local one untouched`() = runControllerTest {
        val fakeBackend = FakeVoiceNotesBackend()
        VoiceNoteController.backendOverride = fakeBackend

        val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        VoiceNoteController.stop(context)
        // Give the row a serverId directly (bypassing the async transcribe-then-sync path, which
        // is covered separately) - syncToBackend is exercised on its own below.
        val stamped = dao().getById(started.noteId)!!.copy(serverId = "server-123")
        dao().update(stamped)

        fakeBackend.softDeleteResult = Result.failure(RuntimeException("offline"))
        val result = VoiceNoteController.delete(context, started.noteId)

        assertTrue(result is VoiceNoteController.DeleteResult.Failed)
        assertTrue("a failed remote delete must leave the local row exactly as it was",
            dao().getById(started.noteId) != null)
        assertTrue("the local audio file must survive a failed remote delete",
            File(started.audioPath).exists())
    }

    // -------------------------------------------------------------------- syncToBackend

    @Test
    fun `syncToBackend upserts every field and stamps the returned serverId back onto the row`() = runControllerTest {
        val fakeBackend = FakeVoiceNotesBackend()
        VoiceNoteController.backendOverride = fakeBackend

        val note = VoiceNote(
            startedAt = 1_000L, endedAt = 2_000L, title = "Kickoff", summary = "We decided X.",
            transcript = "Full transcript text.", kind = VoiceNoteKind.MEETING,
        )
        val id = dao().insert(note)
        val inserted = note.copy(id = id)

        VoiceNoteController.syncToBackend(context, inserted)

        assertEquals(1, fakeBackend.upserts.size)
        val (serverIdArg, fields) = fakeBackend.upserts.single()
        assertNull("a never-synced row has no serverId yet - this must be a CREATE, not an update", serverIdArg)
        assertEquals("Kickoff", fields.title)
        assertEquals("We decided X.", fields.summary)
        assertEquals("Full transcript text.", fields.transcript)
        assertEquals(VoiceNoteKind.MEETING, fields.kind)

        val stamped = dao().getById(id)!!
        assertTrue("a genuine server ACK must stamp its id back onto the local row", stamped.serverId != null)

        // A second sync now carries the stamped serverId - a create only ever happens once.
        VoiceNoteController.syncToBackend(context, stamped)
        assertEquals(2, fakeBackend.upserts.size)
        assertEquals("the second call must be an UPDATE, not a second create", stamped.serverId,
            fakeBackend.upserts[1].first)
    }

    // -------------------------------------------------------------------- listInRange (calendar
    // day-view RECORDED section - `ui/CalendarScreen.kt`)

    @Test
    fun `listInRange buckets a late-evening note into its own day, never the next`() = runControllerTest {
        // The exact day/dayEndExclusive construction ui/CalendarScreen.kt's own effect uses:
        // local midnight, plus com.kevin.legion.ui.notes.DAY_FILTER_WINDOW_MS (24h) exclusive.
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.of(2026, 9, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndExclusive = day + 24L * 60 * 60 * 1000

        val lateEvening = java.time.LocalDate.of(2026, 9, 4).atTime(23, 59, 0)
            .atZone(zone).toInstant().toEpochMilli()
        // Exactly the NEXT day's local midnight - the first instant this window must exclude.
        val nextDayMidnight = dayEndExclusive

        val lateId = dao().insert(VoiceNote(startedAt = lateEvening, kind = VoiceNoteKind.SOLO, audioPath = null))
        val nextId = dao().insert(VoiceNote(startedAt = nextDayMidnight, kind = VoiceNoteKind.SOLO, audioPath = null))

        val result = VoiceNoteController.listInRange(context, day, dayEndExclusive)
        assertTrue(result is VoiceNoteController.VoiceNotesForDayResult.Loaded)
        val notes = (result as VoiceNoteController.VoiceNotesForDayResult.Loaded).notes

        assertTrue("a note recorded at 23:59 must land on its own day, not roll into the next",
            notes.any { it.id == lateId })
        assertTrue("a note starting exactly at the next day's local midnight must not land on " +
            "the previous day", notes.none { it.id == nextId })
    }

    @Test
    fun `listInRange excludes a note from the day before, at the exact start boundary it must include instead`() = runControllerTest {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.of(2026, 9, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndExclusive = day + 24L * 60 * 60 * 1000
        val previousDayLastMs = day - 1

        val startOfDayId = dao().insert(VoiceNote(startedAt = day, kind = VoiceNoteKind.SOLO, audioPath = null))
        val previousDayId = dao().insert(VoiceNote(startedAt = previousDayLastMs, kind = VoiceNoteKind.SOLO, audioPath = null))

        val notes = (VoiceNoteController.listInRange(context, day, dayEndExclusive)
            as VoiceNoteController.VoiceNotesForDayResult.Loaded).notes

        assertTrue("startedAt exactly at local midnight is the FIRST instant this day must include",
            notes.any { it.id == startOfDayId })
        assertTrue("one millisecond before local midnight belongs to the previous day, never this one",
            notes.none { it.id == previousDayId })
    }

    @Test
    fun `reconcileAfterProcessDeath runs through the shared recorder instance`() = runControllerTest {
        val id = dao().insert(VoiceNote(startedAt = 1_000L, kind = VoiceNoteKind.SOLO, audioPath = "/tmp/x.m4a"))

        VoiceNoteController.reconcileAfterProcessDeath(context)

        assertTrue("an unended row must be swept to interrupted through the controller's own entry point",
            dao().getById(id)!!.interrupted)
    }
}
