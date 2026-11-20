package com.kevin.legion.service

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * A record's times reach the model in local time (phone audit 2026-11-19). At a UTC/local date
 * boundary: made at 6:30 PM on the 19th at UTC-6, which is 00:30 on the 20th in UTC, it reads as the
 * 19th at 18:30. Made-up aspect and record.
 */
@RunWith(RobolectricTestRunner::class)
class RecordTimesLocalTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val savedZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-06:00"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `query_records states created and updated times in local time across the UTC date`() = runBlocking {
        val made = ZonedDateTime.of(2026, 11, 19, 18, 30, 0, 0, ZoneOffset.ofHours(-6)).toInstant().toEpochMilli()
        val aspectId = db.aspectDao().insert(Aspect(name = "Plants", createdAt = made, updatedAt = made))
        val typeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = "Plant", createdAt = made, updatedAt = made))
        db.engineRecordDao().insert(
            EngineRecord(recordTypeId = typeId, createdAt = made, updatedAt = made, provenance = RecordProvenance.USER),
        )
        val out = EngineToolbox.dispatch(
            context, "query_records", JSONObject().put("aspectName", "Plants").put("recordTypeName", "Plant"),
        )!!
        val row = out.getJSONArray("records").getJSONObject(0)
        assertEquals("2026-11-19 18:30 local", row.getString("createdAt"))
        assertEquals("2026-11-19 18:30 local", row.getString("updatedAt"))
    }
}
