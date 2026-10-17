package com.kevin.midnightai.fleetupload

import android.content.Context
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.CodeEvent
import com.kevin.midnightai.data.local.OdbSample
import com.kevin.midnightai.data.local.Vehicle
import com.kevin.midnightai.diag.UploadStatusSink

/** The production [FleetUploadSource]: the head unit's Room tables. Archived cars are included,
 * since their history still belongs on the engine. */
class RoomFleetUploadSource(context: Context) : FleetUploadSource {
    private val db = CarDatabase.getDatabase(context.applicationContext)

    override suspend fun vehicles(): List<Vehicle> = db.vehicleDao().getAllIncludingArchived()

    override suspend fun samplesAfter(vehicleId: String, afterId: Long, limit: Int): List<OdbSample> =
        db.odbSampleDao().pendingAfter(vehicleId, afterId, limit)

    override suspend fun sampleCountAfter(vehicleId: String, afterId: Long): Int =
        db.odbSampleDao().countAfter(vehicleId, afterId)

    override suspend fun codeEventsAfter(vehicleId: String, afterId: Long, limit: Int): List<CodeEvent> =
        db.codeEventDao().pendingAfter(vehicleId, afterId, limit)

    override suspend fun codeEventCountAfter(vehicleId: String, afterId: Long): Int =
        db.codeEventDao().countAfter(vehicleId, afterId)
}

/** SharedPreferences-backed [FleetCursorStore]. Not synced to Drive and not part of the Room
 * database: it records what THIS unit has told the engine. */
class PrefsFleetCursorStore(context: Context) : FleetCursorStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun sampleCursor(vehicleId: String): Long = prefs.getLong("$SAMPLE_PREFIX$vehicleId", 0L)

    override fun setSampleCursor(vehicleId: String, id: Long) {
        prefs.edit().putLong("$SAMPLE_PREFIX$vehicleId", id).apply()
    }

    override fun codeEventCursor(vehicleId: String): Long = prefs.getLong("$CODE_PREFIX$vehicleId", 0L)

    override fun setCodeEventCursor(vehicleId: String, id: Long) {
        prefs.edit().putLong("$CODE_PREFIX$vehicleId", id).apply()
    }

    override fun engineVehicleId(vehicleId: String): String? =
        prefs.getString("$ENGINE_PREFIX$vehicleId", null)?.takeIf { it.isNotBlank() }

    override fun setEngineVehicleId(vehicleId: String, engineId: String) {
        prefs.edit().putString("$ENGINE_PREFIX$vehicleId", engineId).apply()
    }

    override fun clearEngineVehicleId(vehicleId: String) {
        prefs.edit().remove("$ENGINE_PREFIX$vehicleId").apply()
    }

    private companion object {
        const val PREFS = "fleet_upload_cursors"
        const val SAMPLE_PREFIX = "sample_cursor_"
        const val CODE_PREFIX = "code_cursor_"
        const val ENGINE_PREFIX = "engine_vehicle_"
    }
}

/** Last-attempt record for the Settings row. Its own prefs file, separate from the drive-log's. */
class FleetUploadStatus(context: Context) : UploadStatusSink {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun record(atMs: Long, ok: Boolean, sentence: String) {
        prefs.edit().putLong(KEY_AT, atMs).putBoolean(KEY_OK, ok).putString(KEY_SENTENCE, sentence).apply()
    }

    fun lastAtMs(): Long? = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }

    fun lastOk(): Boolean = prefs.getBoolean(KEY_OK, false)

    fun lastSentence(): String? = prefs.getString(KEY_SENTENCE, null)

    /** Epoch ms of the last attempt of any kind, for the periodic trigger's time threshold. */
    fun lastAttemptMs(): Long = prefs.getLong(KEY_AT, 0L)

    private companion object {
        const val PREFS = "fleet_upload_status"
        const val KEY_AT = "last_at_ms"
        const val KEY_OK = "last_ok"
        const val KEY_SENTENCE = "last_sentence"
    }
}

/** Production wiring and the one trigger rule shared by the service loop. */
object FleetUpload {
    /** Mid-drive flush: this many waiting samples, or [FLUSH_INTERVAL_MS] since the last attempt. */
    const val FLUSH_SAMPLE_THRESHOLD = 500
    const val FLUSH_INTERVAL_MS = 15 * 60_000L

    fun create(context: Context): FleetUploader {
        val app = context.applicationContext
        return FleetUploader(
            source = RoomFleetUploadSource(app),
            cursors = PrefsFleetCursorStore(app),
            http = EngineHttp(EngineConfig(app)),
            status = FleetUploadStatus(app),
        )
    }

    /** Called from the 3-minute drive-log tick. Uploads only when enough is waiting or enough
     * time has passed, and never raises: a failed flush is retried at the next trigger. */
    suspend fun flushIfDue(context: Context) {
        val app = context.applicationContext
        // Signed out: say nothing here. The 3-minute tick would otherwise write the same
        // breadcrumb all day; the drive-end, startup and manual triggers do say it.
        if (!EngineHttp(EngineConfig(app)).isUsable()) return
        val uploader = create(app)
        val pending = uploader.pending()
        if (pending.samples == 0 && pending.codeEvents == 0) return
        val sinceLast = System.currentTimeMillis() - FleetUploadStatus(app).lastAttemptMs()
        if (shouldFlush(pending, sinceLast)) uploader.upload(FleetUploadReason.PERIODIC)
    }

    fun shouldFlush(pending: FleetPending, msSinceLastAttempt: Long): Boolean =
        pending.codeEvents > 0 || pending.samples >= FLUSH_SAMPLE_THRESHOLD ||
            (pending.samples > 0 && msSinceLastAttempt >= FLUSH_INTERVAL_MS)
}
