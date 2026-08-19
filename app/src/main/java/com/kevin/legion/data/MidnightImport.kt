package com.kevin.legion.data

import android.content.Context
import android.content.res.AssetManager
import android.database.Cursor
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
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
 *
 * AMENDED 2026-08-03: that holds for a car identified by a real dongle MAC.
 * It does NOT hold for one re-keyed off [SENTINEL_VEHICLE_ID], because a
 * synthetic id matches no dongle and is not the `"default"` fallback either.
 * The Outlander is therefore in the database, correct and self-consistent,
 * and unreachable from the Fleet screen until a vehicle picker exists - and
 * `ui/` has none today (`FleetScreen` renders `currentVehicle`, singular).
 * That is a known, deliberate gap, not a silent one: the alternative was
 * leaving its history welded to the wrong car.
 */
object MidnightImport {
    private const val TAG = "MidnightImport"
    private const val ASSET_DIR = "midnight_import"
    private const val MANIFEST_FILE = "manifest.json"
    private const val PREFS = "midnight_import"

    /**
     * Versioned deliberately, and bumped twice on 2026-08-03 for two different
     * false "clean" passes:
     *
     * - `completed` (v1) latched after a pass that imported ZERO rows. Every
     *   shard failed to open under its `.json.gz` name (see [SHARD_SUFFIXES]) and
     *   a missing shard returned `TableResult(0, 0)` without incrementing the
     *   failure count, so thirteen tables reported success having moved nothing.
     * - `completed_v2` latched the same way minutes later, during the fix for
     *   [SENTINEL_VEHICLE_ID], because the shard-name fix and the reconciliation
     *   gate were not yet in the build being tested.
     *
     * v3 is the first version where latching means something: a table only counts
     * as imported if its row count reconciles against the manifest's own stated
     * count. Bumping is safe because the whole import is identity-keyed and
     * idempotent - a re-run never duplicates a row that already landed.
     */
    private const val KEY_COMPLETED = "completed_v3"

    /**
     * `"default"` is the placeholder vehicle id on EVERY device that has ever run
     * this codebase or Midnight AI, which makes it the one `obdMac` value that is
     * NOT a portable identity - it means "this device's unpaired car", and it names
     * a different car on each one.
     *
     * On 2026-08-03 that bit. Midnight AI used `default` for the Mitsubishi
     * Outlander (no dongle ever paired to it). LEGION seeds its own fresh-install
     * placeholder - a 1998 Jeep Cherokee named "Midnight" - at the same key. The
     * import ran, [loadExistingKeys] found `default` already present, and the
     * Outlander's `vehicles` row was skipped as a duplicate.
     *
     * Its CHILDREN were not skipped. They key on `vehicleId = "default"`, which
     * matched nothing locally, so all of them imported and attached themselves to
     * the placeholder Cherokee: 5,242 obd_samples, 21 daily_drive_logs, 2
     * monthly_recaps, and a `vehicle_specs` row carrying the Outlander's VIN. The
     * database then asserted that a 1998 Jeep Cherokee was a 2.4L Mitsubishi.
     *
     * Nothing caught it. Every insert succeeded, so the pass reported clean and
     * latched its flag - the CLAUDE.md sec 4 rule 6 shape, where no step was
     * capable of failing. An incoming vehicle at this id is therefore always
     * re-keyed to a [syntheticVehicleId] before anything looks at it.
     */
    const val SENTINEL_VEHICLE_ID = "default"

    /** The column every child table names its vehicle by (`vehicles` uses `obdMac`). */
    private const val VEHICLE_COL = "vehicleId"

    /**
     * Names a table's shard can arrive under, most specific first. `.json.gz` is
     * what `tools/export_midnight_ai.py` writes; `.json` is what the packaged APK
     * actually contains, because the asset pipeline inflates and renames it. Both
     * are read, and the bytes are sniffed either way.
     */
    private val SHARD_SUFFIXES = listOf(".json.gz", ".json")

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
     * A stable, portable id for a vehicle arriving under [SENTINEL_VEHICLE_ID].
     *
     * Derived from the car's own make/model/year rather than minted randomly, so
     * that re-running the import produces the SAME id and the identity-keyed dedup
     * still holds - a UUID here would insert a second Outlander on every pass.
     *
     * The Outlander yields `imported-mitsubishi-outlander-2020`. A row with no
     * usable make/model/year at all yields `imported-vehicle`, which is still
     * better than `default`: it collides only with another equally blank imported
     * car, never with the local placeholder.
     */
    fun syntheticVehicleId(row: JSONObject): String {
        val year = row.optInt("year", 0)
        val slug = listOf(
            row.optString("make", ""),
            row.optString("model", ""),
            if (year > 0) year.toString() else "",
        ).joinToString("-") { it.trim().lowercase() }
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (slug.isBlank()) "imported-vehicle" else "imported-$slug"
    }

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
        val alreadyDone = prefs.getBoolean(KEY_COMPLETED, false)
        // Instrumentation, added 2026-08-12. On Kevin's device 5,263 obd_samples reappeared under
        // SENTINEL_VEHICLE_ID after every launch, with fresh autoincrement ids, proving a re-insert
        // - yet this object logged NOTHING across a full captured process log, which is only
        // possible if it returned here. Observed behaviour and control flow disagreed, so the gate
        // state is now stated out loud rather than inferred. If this line reports completed=true on
        // a launch that still re-seeds, the importer is exonerated and the caller is elsewhere.
        Log.i(TAG, "midnight_import gate: completed=$alreadyDone (key=$KEY_COMPLETED)")
        if (alreadyDone) return@withContext

        val assets = context.assets
        val manifestBytes = try {
            assets.open("$ASSET_DIR/$MANIFEST_FILE").use { it.readBytes() }
        } catch (e: IOException) {
            // No bundle in this build - the expected steady state for every
            // clone that isn't Kevin's own machine (the exporter's output is
            // gitignored). Nothing to do, nothing to warn about.
            return@withContext
        }

        val manifestCounts = try {
            parseManifest(manifestBytes)
        } catch (e: Exception) {
            Log.w(TAG, "manifest.json present but unreadable, aborting import", e)
            MidnightEvents.recordError("midnight_import_manifest", e)
            return@withContext
        }
        val manifestTables = manifestCounts.keys

        val db = CarDatabase.getDatabase(context).openHelper.writableDatabase
        var totalInserted = 0
        var totalSkipped = 0
        var totalRekeyed = 0
        var totalDiscarded = 0
        var failedTables = 0
        // Filled while `vehicles` imports (it is first, see the class doc's ORDER
        // section), consumed by every table after it: old sentinel id -> the
        // synthetic id its rows must actually carry.
        val remap = mutableMapOf<String, String>()
        for (spec in importOrder(manifestTables)) {
            val result = runCatching { importTable(db, assets, spec, remap, manifestCounts.getValue(spec.table)) }
                .getOrElse {
                    failedTables++
                    Log.w(TAG, "midnight_import ${spec.table} failed", it)
                    MidnightEvents.recordError("midnight_import_${spec.table}", it)
                    null
                } ?: continue
            totalInserted += result.inserted
            totalSkipped += result.skipped
            totalRekeyed += result.rekeyed
            totalDiscarded += result.discarded
            Log.i(
                TAG,
                "midnight_import ${spec.table}: inserted=${result.inserted} " +
                    "skipped=${result.skipped} rekeyed=${result.rekeyed} discarded=${result.discarded}",
            )
        }
        if (totalDiscarded > 0) {
            // Distinct from the re-key line below on purpose. A discard means a sentinel-keyed row
            // whose identity already exists at the destination was dropped rather than moved - the
            // exact duplication loop this function exists to stop (stage 1,
            // `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`).
            // Expected on a device that still has sentinel rows arriving from Drive (stage 2's
            // convergence claim was withdrawn by that same ticket) - the sign something is wrong
            // would be this NEVER appearing while `obd_samples` keeps growing, not this appearing.
            Log.w(TAG, "midnight_import: discarded $totalDiscarded sentinel row(s) already present under the synthetic id")
        }
        if (totalRekeyed > 0) {
            // Loud on purpose: this is rows already on disk being moved off the
            // wrong car, not routine import traffic. It should appear exactly once
            // per device and never again.
            Log.w(TAG, "midnight_import: re-keyed $totalRekeyed row(s) off '$SENTINEL_VEHICLE_ID' -> $remap")
            MidnightEvents.importRekeyed(totalRekeyed, remap)
        }
        // A local UPDATE is not enough, and believing it was is what caused
        // `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`.
        // Written unconditionally on a non-empty remap, not just when rows moved, because a device
        // can hold NO sentinel rows locally and still be handed them by Drive on a later sync.
        if (remap.isNotEmpty()) recordSentinelReassignments(db, remap)
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

    private data class TableResult(
        val inserted: Int,
        val skipped: Int,
        val rekeyed: Int = 0,
        /** Sentinel-keyed rows deleted because the synthetic id already held the same identity -
         * see [rekeyExistingRows]. Reported separately from [rekeyed] on purpose: folding the two
         * together is what made six duplicating passes read as six successful ones. */
        val discarded: Int = 0,
    )

    /** One table's worth of rows, in one transaction (CLAUDE.md L11/spec: not
     * one transaction per row - 11.5k of those on a phone is a full minute). */
    private fun importTable(
        db: SupportSQLiteDatabase,
        assets: AssetManager,
        spec: TableSpec,
        remap: MutableMap<String, String>,
        expectedRows: Int,
    ): TableResult {
        // The exporter writes `<table>.json.gz`, but the Android asset pipeline
        // INFLATES gzipped assets and drops the `.gz` from the packaged name -
        // verified by listing the built APK on 2026-08-03, where a 170-byte
        // `build_entries.json.gz` on disk arrives as a 199-byte
        // `assets/midnight_import/build_entries.json`. Opening only the `.gz`
        // name therefore threw IOException for EVERY table on EVERY device, which
        // is why this import had never moved a single row. Try both names, and let
        // SyncCodec sniff the bytes rather than trust either extension.
        val bytes = SHARD_SUFFIXES.firstNotNullOfOrNull { suffix ->
            try {
                assets.open("$ASSET_DIR/${spec.table}$suffix").use { it.readBytes() }
            } catch (e: IOException) {
                null
            }
        } ?: throw IOException(
            "manifest lists ${spec.table} but no shard exists under any of " +
                SHARD_SUFFIXES.joinToString(" / ") { "${spec.table}$it" },
        )
        val rows = SyncCodec.rowsFromNdjsonAuto(bytes)

        // Re-key BEFORE identity keys are computed or dedup runs, so every step
        // below sees only the portable id and never the sentinel.
        if (spec.table == "vehicles") {
            for (row in rows) {
                if (row.optString("obdMac", "") != SENTINEL_VEHICLE_ID) continue
                val synthetic = syntheticVehicleId(row)
                remap[SENTINEL_VEHICLE_ID] = synthetic
                row.put("obdMac", synthetic)
            }
        } else if (remap.isNotEmpty()) {
            for (row in rows) {
                val mapped = remap[row.optString(VEHICLE_COL, "")] ?: continue
                row.put(VEHICLE_COL, mapped)
            }
        }

        // Rows a PREVIOUS (v1) pass already inserted under the sentinel id are on
        // disk pointing at the wrong car. Move them before dedup, so they are then
        // recognised as already-present rather than inserted a second time.
        val rekey = if (spec.table != "vehicles" && remap.isNotEmpty()) {
            rekeyExistingRows(db, spec, rows, remap)
        } else {
            RekeyResult(0, 0)
        }

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

        // Reconcile against the bundle's OWN stated count, CLAUDE.md sec 4 rule 2
        // pointed at the import path. Every row the manifest claims must be
        // accounted for as either inserted now or already present; a shortfall
        // means rows were dropped, and dropping them quietly is what let this
        // import report thirteen clean tables while moving nothing at all.
        //
        // Rule 6 matters as much as rule 2 here: this check is deliberately
        // unsatisfiable by an empty extraction. Zero rows against a manifest that
        // claims 11,511 fails, where the old `return TableResult(0, 0)` passed.
        val accounted = inserted + skipped
        if (accounted != expectedRows) {
            throw IllegalStateException(
                "${spec.table}: manifest claims $expectedRows row(s), accounted for $accounted " +
                    "(inserted=$inserted skipped=$skipped) - refusing to call this table imported",
            )
        }
        return TableResult(inserted, skipped, rekey.moved, rekey.discarded)
    }

    /** What one [rekeyExistingRows] pass did: rows moved onto the synthetic id, and rows dropped
     * because the synthetic id already held the same identity. See that function's doc for why the
     * second number exists and why it is not a failure. */
    internal data class RekeyResult(val moved: Int, val discarded: Int)

    /**
     * Moves rows a v1 pass already wrote under [SENTINEL_VEHICLE_ID] onto the
     * synthetic id, in place.
     *
     * Precision matters more than speed here: the sentinel id is shared with
     * LEGION's own placeholder car, so `UPDATE ... WHERE vehicleId = 'default'`
     * would drag the user's OWN locally-recorded rows onto an imported vehicle
     * they have nothing to do with. Instead each bundle row is matched
     * individually on its full identity - with the vehicle column forced back to
     * the sentinel - so only rows this import is known to have created are
     * touched. Anything the driver generated locally under `default` stays put.
     *
     * **The destination is checked BEFORE the move, and an occupied destination means the source
     * row is DELETED rather than moved** (stage 1 of
     * `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`,
     * 2026-08-16). The original assumed the destination key was always free. It was not, in two
     * different ways:
     *
     * - On `vehicle_specs` (PK `vehicleId`) and `maintenance_items` (PK `vehicleId, serviceName`)
     *   the plain `UPDATE` hit a real constraint and threw. That rolled the table back, marked the
     *   pass failed, and left [KEY_COMPLETED] unlatched - so the import re-ran on EVERY launch,
     *   forever.
     * - On the tables with an autoincrement `id` and no constraint over their identity columns
     *   (`obd_samples`, `daily_drive_logs`, `monthly_recaps`) nothing objected. The row moved and
     *   landed beside the copy already there. `vehicleId` is part of the SYNC identity for those
     *   tables (`SyncEngine.REGISTRY`, `Mode.UNION`), so moving a row off the sentinel made it
     *   invisible to the next pull, which then re-inserted the whole batch from Drive under the
     *   sentinel again - one fresh copy per launch, 31,452 junk `obd_samples` rows over 5,242
     *   distinct identities measured on Kevin's phone before this fix.
     *
     * Deleting the source is safe precisely because of the identity match: a source row only
     * qualifies when its full identity matches a bundle row this import created, and the
     * destination already holds that same identity. The two are the same row by every column this
     * import keys on. (They can still differ in a non-identity column - a sample's `value`, say -
     * and the surviving copy wins. Choosing the destination is deliberate: it is the one later
     * passes and sync will agree on.)
     *
     * **SET-BASED, not row-at-a-time** (rewritten 2026-08-16 after the first cut of this fix hung
     * on-device - see `git stash`, "stage-1 rekey fix, HANGS on device - needs rework", and the
     * ticket's own "Stage 1 attempt" section). The first cut still issued one `UPDATE` or `DELETE`
     * per bundle row - up to 11,511 of them for `obd_samples` on Kevin's phone - and each one is a
     * `WHERE` clause over `pid`/`timestamp`/`vehicleId`, none of which [OdbSample] indexes (checked
     * 2026-08-16: no `@Index` on that entity, and none is being added here - CLAUDE.md sec 5 rules
     * out an unmigrated schema change). Without an index, EVERY one of those statements is a full
     * table scan, and the table it scans is the one this exact bug keeps inflating - so the cost
     * compounds with the duplication itself. That fits the observed shape: the
     * destination-occupancy check was ALREADY rewritten from a per-row `SELECT ... LIMIT 1` to a
     * single upfront load ([loadIdentitiesFor] - proven fine, kept here unchanged) before the hang
     * was diagnosed, and the hang persisted anyway - consistent with the statement-per-row `WHERE`
     * scan being the actual cost, not the occupancy check.
     *
     * This version keeps that one-time occupancy load, decides move-vs-discard for every candidate
     * row in memory (cheap - a `Set` lookup, no SQL), and then applies each decision as ONE
     * `DELETE`/`UPDATE` per table per destination, matched against a small SQLite TEMP table holding
     * just that batch's identities (never the persisted schema - dropped with the connection, exempt
     * from CLAUDE.md's migration rule). A table the size of `obd_samples` therefore costs, per
     * import pass: one full-table scan to load existing destination identities (already proven cheap
     * on-device), O(batch size) trivial temp-table inserts (no scan - the temp table starts empty),
     * and two full-table scans total for the DELETE and the UPDATE - a small constant number of
     * scans of the CURRENT table size, not one scan per bundle row. **This has not been run on a
     * device where it matters** - see this function's test coverage and the caller's own report for
     * what that means for confidence.
     *
     * A discarded row is NOT a failure and is counted separately, because reporting a discard as
     * `rekeyed=N` is what let this run for six passes looking like success.
     */
    @VisibleForTesting
    internal fun rekeyExistingRows(
        db: SupportSQLiteDatabase,
        spec: TableSpec,
        rows: List<JSONObject>,
        remap: Map<String, String>,
    ): RekeyResult {
        // `places` has no `vehicleId` column at all - checked on the FIRST row rather than per-row,
        // because every row in one table's shard shares the same schema, and checking before any SQL
        // runs is what keeps this a true no-op for such tables. The first cut of this fix broke that
        // by loading a destination-identity set BEFORE this guard, which threw
        // `no such column: vehicleId` on `places` on the A25 - caught then, re-pinned by a test now.
        if (rows.isNotEmpty() && !rows[0].has(VEHICLE_COL)) return RekeyResult(0, 0)

        val reverse = remap.entries.associate { (old, new) -> new to old }
        // The identity columns that actually distinguish rows WITHIN one destination vehicle.
        // Empty for `vehicle_specs`, whose whole identity IS the vehicle column - see the branch
        // below.
        val nonVehicleCols = spec.identity.filter { it != VEHICLE_COL }

        // Group candidate rows by which sentinel -> synthetic move they belong to. `remap` carries
        // exactly one entry in every case observed so far (one imported car per pass), but nothing
        // here assumes that - two imported vehicles in one bundle would each get their own move.
        val byMove = linkedMapOf<Pair<String, String>, MutableList<JSONObject>>()
        for (row in rows) {
            if (!row.has(VEHICLE_COL)) continue
            val newId = row.optString(VEHICLE_COL, "")
            val oldId = reverse[newId] ?: continue
            byMove.getOrPut(oldId to newId) { mutableListOf() }.add(row)
        }
        if (byMove.isEmpty()) return RekeyResult(0, 0)

        var moved = 0
        var discarded = 0
        db.beginTransaction()
        try {
            for ((move, candidateRows) in byMove) {
                val (oldId, newId) = move

                if (nonVehicleCols.isEmpty()) {
                    // Whole identity is the vehicle column itself (`vehicle_specs`) - at most one
                    // row can ever exist at `oldId` for this move, so there is nothing to batch:
                    // one query answers "is the destination occupied", one statement resolves it.
                    val occupied = db.query(
                        "SELECT 1 FROM `${spec.table}` WHERE `$VEHICLE_COL`=? LIMIT 1",
                        arrayOf<Any?>(newId),
                    ).use { it.moveToFirst() }
                    if (occupied) {
                        db.execSQL("DELETE FROM `${spec.table}` WHERE `$VEHICLE_COL`=?", arrayOf<Any?>(oldId))
                        discarded += changes(db)
                    } else {
                        db.execSQL(
                            "UPDATE `${spec.table}` SET `$VEHICLE_COL`=? WHERE `$VEHICLE_COL`=?",
                            arrayOf<Any?>(newId, oldId),
                        )
                        moved += changes(db)
                    }
                    continue
                }

                // One scan of the rows already at the destination - the piece already proven not to
                // be the hang's cause (see this function's doc). Everything after this is in-memory.
                val occupied = loadIdentitiesFor(db, spec, newId)
                val toDelete = mutableListOf<JSONObject>()
                val toMove = mutableListOf<JSONObject>()
                for (row in candidateRows) {
                    val destKey = nonVehicleCols.map { SyncCodec.sqlArg(row, it) }
                    // Set.add returns false when the key is already present - covers both "the
                    // destination already held this identity" and "an earlier row in THIS batch
                    // already claimed it" (a bundle-level duplicate), either way discarded rather
                    // than stacked.
                    if (!occupied.add(destKey)) toDelete.add(row) else toMove.add(row)
                }

                if (toDelete.isNotEmpty()) {
                    stageRekeyBatch(db, nonVehicleCols, toDelete)
                    db.execSQL(
                        "DELETE FROM `${spec.table}` WHERE `$VEHICLE_COL`=? AND EXISTS " +
                            "(SELECT 1 FROM `$REKEY_BATCH_TABLE` WHERE ${rekeyBatchMatch(spec.table, nonVehicleCols)})",
                        arrayOf<Any?>(oldId),
                    )
                    discarded += changes(db)
                    db.execSQL("DROP TABLE IF EXISTS `$REKEY_BATCH_TABLE`")
                }
                if (toMove.isNotEmpty()) {
                    stageRekeyBatch(db, nonVehicleCols, toMove)
                    db.execSQL(
                        "UPDATE `${spec.table}` SET `$VEHICLE_COL`=? WHERE `$VEHICLE_COL`=? AND EXISTS " +
                            "(SELECT 1 FROM `$REKEY_BATCH_TABLE` WHERE ${rekeyBatchMatch(spec.table, nonVehicleCols)})",
                        arrayOf<Any?>(newId, oldId),
                    )
                    moved += changes(db)
                    db.execSQL("DROP TABLE IF EXISTS `$REKEY_BATCH_TABLE`")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return RekeyResult(moved, discarded)
    }

    /** Fixed name, reused sequentially (dropped and recreated) rather than uniquely generated -
     * [rekeyExistingRows] never has two batches staged at once, so there is nothing for a second
     * name to disambiguate. Prefixed so it cannot collide with a real table name. */
    private const val REKEY_BATCH_TABLE = "_midnight_import_rekey_batch"

    /** `outer.col = batch.col AND ...` for every identity column that is not the vehicle column,
     * shared between the DELETE and UPDATE statements in [rekeyExistingRows] so the two cannot
     * drift apart. */
    private fun rekeyBatchMatch(table: String, cols: List<String>): String =
        cols.joinToString(" AND ") { "`$table`.`$it` = `$REKEY_BATCH_TABLE`.`$it`" }

    /**
     * Creates a fresh SQLite TEMP table holding [rows]' values for [cols], plus a temp index over
     * those same columns, and populates it via one compiled, reused `INSERT` statement rather than
     * re-parsing SQL text per row.
     *
     * **Not a persisted-schema change.** A TEMP table lives in `sqlite_temp_master`, scoped to this
     * connection, and is gone the moment it disconnects - CLAUDE.md sec 5's migration rule governs
     * the real `obd_samples` table, not a scratch structure that never outlives this function call.
     * The temp index exists so the `EXISTS` join in the caller does not have to fall back on
     * SQLite's automatic-index heuristic (on by default, but not something to depend on sight
     * unseen when an explicit index is one statement away).
     */
    private fun stageRekeyBatch(db: SupportSQLiteDatabase, cols: List<String>, rows: List<JSONObject>) {
        db.execSQL("DROP TABLE IF EXISTS `$REKEY_BATCH_TABLE`")
        val colList = cols.joinToString(",") { "`$it`" }
        db.execSQL("CREATE TEMP TABLE `$REKEY_BATCH_TABLE` ($colList)")
        val insert = db.compileStatement(
            "INSERT INTO `$REKEY_BATCH_TABLE` ($colList) VALUES (${cols.joinToString(",") { "?" }})",
        )
        try {
            for (row in rows) {
                insert.clearBindings()
                cols.forEachIndexed { i, col -> bindRekeyArg(insert, i + 1, SyncCodec.sqlArg(row, col)) }
                insert.executeInsert()
            }
        } finally {
            insert.close()
        }
        db.execSQL("CREATE INDEX `${REKEY_BATCH_TABLE}_idx` ON `$REKEY_BATCH_TABLE` ($colList)")
    }

    /** [SyncCodec.sqlArg]'s output (`null` / [Long] / [Double] / [String]) bound onto a compiled
     * statement - the same value shapes [insertRow] hands to `execSQL`'s varargs, spelled out
     * explicitly here because [SupportSQLiteStatement] has no varargs-array bind overload. */
    private fun bindRekeyArg(stmt: SupportSQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.bindNull(index)
            is Long -> stmt.bindLong(index, value)
            is Int -> stmt.bindLong(index, value.toLong())
            is Double -> stmt.bindDouble(index, value)
            else -> stmt.bindString(index, value.toString())
        }
    }

    /**
     * Identity tuples of rows already carrying [vehicleId], for [rekeyExistingRows]'s
     * is-the-destination-occupied question. Same shape as [loadExistingKeys] and deliberately so -
     * both compare a JSON-derived key against a cursor-derived one, and the type mapping in
     * [cursorValue] is what makes that comparison work at all.
     *
     * Scoped to one vehicle rather than the whole table because on the `syncId` tables the vehicle
     * column is not part of [TableSpec.identity], so an unscoped set could not tell "this syncId
     * exists somewhere" from "this syncId exists AT THE DESTINATION", which is the only question
     * worth asking. One scan of the table (filtered by an unindexed equality check, same cost as
     * any other single-statement scan here) - proven, on the A25, NOT to be what caused the hang
     * this function was rewritten to fix.
     */
    private fun loadIdentitiesFor(
        db: SupportSQLiteDatabase,
        spec: TableSpec,
        vehicleId: String,
    ): MutableSet<List<Any?>> {
        val cols = spec.identity.filter { it != VEHICLE_COL }.joinToString(",") { "`$it`" }
        val keys = mutableSetOf<List<Any?>>()
        db.query("SELECT $cols FROM `${spec.table}` WHERE `$VEHICLE_COL`=?", arrayOf<Any?>(vehicleId)).use { c ->
            while (c.moveToNext()) {
                keys.add((0 until c.columnCount).map { i -> cursorValue(c, i) })
            }
        }
        return keys
    }

    /**
     * Records the sentinel re-key as a **synced [com.kevin.legion.data.local.DriveReassignment]
     * rule**, so the correction survives a Drive round trip instead of being undone by one.
     *
     * This is the fix for `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`,
     * and the mechanism it uses already existed - it was built on 2026-07-16 for the identical
     * problem, and [com.kevin.legion.vehicle.VehicleController.reassignDrive]'s doc comment states
     * the hazard in as many words:
     *
     * > *"Writes a RULE rather than re-keying the rows directly: `obd_samples` syncs UNION on an
     * > identity that INCLUDES vehicleId, so a plain UPDATE would leave the originals on Drive under
     * > the old id, and the next sync would re-insert them - cloning the drive onto both cars
     * > instead of moving it, permanently, on every device."*
     *
     * [rekeyExistingRows] then went and did exactly that plain UPDATE. Measured consequence on
     * Kevin's A25: `obd_samples` reached 36,694 rows over 5,242 distinct identities, one fresh copy
     * per launch, because every sync pulled the sentinel-keyed rows back and the next re-key made
     * them look new again.
     *
     * What makes a rule different is not that it re-keys harder. It is WHERE
     * [com.kevin.legion.sync.SyncEngine] applies it: inside `syncFile`, after the merge and
     * **before** the converged snapshot is re-read and uploaded. So the rows Drive receives already
     * carry the new id, the sentinel-keyed originals stop coming back, and the correction converges
     * instead of oscillating.
     *
     * **The syncId is deterministic, not a fresh UUID.** Re-running the import must not add a second
     * rule saying the same thing: `drive_reassignments` is LWW keyed on `syncId`, so a stable id
     * makes a re-run overwrite its own rule rather than accumulate near-duplicates that
     * [com.kevin.legion.vehicle.DriveReassigner.plan] would then replay one after another.
     *
     * **Unbounded backwards, bounded at NOW going forwards** (`0 .. System.currentTimeMillis()`).
     * The rule shape is time-ranged because its original caller corrects ONE drive; here the whole
     * of a car's history is on the wrong id, so the past needs no lower bound.
     *
     * The upper bound is load-bearing and was very nearly `Long.MAX_VALUE`.
     * [SENTINEL_VEHICLE_ID] is not just the imported car's stale id - it is ALSO this device's
     * live placeholder for a car with no dongle paired, which is the whole reason it was ambiguous
     * enough to cause the 2026-08-03 mess in the first place. An unbounded rule would therefore
     * sweep every FUTURE sample recorded under the placeholder onto the imported car, silently,
     * forever, on every sync pass. Everything the bundle carries is by definition already in the
     * past at the moment this runs, so `now` covers all of it and none of what has not happened
     * yet. `DriveReassigner.plan` passes both bounds straight through to
     * `timestamp BETWEEN ? AND ?`.
     *
     * **Known gap, deliberate:** `SyncEngine.applyReassignments` rewrites `obd_samples` ONLY. The
     * other UNION tables whose identity includes `vehicleId` (`monthly_recaps`, `daily_drive_logs`,
     * `yearly_wrapped`) are not covered, and cannot be by this rule shape - they are keyed by
     * year/month/day, not by a millisecond timestamp, so a `fromMs`/`toMs` window does not address
     * their rows at all. Those tables are far smaller (2 and 24 rows on Kevin's phone against
     * 5,242), and extending the rule to them is its own design question rather than something to
     * improvise here. `maintenance_items`/`vehicle_specs`/`vehicles` need nothing: they are LWW over
     * a real primary key, so a stale sentinel row is replaced rather than duplicated, which is why
     * they never grew.
     */
    @VisibleForTesting
    internal fun recordSentinelReassignments(db: SupportSQLiteDatabase, remap: Map<String, String>) {
        val now = System.currentTimeMillis()
        for ((oldId, newId) in remap) {
            if (oldId == newId) continue
            db.execSQL(
                "INSERT OR REPLACE INTO `drive_reassignments` " +
                    "(`id`, `syncId`, `vehicleId`, `fromMs`, `toMs`, `newVehicleId`, `updatedAt`) " +
                    "VALUES ((SELECT `id` FROM `drive_reassignments` WHERE `syncId` = ?), ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    reassignmentSyncId(oldId, newId),
                    reassignmentSyncId(oldId, newId),
                    oldId,
                    0L,
                    now,
                    newId,
                    now,
                ),
            )
        }
        Log.w(TAG, "midnight_import: recorded ${remap.size} drive reassignment rule(s) so the re-key survives sync")
    }

    /** Stable across re-runs, so the import overwrites its own rule instead of stacking another. */
    private fun reassignmentSyncId(oldId: String, newId: String) = "midnight-import-rekey:$oldId->$newId"

    /** Rows affected by the last statement on this connection. */
    private fun changes(db: SupportSQLiteDatabase): Int =
        db.query("SELECT changes()").use { if (it.moveToFirst()) it.getInt(0) else 0 }

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
