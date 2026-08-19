package com.kevin.legion.ledger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [com.kevin.legion.data.local.IngestedFileDao.retryAllQuarantined],
 * the bulk counterpart to the per-file RETRY.
 *
 * Same reasoning as [IngestPipelineReplaceFlowTest] for living in
 * `androidTest` rather than `test`: `room-testing` is `androidTestImplementation`
 * only, so a plain JVM unit test cannot run the query at all. Uses an isolated
 * [Room.inMemoryDatabaseBuilder] instance rather than [CarDatabase.getDatabase],
 * for the same cross-contamination reason spelled out there - doubly so here,
 * since this DAO method rewrites every quarantined row in whatever database it
 * is handed.
 *
 * The behaviour worth pinning is the state guard. A bulk UPDATE that forgot its
 * `WHERE state = 'QUARANTINED'` would drag INGESTED records backwards to NEW,
 * and a rescan would then re-ingest statements whose rows are already committed.
 */
@RunWith(AndroidJUnit4::class)
class RetryAllQuarantinedTest {

    private lateinit var db: CarDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CarDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(id: String, state: IngestState) = IngestedFile(
        driveFileId = id, treeUri = "tree-1", displayName = "$id.pdf",
        sizeBytes = 100, lastModified = 1_000, contentSha256 = "hash-$id",
        state = state, firstSeenAt = 1, lastAttemptAt = 1,
    )

    @Test
    fun retryAllMovesEveryQuarantinedRecordAndLeavesEveryOtherStateAlone() = kotlinx.coroutines.runBlocking {
        val dao = db.ingestedFileDao()
        dao.upsert(file("q1", IngestState.QUARANTINED))
        dao.upsert(file("q2", IngestState.QUARANTINED))
        dao.upsert(file("q3", IngestState.QUARANTINED))
        dao.upsert(file("done", IngestState.INGESTED))
        dao.upsert(file("fresh", IngestState.NEW))

        val moved = dao.retryAllQuarantined()

        assertEquals(3, moved)
        assertEquals(IngestState.NEW, dao.getByDriveFileId("q1")?.state)
        assertEquals(IngestState.NEW, dao.getByDriveFileId("q2")?.state)
        assertEquals(IngestState.NEW, dao.getByDriveFileId("q3")?.state)
        // The guard: an already-committed file must not be dragged backwards
        // into a state that would make the next scan re-ingest it.
        assertEquals(IngestState.INGESTED, dao.getByDriveFileId("done")?.state)
        assertEquals(IngestState.NEW, dao.getByDriveFileId("fresh")?.state)
        assertEquals(0, dao.listQuarantined().size)
    }

    @Test
    fun retryAllOnNothingQuarantinedIsAHarmlessNoOp() = kotlinx.coroutines.runBlocking {
        val dao = db.ingestedFileDao()
        dao.upsert(file("done", IngestState.INGESTED))

        assertEquals(0, dao.retryAllQuarantined())
        assertEquals(IngestState.INGESTED, dao.getByDriveFileId("done")?.state)
    }
}
