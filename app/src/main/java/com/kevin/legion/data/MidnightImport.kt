package com.kevin.legion.data

import android.content.Context
import android.content.res.AssetManager
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.sync.SyncCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * One-time migration of Kevin's Midnight AI fleet history into LEGION's own
 * Room database.
 *
 * WHY THIS EXISTS: see `tools/export_midnight_ai.py`'s doc comment for the
 * full design rationale - Drive's `appDataFolder` is scoped per app, so
 * nothing outside LEGION can write into LEGION's own folder (that needs
 * OAuth AS LEGION, package + SHA-1-cert bound). The data has to enter
 * through the app itself, and [com.kevin.legion.sync.SyncEngine] then pushes
 * these rows to Drive on the very next ordinary sync pass, exactly like any
 * other locally-created row - this object never talks to Drive itself.
 * That script writes `sync/SyncCodec`'s own wire format (gzipped NDJSON, one
 * file per table) into `assets/midnight_import/`, specifically so the
 * reader here is a decoder that already exists and is tested rather than a
 * bespoke one.
 *
 * WHERE THIS LIVES: `data/`, not `sync/` - this is a one-shot local seeding
 * step, not a recurring cross-device merge pass. It reuses [SyncCodec]'s
 * decoder and mirrors [com.kevin.legion.sync.SyncEngine]'s raw-SQLite
 * INSERT-OR-IGNORE shape deliberately, but does not touch `SyncEngine`'s
 * registry, its Drive round-trip, or its LWW/UNION merge planner.
 *
 * THE BUNDLE IS GITIGNORED (`tools/export_midnight_ai.py`'s doc comment -
 * `places` carries real lat/long, `daily_drive_logs` carry written
 * narratives). A stranger's clone has no `assets/midnight_import/`
 * directory at all, so [run] is a silent no-op there - see its early
 * return. That is the property that keeps clone-and-run intact.
 *
 * GUARDED TO RUN AT MOST ONCE PER SUCCESSFUL PASS: a SharedPreferences flag
 * is checked before any asset I/O, and is only set once every table in the
 * manifest has imported without error (see [run]). A run that hits a
 * transient per-table failure leaves the flag unset so the NEXT app launch
 * retries - safe to do because every insert below is identity-keyed and
 * idempotent (re-running never duplicates a row that already landed).
 *
 * IDENTITY, NOT ROOM'S RAW PRIMARY KEY. Four of the thirteen tables
 * ([TableSpec.naturalPk] = true: `vehicles`/obdMac, `vehicle_specs`/
 * vehicleId, `places`/label, `maintenance_items`/vehicleId+serviceName)
 * have a real, portable primary key, so inserting with that key present
 * lets SQLite's own uniqueness constraint do the dedup - `INSERT OR IGNORE`
 * is authoritative there. The other nine use Room `autoGenerate = true`
 * `id`: a per-database local counter, never portable, and inserting a
 * Midnight AI row's literal `id` value here could collide with an unrelated
 * LEGION-native row that happens to share the same integer, silently
 * dropping the incoming row. So for those, `id` is omitted from the insert
 * entirely (SQLite assigns a fresh local one) and [TableSpec.identity] is
 * instead the SAME natural/composite/`syncId` key
 * [com.kevin.legion.sync.SyncEngine]'s own `REGISTRY` already uses to
 * identify these rows across devices (traced from that file 2026-08-03) -
 * de-duplication happens by looking up existing rows by that identity
 * before inserting, application-level rather than SQL-level, because
 * SQLite has no unique index on these columns (only SyncEngine's merge
 * planner enforces that, and it isn't involved here). `companion_memories`
 * is not in SyncEngine's `REGISTRY` at all yet ("cross-device sync of this
 * table is still fog" per that entity's own doc comment) but carries the
 * same `syncId` column, so it gets the identical treatment here for
 * consistency.
 *
 * ORDER: `vehicles` imports first (see [SPECS]). No entity in
 * `data/local/` declares an `@ForeignKey` (grepped 2026-08-03 - the only
 * two hits repo-wide are a doc-comment mention in `CarDatabase.kt` and an
 * unrelated ledger table), so nothing enforces parent-before-child at the
 * SQLite level. Vehicles-first is still correct: every other table's
 * `vehicleId`/`obdMac` column is meaningless without a matching vehicle
 * row, and every reader of these tables in this codebase
 * ([com.kevin.legion.vehicle.VehicleController], the Fleet screen,
 * `LiveToolbox`) assumes the vehicle already exists - an orphaned child row
 * would be silently unreachable rather than rejected, which is worth
 * avoiding even without a DB-level constraint forcing it.
 *
 * VERIFYING THE FLEET SCREEN PICKS THESE UP: traced 2026-08-03 -
 * `FleetScreen` resolves its one displayed car via
 * `VehicleController.currentVehicle` -> [com.kevin.legion.vehicle.ActiveVehicle.current],
 * which reads the driver's explicit picker choice, else the connected
 * dongle's MAC, else the `"default"` placeholder id - then looks that id up
 * in `vehicles` by `obdMac`. Importing real rows into the same `vehicles`
 * table under their real `obdMac` values plugs straight into that existing
 * resolution path with no code change: once a dongle for one of the
 * imported cars connects (or a future car picker selects one explicitly),
 * `ActiveVehicle.current` already resolves to it. [ActiveVehicle] itself
 * needs no change.
 */
object MidnightImport {
    private const val TAG = "MidnightImport"
    private const val ASSET_DIR = "midnight_import"
    private const val MANIFEST_FILE = "manifest.json"
    private const val PREFS = "midnight_import"
    private const val KEY_COMPLETED = "completed"

    /**
     * @param table SQLite table name, matches the `.json.gz` file name.
     * @param identity columns that identify a row uniquely for this import's
     *   purposes - either the table's real primary key ([naturalPk] = true)
     *   or the same natural/`syncId` key SyncEngine's registry uses for it.
     * @param naturalPk true if [identity] IS the table's declared Room
     *   primary key (no separate autoincrement `id` column exists); false if
     *   the table has an `id` column that must be omitted from the insert
     *   and dedup must happen by [identity] instead.
     */
    data class TableSpec(val table: String, val identity: List<String>, val naturalPk: Boolean)

    /**
     * All thirteen tables the exporter can produce, in import order (see the
     * class doc's ORDER section for why `vehicles` is first; the rest have no
     * ordering requirement between them). [importOrder] filters this down to
     * whatever the bundle's own manifest actually lists.
     */
    val SPECS: List<TableSpec> = listOf(
        // Real, portable primary keys - SQLite's own uniqueness constraint
        // does the dedup, so `INSERT OR IGNORE` is authoritative here.
        TableSpec("vehicles", listOf("obdMac"), naturalPk = true),
        TableSpec("vehicle_specs", listOf("vehicleId"), naturalPk = true),
        TableSpec("maintenance_items", listOf("vehicleId", "serviceName"), naturalPk = true),
        TableSpec("places", listOf("label"), naturalPk = true),
        // Autoincrement `id` locally (never inserted - see class doc);
        // identity mirrors SyncEngine's REGISTRY spec for the same table.
        TableSpec("obd_samples", listOf("vehicleId", "pid", "timestamp"), naturalPk = false),
        TableSpec("code_events", listOf("syncId"), naturalPk = false),
        TableSpec("companion_memories", listOf("syncId"), naturalPk = false),
        TableSpec("daily_drive_logs", listOf("vehicleId", "year", "month", "day"), naturalPk = false),
        TableSpec("car_tasks", listOf("syncId"), naturalPk = false),
        TableSpec("memories", listOf("syncId"), naturalPk = false),
        TableSpec("monthly_recaps", listOf("vehicleId", "year", "month"), naturalPk = false),
        TableSpec("build_entries", listOf("syncId"), naturalPk = false),
        TableSpec("service_records", listOf("syncId"), naturalPk = false),
    )

    /**
     * Parses `manifest.json`'s `"tables": {name: rowCount}` map. Pure (no
     * Android calls) - unit tested directly rather than through an
     * instrumented asset read.
     */
    fun parseManifest(bytes: ByteArray): Map<String, Int> {
        val tables = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("tables")
        return tables.keys().asSequence().associateWith { tables.getInt(it) }
    }

    /**
     * [SPECS] filtered down to the tables [manifestTables] actually lists,
     * preserving [SPECS]' declared (vehicles-first) order. A manifest entry
     * for a table this object doesn't know about (a future export script
     * change landing before this importer is updated for it) is silently
     * skipped rather than crashing the whole import. Pure - unit tested
     * directly.
     */
    fun importOrder(manifestTables: Set<String>): List<TableSpec> =
        SPECS.filter { it.table in manifestTables }

    /**
     * Runs the import if it hasn't already completed successfully and the
     * bundle is present. Safe to call unconditionally on every app start -
     * see the class doc's "GUARDED" section. Never throws: every failure
     * mode (missing bundle, corrupt manifest, one table's insert blowing up)
     * degrades to a logged skip rather than propagating into the caller's
     * [kotlinx.coroutines.CoroutineScope].
     */
    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return@withContext

        val assets = context.assets
        val manifestBytes = try {
            assets.open("$ASSET_DIR/$MANIFEST_FILE").use { it.readBytes() }
        } catch (e: IOException) {
            // No bundle in this build - the expected steady state for every
            // clone that isn't Kevin's own machine (the exporter's output is
            // gitignored). Nothing to do, nothing to warn about.
            return@withContext
        }

        val manifestTables = try {
            parseManifest(manifestBytes).keys
        } catch (e: Exception) {
            Log.w(TAG, "manifest.json present but unreadable, aborting import", e)
            MidnightEvents.recordError("midnight_import_manifest", e)
            return@withContext
        }

        val db = CarDatabase.getDatabase(context).openHelper.writableDatabase
        var totalInserted = 0
        var totalSkipped = 0
        var failedTables = 0
        for (spec in importOrder(manifestTables)) {
            val result = runCatching { importTable(db, assets, spec) }
                .getOrElse {
                    failedTables++
                    Log.w(TAG, "midnight_import ${spec.table} failed", it)
                    MidnightEvents.recordError("midnight_import_${spec.table}", it)
                    null
                } ?: continue
            totalInserted += result.inserted
            totalSkipped += result.skipped
            Log.i(TAG, "midnight_import ${spec.table}: inserted=${result.inserted} skipped=${result.skipped}")
        }
        Log.i(
            TAG,
            "midnight_import complete: $totalInserted rows inserted, $totalSkipped already present, " +
                "${manifestTables.size} tables in manifest, $failedTables failed",
        )
        // Only latch the "never again" flag on a fully clean pass. A table
        // that failed leaves the flag unset so the next app launch retries -
        // safe because every insert below is identity-keyed and idempotent,
        // so a retry never duplicates a row that already landed.
        if (failedTables == 0) prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        else Log.w(TAG, "midnight_import: $failedTables table(s) failed, will retry next launch")
    }

    private data class TableResult(val inserted: Int, val skipped: Int)

    /** One table's worth of rows, in one transaction (CLAUDE.md L11/spec: not
     * one transaction per row - 11.5k of those on a phone is a full minute). */
    private fun importTable(db: SupportSQLiteDatabase, assets: AssetManager, spec: TableSpec): TableResult {
        val bytes = try {
            assets.open("$ASSET_DIR/${spec.table}.json.gz").use { it.readBytes() }
        } catch (e: IOException) {
            // manifest.json claimed this table but its shard is missing - the
            // export script always writes both together, so this would mean a
            // corrupted or hand-edited bundle. Skip it, don't crash the rest.
            Log.w(TAG, "manifest listed ${spec.table} but its .json.gz is missing, skipping")
            return TableResult(0, 0)
        }
        val rows = SyncCodec.rowsFromGzipNdjson(bytes)
        if (rows.isEmpty()) return TableResult(0, 0)

        // A handful of Midnight AI rows may predate the `syncId` column and
        // carry a blank value (the column was backfilled lazily there too,
        // same shape as SyncEngine.backfillSyncIds does for LEGION's own
        // rows). Treating a blank as a shared identity would silently
        // collapse every such row into a single import. Mint each a fresh
        // UUID before dedup instead of trusting the source data's blank.
        if (spec.identity == listOf("syncId")) {
            for (row in rows) {
                if (row.optString("syncId", "").isBlank()) row.put("syncId", UUID.randomUUID().toString())
            }
        }

        val existingKeys = loadExistingKeys(db, spec)
        var inserted = 0
        var skipped = 0
        db.beginTransaction()
        try {
            for (row in rows) {
                val key = spec.identity.map { SyncCodec.sqlArg(row, it) }
                // Set.add returns false when the key is already present -
                // covers both "already in the local DB before this pass" and
                // "duplicated within the bundle itself", either way skipped
                // exactly once and never inserted twice.
                if (!existingKeys.add(key)) {
                    skipped++
                    continue
                }
                insertRow(db, spec.table, row, omitId = !spec.naturalPk)
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return TableResult(inserted, skipped)
    }

    /** Every existing row's identity tuple, so incoming rows can be checked
     * against the local DB before insert (see class doc's IDENTITY section). */
    private fun loadExistingKeys(db: SupportSQLiteDatabase, spec: TableSpec): MutableSet<List<Any?>> {
        val cols = spec.identity.joinToString(",") { "`$it`" }
        val keys = mutableSetOf<List<Any?>>()
        db.query("SELECT $cols FROM `${spec.table}`").use { c ->
            while (c.moveToNext()) {
                keys.add(spec.identity.indices.map { i -> cursorValue(c, i) })
            }
        }
        return keys
    }

    /** Mirrors [SyncCodec.sqlArg]'s type mapping so a cursor-derived key and
     * a JSON-derived key compare equal for the same logical value. */
    private fun cursorValue(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        else -> c.getString(i)
    }

    /**
     * Same shape as [com.kevin.legion.sync.SyncEngine]'s private `insertRow`
     * - not called directly (that one is private to `sync/`, and this
     * importer deliberately sits outside the sync registry/merge machinery,
     * see class doc) but the `INSERT OR IGNORE` + omitted-`id` pattern is
     * intentionally identical.
     */
    private fun insertRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, omitId: Boolean) {
        val cols = row.keys().asSequence().filter { !(omitId && it == "id") }.toList()
        if (cols.isEmpty()) return
        val sql = "INSERT OR IGNORE INTO `$table` (${cols.joinToString(",") { "`$it`" }}) " +
            "VALUES (${cols.joinToString(",") { "?" }})"
        db.execSQL(sql, cols.map { SyncCodec.sqlArg(row, it) }.toTypedArray())
    }
}
