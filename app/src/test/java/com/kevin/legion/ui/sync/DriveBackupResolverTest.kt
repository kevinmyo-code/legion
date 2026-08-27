package com.kevin.legion.ui.sync

import com.kevin.legion.sync.DatabaseSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM - [DriveBackupResolver] is Android-free by design (see its own doc comment,
 * same shape as [GoogleGrantResolverTest]). */
class DriveBackupResolverTest {

    private fun gen(ts: Long, schema: Int, rows: Long) =
        DatabaseSnapshot.Generation(timestampMs = ts, schemaVersion = schema, rowCount = rows, dbFileId = "db$ts", metaFileId = "meta$ts")

    private val fixedFormat: (Long) -> String = { ts -> "T$ts" }

    @Test
    fun `generationRows marks only the first entry as newest, given already-sorted input`() {
        val rows = DriveBackupResolver.generationRows(
            generations = listOf(gen(300, 15, 100), gen(200, 15, 90), gen(100, 15, 80)),
            runningSchemaVersion = 15,
            formatTime = fixedFormat,
        )
        assertTrue(rows[0].isNewest)
        assertFalse(rows[1].isNewest)
        assertFalse(rows[2].isNewest)
    }

    @Test
    fun `a generation at or below the running schema version can restore`() {
        val rows = DriveBackupResolver.generationRows(
            generations = listOf(gen(100, 15, 80), gen(50, 12, 60)),
            runningSchemaVersion = 15,
            formatTime = fixedFormat,
        )
        assertTrue(rows[0].canRestore)
        assertNull(rows[0].disabledReason)
        assertTrue(rows[1].canRestore)
    }

    @Test
    fun `a generation from a newer schema than the running app cannot restore, and says why`() {
        val rows = DriveBackupResolver.generationRows(
            generations = listOf(gen(100, 17, 80)),
            runningSchemaVersion = 15,
            formatTime = fixedFormat,
        )
        assertFalse(rows[0].canRestore)
        assertTrue(rows[0].disabledReason!!.contains("newer"))
        assertTrue(rows[0].disabledReason!!.contains("v17"))
        assertTrue(rows[0].disabledReason!!.contains("v15"))
    }

    @Test
    fun `rowCountLabel pluralizes correctly`() {
        val rows = DriveBackupResolver.generationRows(
            generations = listOf(gen(100, 15, 1), gen(90, 15, 0), gen(80, 15, 2)),
            runningSchemaVersion = 15,
            formatTime = fixedFormat,
        )
        assertEquals("1 row", rows[0].rowCountLabel)
        assertEquals("0 rows", rows[1].rowCountLabel)
        assertEquals("2 rows", rows[2].rowCountLabel)
    }

    @Test
    fun `lastBackupSummary reports no backups yet when the list is empty`() {
        assertEquals("No backups yet.", DriveBackupResolver.lastBackupSummary(emptyList(), fixedFormat))
    }

    @Test
    fun `lastBackupSummary picks the newest by timestamp regardless of input order`() {
        val summary = DriveBackupResolver.lastBackupSummary(
            listOf(gen(100, 15, 80), gen(300, 15, 120), gen(200, 15, 90)),
            fixedFormat,
        )
        assertEquals("Last backup: T300 - 120 rows.", summary)
    }

    @Test
    fun `confirmRestoreMessage names the timestamp, row count, and that a restart is required`() {
        val message = DriveBackupResolver.confirmRestoreMessage(gen(500, 15, 250), fixedFormat)
        assertTrue(message.contains("T500"))
        assertTrue(message.contains("250 rows"))
        assertTrue(message.contains("restart"))
        assertTrue(message.contains("everything"))
    }

    @Test
    fun `confirmRestoreMessage says photos are not covered - ticket 09`() {
        val message = DriveBackupResolver.confirmRestoreMessage(gen(500, 15, 250), fixedFormat)
        assertTrue(message.contains("does not include photos"))
    }

    private fun recovery(ts: Long, kind: DatabaseSnapshot.LocalRecoveryKind) =
        DatabaseSnapshot.LocalRecovery(file = File("fake_$ts.db"), timestampMs = ts, kind = kind, label = "test")

    @Test
    fun `localRecoveryRows labels a pre-restore copy and an interrupted-restore leftover differently`() {
        val rows = DriveBackupResolver.localRecoveryRows(
            recoveries = listOf(
                recovery(200, DatabaseSnapshot.LocalRecoveryKind.INTERRUPTED_ORIGINAL),
                recovery(100, DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY),
            ),
            formatTime = fixedFormat,
        )
        assertTrue(rows[0].kindLabel.contains("Interrupted"))
        assertEquals("Safety copy", rows[1].kindLabel)
        assertEquals("T200", rows[0].timeLabel)
    }

    @Test
    fun `confirmLocalRecoveryMessage names the time, the kind, and that it does not touch Drive`() {
        val row = DriveBackupResolver.localRecoveryRows(
            listOf(recovery(400, DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY)),
            fixedFormat,
        ).single()
        val message = DriveBackupResolver.confirmLocalRecoveryMessage(row, fixedFormat)
        assertTrue(message.contains("T400"))
        assertTrue(message.contains("does not touch Drive"))
        assertTrue(message.contains("restart"))
        assertTrue(message.contains("does not include photos"))
    }

    @Test
    fun `confirmOverrideGuardMessage quotes the specific refusal reason back and warns it will overwrite the last good backup`() {
        val reason = "New backup has 0 row(s) vs the last good backup's 48213 - that looks like data loss."
        val message = DriveBackupResolver.confirmOverrideGuardMessage(reason)
        assertTrue(message.contains(reason))
        assertTrue(message.contains("deliberately"))
        assertTrue(message.contains("overwrite"))
    }
}
