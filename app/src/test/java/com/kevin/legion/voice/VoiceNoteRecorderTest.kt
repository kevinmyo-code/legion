package com.kevin.legion.voice

import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteDao
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.service.MicArbiter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * [VoiceNoteRecorder] - ticket 01's own verification list, pinned as plain-JVM-reachable unit
 * tests via [AudioCapture] injection (see that interface's own doc comment for why a real
 * [android.media.MediaRecorder] never appears here). Robolectric only for a real
 * [android.content.Context.getCacheDir] - matches [com.kevin.legion.data.local.CarDatabaseSchemaVersionTest]'s
 * own precedent for the same reason.
 *
 * **The actual 10-minute-screen-off recording ticket 01 also asks for is explicitly NOT covered
 * here** - see this session's own report for why that is "on the phone (owed)" rather than
 * something a JVM test can stand in for.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceNoteRecorderTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Same narrow in-memory fake as `VoiceNoteStoreTest`'s - exercises [VoiceNoteRecorder]
     * against [VoiceNoteDao]'s real interface rather than a hand-rolled shape that could drift. */
    private class FakeVoiceNoteDao : VoiceNoteDao {
        val rows = mutableMapOf<Long, VoiceNote>()
        private var nextId = 1L

        override suspend fun insert(note: VoiceNote): Long {
            val id = nextId++
            rows[id] = note.copy(id = id)
            return id
        }

        override suspend fun update(note: VoiceNote) {
            rows[note.id] = note
        }

        override suspend fun getById(id: Long): VoiceNote? = rows[id]
        override suspend fun getAll(): List<VoiceNote> = rows.values.sortedByDescending { it.startedAt }
        override suspend fun getUnended(): List<VoiceNote> = rows.values.filter { it.endedAt == null }
        override suspend fun getAllAudioPaths(): List<String> = rows.values.mapNotNull { it.audioPath }
        override suspend fun deleteById(id: Long) {
            rows.remove(id)
        }
    }

    /** Stands in for [MediaRecorderAudioCapture]: writes a few real bytes to the output file on
     * [start] (so "file exists and is non-empty" is a genuine assertion, not a given), and records
     * every call so a test can assert teardown actually happened. */
    private class FakeAudioCapture(private val outputPath: String) : AudioCapture {
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        var failOnStart: Exception? = null
        var failOnStop: Exception? = null

        /** Invoked at the end of [start], after the file is written - lets a test reach INTO the
         * `startInFlight` window ([VoiceNoteRecorder.start]'s own doc comment) and act as if a
         * preemption landed while `MediaRecorder.prepare()/start()` was still blocking, exactly
         * where it can in production. */
        var onStart: (() -> Unit)? = null

        override fun start() {
            failOnStart?.let { throw it }
            startCalls++
            File(outputPath).writeBytes(byteArrayOf(1, 2, 3, 4))
            onStart?.invoke()
        }

        override fun stop() {
            stopCalls++
            failOnStop?.let { throw it }
        }

        override fun release() {
            releaseCalls++
        }
    }

    private lateinit var dao: FakeVoiceNoteDao
    private val createdCaptures = mutableListOf<FakeAudioCapture>()

    private fun newRecorder(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) = VoiceNoteRecorder(
        context = context,
        dao = dao,
        audioCaptureFactory = { path -> FakeAudioCapture(path).also { createdCaptures += it } },
        now = { fixedNow },
        scope = scope,
    )

    private var fixedNow = 1_000L

    @Before
    fun setUp() {
        dao = FakeVoiceNoteDao()
        createdCaptures.clear()
        MicArbiter.Claimant.entries.forEach { MicArbiter.release(it) }
    }

    @After
    fun tearDown() {
        MicArbiter.Claimant.entries.forEach { MicArbiter.release(it) }
        // Leave no files behind for the next test in the same Robolectric app sandbox.
        File(context.cacheDir, "voicenotes").deleteRecursively()
    }

    @Test
    fun `start then stop - the file exists and is non-empty`() = runTest {
        val recorder = newRecorder()

        val started = recorder.start(VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        val file = File(started.audioPath)
        assertTrue("the audio file must exist once recording has started", file.exists())
        assertTrue("the audio file must be non-empty", file.length() > 0)

        fixedNow = 2_000L
        val stopped = recorder.stop() as VoiceNoteStopResult.Stopped
        assertEquals(started.noteId, stopped.noteId)

        val note = dao.getById(started.noteId)!!
        assertEquals(1_000L, note.startedAt)
        assertEquals(2_000L, note.endedAt)
        assertFalse("a deliberate stop must not read as interrupted", note.interrupted)
        // Room's generated SQL has no column-level DEFAULT for provenance/interrupted (confirmed
        // against a real kapt run - see MIGRATION_55_56's own doc comment); this is the KOTLIN
        // default doing the work, exercised through the real write path rather than raw SQL.
        assertEquals(com.kevin.legion.data.local.VoiceNoteProvenance.LLM_DERIVED, note.provenance)
        assertEquals(1, createdCaptures.single().stopCalls)
        assertEquals(1, createdCaptures.single().releaseCalls)
    }

    @Test
    fun `a stop whose capture stop() throws marks the row interrupted, pinned against the clean case above`() = runTest {
        // MediaRecorder.stop() throws precisely when no valid data was recorded - the .m4a on
        // disk cannot be trusted to be whole. Before this fix, interrupted was hardcoded false on
        // every deliberate stop regardless of whether AudioCapture.stop() actually succeeded.
        val recorder = newRecorder()
        val started = recorder.start(VoiceNoteKind.SOLO) as VoiceNoteStartResult.Started
        createdCaptures.single().failOnStop = RuntimeException("no valid data")

        fixedNow = 2_000L
        val stopped = recorder.stop() as VoiceNoteStopResult.Stopped
        assertEquals(started.noteId, stopped.noteId)

        val note = dao.getById(started.noteId)!!
        assertEquals(2_000L, note.endedAt)
        assertTrue("a stop whose underlying capture.stop() threw must read as interrupted - " +
            "the audio cannot be trusted to be whole", note.interrupted)
        assertEquals(1, createdCaptures.single().releaseCalls)
    }

    @Test
    fun `stopping with nothing recording reports NothingRecording, never a stop that did not happen`() = runTest {
        val recorder = newRecorder()

        assertEquals(VoiceNoteStopResult.NothingRecording, recorder.stop())
    }

    @Test
    fun `starting while the microphone is held by a live turn is refused`() = runTest {
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN)
        val recorder = newRecorder()

        val result = recorder.start(VoiceNoteKind.SOLO)

        assertTrue(result is VoiceNoteStartResult.Refused)
        assertTrue("nothing must have been inserted for a refused start", dao.rows.isEmpty())
    }

    @Test
    fun `a call arriving preempts the recording and marks it interrupted with a real endedAt`() = runTest {
        // Dispatchers.Unconfined so onMicPreempted's scope.launch runs synchronously here, since
        // its body has no real suspension point against the fake dao - deterministic without a
        // sleep or an explicit join.
        val recorder = newRecorder(scope = CoroutineScope(Dispatchers.Unconfined))
        val started = recorder.start(VoiceNoteKind.MEETING) as VoiceNoteStartResult.Started

        fixedNow = 5_000L
        assertTrue(MicArbiter.request(MicArbiter.Claimant.RING_LISTENING))

        val note = dao.getById(started.noteId)!!
        assertEquals("a mic-preemption stop has a REAL endedAt, unlike a crash", 5_000L, note.endedAt)
        assertTrue("a preempted recording must read as interrupted", note.interrupted)
        assertEquals(1, createdCaptures.single().stopCalls)
        assertEquals("the preempting claimant must now hold the mic, not merely have taken it",
            MicArbiter.Claimant.RING_LISTENING, MicArbiter.current())
    }

    @Test
    fun `a preemption landing during start refuses the start, keeps the audio, and does not steal the mic back`() = runTest {
        // The startInFlight window (VoiceNoteRecorder.start's own doc comment): dao.insert and a
        // blocking AudioCapture.start() sit between MicArbiter granting VOICE_NOTE and
        // activeCapture being published. Before this fix, onMicPreempted found both active fields
        // still null, did nothing, and start() went on to publish a "recording" that no longer
        // held the mic. Triggering the preemption from INSIDE capture.start() puts it squarely in
        // that window, the same place a real ring-listening callback would land it.
        lateinit var capture: FakeAudioCapture
        val recorder = VoiceNoteRecorder(
            context = context,
            dao = dao,
            audioCaptureFactory = { path ->
                FakeAudioCapture(path).also {
                    createdCaptures += it
                    capture = it
                    it.onStart = {
                        fixedNow = 5_000L
                        assertTrue("RING_LISTENING must be able to preempt VOICE_NOTE mid-start",
                            MicArbiter.request(MicArbiter.Claimant.RING_LISTENING))
                    }
                }
            },
            now = { fixedNow },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val result = recorder.start(VoiceNoteKind.SOLO)

        assertTrue("a preemption mid-start must refuse, never Started", result is VoiceNoteStartResult.Refused)
        val noteId = dao.rows.keys.single()
        val note = dao.getById(noteId)!!
        assertTrue("the unwound row must read as interrupted", note.interrupted)
        assertEquals(5_000L, note.endedAt)
        assertTrue("ticket 01: a call arriving keeps the audio", File(note.audioPath!!).exists())
        assertEquals(1, capture.stopCalls)
        assertEquals(1, capture.releaseCalls)
        assertEquals("the preempting claimant must keep the mic - the unwind must not steal it " +
            "back from whoever legitimately took it", MicArbiter.Claimant.RING_LISTENING, MicArbiter.current())
    }

    @Test
    fun `a failed capture start rolls back the row and the mic claim`() = runTest {
        val recorder = VoiceNoteRecorder(
            context = context,
            dao = dao,
            audioCaptureFactory = { path ->
                FakeAudioCapture(path).apply { failOnStart = RuntimeException("no audio hardware") }
            },
            now = { fixedNow },
        )

        val result = recorder.start(VoiceNoteKind.SOLO)

        assertTrue(result is VoiceNoteStartResult.Refused)
        assertTrue("a rolled-back start must leave no row behind", dao.rows.isEmpty())
        assertNull("a rolled-back start must release its mic claim", MicArbiter.current())
    }

    @Test
    fun `reconcileAfterProcessDeath marks an unended row interrupted and leaves endedAt null`() = runTest {
        val id = dao.insert(
            VoiceNote(startedAt = 1_000, kind = VoiceNoteKind.SOLO, audioPath = "/tmp/whatever.m4a"),
        )
        val recorder = newRecorder()

        recorder.reconcileAfterProcessDeath()

        val note = dao.getById(id)!!
        assertTrue("a row with no observed stop must read as interrupted on the next start", note.interrupted)
        assertNull("a crash-recovered row's endedAt stays null - nothing ever observed a stop time",
            note.endedAt)
    }

    @Test
    fun `reconcileAfterProcessDeath deletes an orphan file with no matching row`() = runTest {
        val recorder = newRecorder()
        val dir = File(context.cacheDir, "voicenotes").apply { mkdirs() }
        val orphan = File(dir, "orphan.m4a").apply { writeBytes(byteArrayOf(9)) }
        assertTrue(orphan.exists())

        recorder.reconcileAfterProcessDeath()

        assertFalse("an .m4a with no row claiming it must be deleted on the next start", orphan.exists())
    }

    @Test
    fun `reconcileAfterProcessDeath never deletes a file a row still claims`() = runTest {
        val recorder = newRecorder()
        val dir = File(context.cacheDir, "voicenotes").apply { mkdirs() }
        val claimed = File(dir, "claimed.m4a").apply { writeBytes(byteArrayOf(9)) }
        dao.insert(
            VoiceNote(startedAt = 1_000, endedAt = 2_000, kind = VoiceNoteKind.SOLO, audioPath = claimed.absolutePath),
        )

        recorder.reconcileAfterProcessDeath()

        assertTrue("a file a live row still points at must never be swept as an orphan", claimed.exists())
    }
}
