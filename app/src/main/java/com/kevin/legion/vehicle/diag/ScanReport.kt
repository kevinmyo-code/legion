package com.kevin.legion.vehicle.diag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How one read went. Only [READ] carries an answer; every other value is said in words and is
 * never shown as "no codes" (Kevin, 2026-11-15: a refusal is not a clean bill).
 */
@Serializable
enum class Outcome(val words: String) {
    READ("read"),
    REFUSED("refused"),
    NO_ANSWER("no answer"),
    PARTIAL("reply cut off"),
    NOT_SUPPORTED("the adapter did not understand the request"),
    NOT_READ("not read"),
}

/** A list of codes from one read, with how the read went and the raw reply it came from (§4 rule 8). */
@Serializable
data class CodeRead(
    val outcome: Outcome,
    val service: String,
    val dtcs: List<ModuleDtc> = emptyList(),
    val detail: String? = null,
    val raw: String = "",
)

/** One identification read (0x22 DID). [value] is ASCII when printable, hex otherwise. */
@Serializable
data class IdRead(val did: String, val label: String, val outcome: Outcome, val value: String? = null)

/** A module that answered at [address] (a 3-digit 11-bit id, or 8-digit 29-bit id). */
@Serializable
data class ModuleReport(
    val address: String,
    val responseAddress: String,
    /** A name from the make's table or from the module itself; null = unidentified. */
    val name: String? = null,
    val nameSource: String? = null,
    /** Airbag / restraint / occupant-classification module, by any signal. Never cleared. */
    val restraint: Boolean = false,
    val codes: CodeRead,
    val ids: List<IdRead> = emptyList(),
)

/** The engine's generic OBD answer: Mode 03 stored, 07 pending, 0A permanent. */
@Serializable
data class EngineReport(
    val stored: CodeRead,
    val pending: CodeRead,
    val permanent: CodeRead,
    /** The ids (or legacy addresses) that answered the generic request. */
    val responders: List<String> = emptyList(),
)

/** What was tried, so a partial scan says "N of M" and never reads as a whole one. */
@Serializable
data class Coverage(
    val mode: String,
    val planned: Int,
    val probed: Int,
    val ranges: String,
    val timeBudgetHit: Boolean = false,
)

@Serializable
data class AdapterSummary(val kind: String, val ati: String, val sti: String? = null, val words: String)

/** A per-make extra reading's result. Always unverified until its pack row has a `verified_on`. */
@Serializable
data class PackReadingResult(
    val key: String,
    val label: String,
    val address: String,
    val outcome: Outcome,
    val value: String? = null,
    val unit: String = "",
    val verified: Boolean = false,
    val raw: String = "",
    val source: String = "",
)

/** One whole scan, as stored in `diag_scans.reportJson` and handed to the voice result. */
@Serializable
data class ScanReport(
    val schema: Int = SCHEMA,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val adapter: AdapterSummary,
    val bus: BusKind,
    val make: String? = null,
    val engine: EngineReport? = null,
    val modules: List<ModuleReport> = emptyList(),
    val coverage: Coverage,
    val readings: List<PackReadingResult> = emptyList(),
    /** Plain-word facts about the scan itself: what could not be reached and why. */
    val notes: List<String> = emptyList(),
    /** Why the scan stopped before reading anything, in words; null when it ran. */
    val stopped: String? = null,
) {
    val complete: Boolean get() = stopped == null && !coverage.timeBudgetHit && coverage.probed >= coverage.planned

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = 1
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): ScanReport? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}
