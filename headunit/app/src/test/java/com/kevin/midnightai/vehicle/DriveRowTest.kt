package com.kevin.midnightai.vehicle

import androidx.sqlite.db.SupportSQLiteDatabase
import com.kevin.midnightai.data.local.MIGRATION_12_13
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The `drives` row rule in `TelemetryRecorder.driveRow`, and the 12 -> 13 migration SQL. */
class DriveRowTest {
    private val start = 1_700_000_000_000L
    private val end = start + 600_000L

    @Test fun `a finished drive keeps the start the recorder saw`() {
        val row = TelemetryRecorder.driveRow("AA:BB", start, end, miles = 4.2, gallons = 0.2)!!
        assertEquals(start, row.startedAt)
        assertEquals(end, row.endedAt)
        assertEquals(4.2, row.miles, 0.0)
        assertEquals("ENGINE_OFF", row.endReason)
        assertEquals(0.2, row.gallons!!, 0.0)
        assertNotEquals("", row.syncId)
    }

    @Test fun `gallons are null, never zero or derived, without the drive's own MPG figure`() {
        assertNull(TelemetryRecorder.driveRow("v", start, end, miles = 4.2, gallons = 0.0)!!.gallons)
        assertNull(TelemetryRecorder.driveRow("v", start, end, miles = 4.2, gallons = 0.04)!!.gallons)
    }

    @Test fun `no recorded start, a backwards clock, or under a mile writes no row`() {
        assertNull(TelemetryRecorder.driveRow("v", 0L, end, 4.2, 0.2))
        assertNull(TelemetryRecorder.driveRow("v", end, start, 4.2, 0.2))
        assertNull(TelemetryRecorder.driveRow("v", start, end, 0.5, 0.2))
    }

    @Test fun `each row gets its own sync id`() {
        val a = TelemetryRecorder.driveRow("v", start, end, 4.2, 0.2)!!
        val b = TelemetryRecorder.driveRow("v", start, end, 4.2, 0.2)!!
        assertNotNull(a.syncId)
        assertNotEquals(a.syncId, b.syncId)
    }

    @Test fun `the 12 to 13 migration runs exactly the createSql Room exported for drives`() {
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") executed += args[0] as String
            null
        } as SupportSQLiteDatabase
        MIGRATION_12_13.migrate(db)

        val json = JSONObject(File("schemas/com.kevin.midnightai.data.local.CarDatabase/13.json").readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")
        val sql = (0 until entities.length()).map { entities.getJSONObject(it) }
            .single { it.getString("tableName") == "drives" }
            .getString("createSql").replace("\${TABLE_NAME}", "drives")
        assertEquals(listOf(sql), executed)
    }
}
