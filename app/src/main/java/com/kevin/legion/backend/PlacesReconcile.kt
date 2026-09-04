package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.places.PlacesAspectSeeder
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Places
 * (`.scratch/backend-erp/issues/05-migration-path.md`, "Each aspect follows the identical
 * shape... 1. Upload... 2. Diff until clean"). Uploads every active ENGINE record to the server,
 * keyed on `label` (the server's natural key, so a re-run is a free no-op per row), pulls the
 * server's active set back into the Room replica, and reports what does not yet agree.
 *
 * **Never touches, trashes, or deletes an engine record.** The engine stays the source of truth
 * until [PlacesReconcile.Report.isClean] - deleting the engine's copy is a LATER phase (phase 6),
 * and this function's only side effects are a network upload and a Room replica write.
 */
object PlacesReconcile {

    /**
     * @param engineCount how many active engine records this aspect had.
     * @param uploaded how many of those were successfully upserted server-side (equal to
     *   [engineCount] on any run that did not fail partway - a failed upload short-circuits into
     *   [Result.failure] rather than returning a report with a lower count, so a caller can never
     *   mistake a partial upload for a complete one).
     * @param serverCountAfter the server's active row count after the upload.
     * @param replicaCountAfter the Room replica's active row count after being refreshed from the
     *   server's active set.
     * @param onlyOnEngine labels the engine has that the server does not (should be empty after a
     *   clean upload; a non-empty list here means the upload silently failed to cover something).
     * @param onlyOnServer labels the server has that the engine does not (a place created directly
     *   against the server, or a stale engine read).
     */
    data class Report(
        val engineCount: Int,
        val uploaded: Int,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        /** True only when every engine label reconciled against exactly one server label and
         * nothing is left over on either side - the "diff until clean" gate phase 4 step 2 owes
         * before step 3 (flipping the write path) may start. */
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    private data class EnginePlace(val label: String, val latitude: Double, val longitude: Double)

    suspend fun run(context: Context, backend: PlacesBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val sch = PlacesAspectSeeder.ensureSeeded(context)

        val enginePlaces = db.engineRecordDao().activeByRecordType(sch.recordTypeId).mapNotNull { record ->
            val payload = JSONObject(record.payload)
            val label = PayloadCodec.readString(payload, sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL))
                ?: return@mapNotNull null
            val latitude = PayloadCodec.readDouble(payload, sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE))
                ?: return@mapNotNull null
            val longitude = PayloadCodec.readDouble(payload, sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE))
                ?: return@mapNotNull null
            EnginePlace(label, latitude, longitude)
        }

        var uploaded = 0
        for (place in enginePlaces) {
            val result = backend.upsert(place.label, place.latitude, place.longitude)
            result.exceptionOrNull()?.let { return Result.failure(it) }
            uploaded++
        }

        val serverRows = backend.fetchActive().getOrElse { return Result.failure(it) }
        for (row in serverRows) {
            db.placeDao().upsert(
                TaggedPlace(
                    label = row.label,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    timestamp = row.updatedAtMs,
                    deleted = row.deleted,
                )
            )
        }

        val engineLabels = enginePlaces.map { it.label }.toSet()
        val serverLabels = serverRows.map { it.label }.toSet()

        return Result.success(
            Report(
                engineCount = enginePlaces.size,
                uploaded = uploaded,
                serverCountAfter = serverRows.size,
                replicaCountAfter = db.placeDao().getAll().size,
                onlyOnEngine = (engineLabels - serverLabels).sorted(),
                onlyOnServer = (serverLabels - engineLabels).sorted(),
            )
        )
    }

    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoRunAt = 0L

    /** Same floor and reasoning as [LedgerReconcile]'s own `AUTO_RUN_MIN_INTERVAL_MS` - a
     * full-table scan (a handful of tagged places on the real phone, not a queue drain), so a
     * floor exists to stop every foreground return from re-scanning the whole engine `Place` set. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** The throttle half of [autoRunGate], pulled out as a pure predicate so a test can exercise
     *  the floor's own arithmetic directly against [setLastAutoRunAtForTest] - same reasoning as
     *  [ObdSampleReconcile.isThrottled]'s own doc comment (a configured [SupabaseClient] throws
     *  under Robolectric). */
    internal fun isThrottled(now: Long): Boolean = now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS

    /** Test-only escape hatch for [isThrottled]'s own test - never called from [autoRunGate] or
     *  [maybeAutoRun]. */
    internal fun setLastAutoRunAtForTest(atMs: Long) {
        lastAutoRunAt = atMs
    }

    /**
     * The synchronous half of [maybeAutoRun] - the throttle floor ([isThrottled]) and the "is
     * Supabase even configured" check, extracted so a test can assert on this gate's own return
     * value directly. [lastAutoRunAt] is reserved here, before [maybeAutoRun] ever launches
     * anything async - the "reserved before any awaiting" property that makes a cold start
     * immediately followed by a foreground resume one run, not two.
     */
    internal fun autoRunGate(context: Context, now: Long = System.currentTimeMillis()): SupabaseClient? {
        if (isThrottled(now)) return null
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return null
        lastAutoRunAt = now
        return client
    }

    /**
     * The async half of [maybeAutoRun] - resolves who is signed in via
     * [SupabaseAuth.resolveSignedInUserId] (never the raw `currentUserId() == null` guard) and,
     * if anyone is, runs [run] and reports the result via [MidnightEvents]. Extracted so a test
     * can drive "signed out" and "signed in" directly against a fake [SupabaseAuth] gatewayProvider.
     * Fails to a logged [MidnightEvents] event, never a crash or a dialog, matching every sibling
     * `maybeAutoRun`'s posture.
     */
    internal suspend fun runIfSignedIn(context: Context, backend: PlacesBackend, auth: SupabaseAuth) {
        try {
            if (auth.resolveSignedInUserId() == null) return
            val report = run(context, backend).getOrThrow()
            MidnightEvents.placesAutoReconcileSucceeded(report.uploaded, report.serverCountAfter)
        } catch (e: Exception) {
            MidnightEvents.placesAutoReconcileFailed(e)
        }
    }

    /**
     * `MainActivity.onResume`'s hook - this reconcile's only production caller before this ticket
     * was a Settings row nobody had wired up to run automatically (the `BackendMigrationScreen`
     * migration row). No-ops silently, with a logged breadcrumb rather than a dialog or a crash,
     * when Supabase is not configured or nobody is signed in - see [autoRunGate]/[runIfSignedIn]
     * for the two halves this delegates to. Fire-and-forget on [autoRunScope]; never suspends the
     * caller.
     */
    fun maybeAutoRun(context: Context) {
        val client = autoRunGate(context) ?: return
        val app = context.applicationContext
        autoRunScope.launch {
            runIfSignedIn(app, SupabasePlacesBackend(client), SupabaseAuth(app))
        }
    }
}
