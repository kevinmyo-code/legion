package com.kevin.legion.vehicle.diag

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DiagScan
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A stored scan with its decoded report. */
data class StoredScan(val row: DiagScan, val report: ScanReport)

/**
 * Room and asset side of diagnostics: saving and reading scans, the car's make, the bundled code
 * table and packs. Phone only (the engine side is a follow-up).
 */
object DiagScanStore {
    private const val TAG = "DiagScanStore"

    @Volatile private var table: Map<String, String>? = null

    @Volatile private var packs: List<PackReading>? = null

    suspend fun save(context: Context, carId: String, report: ScanReport): Long = withContext(Dispatchers.IO) {
        val faults = buildSet {
            report.engine?.let { e ->
                listOf(e.stored, e.pending, e.permanent).forEach { r -> r.dtcs.forEach { add(it.code) } }
            }
            report.modules.forEach { m -> m.codes.dtcs.filter { it.fault }.forEach { add("${m.address}:${it.code}") } }
        }
        CarDatabase.getDatabase(context).diagScanDao().insert(
            DiagScan(
                vehicleId = carId,
                startedAtMs = report.startedAtMs,
                finishedAtMs = report.finishedAtMs,
                bus = report.bus.name,
                moduleCount = report.modules.size,
                faultCount = faults.size,
                complete = report.complete,
                reportJson = report.toJson(),
            ),
        )
    }

    /** Keeps the clear sent from scan [scanId], raw replies included (section 4 rule 8). */
    suspend fun saveClear(context: Context, scanId: Long, run: ClearRun) = withContext(Dispatchers.IO) {
        val json = ScanReport.JSON.encodeToString(ClearRun.serializer(), run)
        CarDatabase.getDatabase(context).diagScanDao().setClear(scanId, json)
    }

    /** Newest first; rows whose JSON no longer parses are skipped, never shown as empty scans. */
    suspend fun latest(context: Context, carId: String, limit: Int = 2): List<StoredScan> =
        withContext(Dispatchers.IO) {
            CarDatabase.getDatabase(context).diagScanDao().latest(carId, limit).mapNotNull { row ->
                ScanReport.fromJson(row.reportJson)?.let { StoredScan(row, it) }
            }
        }

    suspend fun get(context: Context, id: Long): StoredScan? = withContext(Dispatchers.IO) {
        CarDatabase.getDatabase(context).diagScanDao().get(id)?.let { row ->
            ScanReport.fromJson(row.reportJson)?.let { StoredScan(row, it) }
        }
    }

    /** The car's make: its record first, then the VIN's maker code (WMI). Null when neither says. */
    suspend fun makeFor(context: Context, carId: String): String? {
        val onFile = FleetEngineStore.getAll(context).firstOrNull { it.obdMac == carId }?.make
        if (!onFile.isNullOrBlank()) return onFile
        val vin = DongleCar.vinOfPending(carId)
            ?: withContext(Dispatchers.IO) { CarDatabase.getDatabase(context).vehicleSpecDao().get(carId)?.vin }
        return vin?.let(Wmi::make)
    }

    fun codeTable(context: Context): Map<String, String> = table ?: runCatching {
        DtcText.parseTable(context.assets.open("dtc_sae_generic.csv").bufferedReader().use { it.readText() })
    }.getOrElse {
        Log.w(TAG, "code table failed to load: $it")
        emptyMap()
    }.also { table = it }

    fun packs(context: Context): List<PackReading> = packs ?: runCatching {
        DiagPacks.parse(context.assets.open("diag_packs.json").bufferedReader().use { it.readText() })
    }.getOrElse {
        Log.w(TAG, "packs failed to load: $it")
        emptyList()
    }.also { packs = it }

    fun describe(context: Context, code: String): DtcText.Meaning =
        DtcText.describe(code, codeTable(context), DtcDescriptions.loadSeed(context))
}

/** The VIN's first three characters (World Manufacturer Identifier) for the makes this app has packs or tables for. */
object Wmi {
    private val MAKES = mapOf(
        "Ford" to listOf(
            "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FA", "2FM", "2FT", "3FA", "3FM", "3FT", "1LN", "5LM",
        ),
        "Mitsubishi" to listOf("JA3", "JA4", "JA7", "4A3", "4A4", "ML3", "ML4", "JMY"),
        "Jeep" to listOf("1J4", "1J8", "1C4", "3C4"),
        "Toyota" to listOf("JTD", "JTE", "JTM", "JTN", "2T3", "4T1", "4T3", "5TD", "5TF"),
    )
    private const val WMI_LEN = 3

    fun make(vin: String): String? {
        val wmi = vin.trim().uppercase().take(WMI_LEN)
        return MAKES.entries.firstOrNull { wmi in it.value }?.key
    }
}
