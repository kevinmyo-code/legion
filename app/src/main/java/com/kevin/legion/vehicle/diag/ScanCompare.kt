package com.kevin.legion.vehicle.diag

import kotlinx.serialization.Serializable

/**
 * Two scans of one car side by side: codes that are new, codes gone, codes still there. A code is
 * called gone only when the place it came from was READ again in the newer scan; a module or mode
 * that was refused, silent or not reached the second time puts its old codes in [notRechecked],
 * because "we could not ask" is not "it cleared".
 */
@Serializable
data class ScanDiff(
    val added: List<String>,
    val gone: List<String>,
    val unchanged: List<String>,
    val notRechecked: List<String>,
)

object ScanCompare {

    /** Each place a code can come from, with whether it was read and the fault codes it gave. */
    private data class Source(val label: String, val read: Boolean, val codes: Set<String>)

    fun diff(older: ScanReport, newer: ScanReport): ScanDiff {
        val before = sources(older)
        val after = sources(newer)
        val added = mutableListOf<String>()
        val gone = mutableListOf<String>()
        val same = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for ((place, now) in after) {
            val was = before[place]?.codes.orEmpty()
            now.codes.filter { it !in was }.mapTo(added) { "$it (${now.label})" }
            now.codes.filter { it in was }.mapTo(same) { "$it (${now.label})" }
        }
        for ((place, then) in before) {
            val now = after[place]
            for (code in then.codes) {
                when {
                    now == null || !now.read -> unknown += "$code (${then.label})"
                    code !in now.codes -> gone += "$code (${then.label})"
                }
            }
        }
        return ScanDiff(added, gone, same, unknown)
    }

    private fun sources(r: ScanReport): Map<String, Source> = buildMap {
        r.engine?.let { e ->
            put("engine stored", source("engine, stored", e.stored))
            put("engine pending", source("engine, pending", e.pending))
            put("engine permanent", source("engine, permanent", e.permanent))
        }
        for (m in r.modules) put(m.address, source(m.name ?: "module ${m.address}", m.codes))
    }

    private fun source(label: String, read: CodeRead) = Source(
        label = label,
        read = read.outcome == Outcome.READ,
        codes = read.dtcs.filter { it.fault }.map { it.code }.toSet(),
    )
}
