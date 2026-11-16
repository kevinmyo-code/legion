package com.kevin.legion.vehicle.diag

import kotlinx.serialization.Serializable

/** Something a clear may be sent to. [address] "engine" means the generic Mode 04 (functional). */
@Serializable
data class ClearTarget(val address: String, val label: String, val service: String)

@Serializable
data class ClearExcluded(val label: String, val reason: String)

@Serializable
data class ClearPlan(val targets: List<ClearTarget>, val excluded: List<ClearExcluded>)

/**
 * Which modules from a scan may be cleared, failing closed.
 *
 * A module is offered only when it had fault codes that were READ (so a re-read can confirm the
 * clear) AND it is positively known not to be a restraint module: either its address is one ISO
 * 15765-4 reserves for emissions ECUs (7E0-7E7), or the make's table names it AND the table's
 * restraint address answered in this same scan (so the table's map of this car holds). Every
 * restraint module is excluded in this build (Kevin, 2026-11-15), and so is every module nobody
 * can name, because an unnamed module could be the airbag module.
 */
object ClearPlanner {
    const val ENGINE = "engine"
    const val SERVICE_MODE_04 = "OBD mode 04"
    const val SERVICE_UDS = "UDS 14 FF FF FF"
    const val SERVICE_KWP = "KWP2000 14 FF 00"
    const val RESTRAINT_RULE = "airbag / restraint module: clearing it is not allowed in this build"
    const val UNNAMED_RULE = "not positively identified, so it cannot be ruled out as the airbag module"
    const val UNREAD_RULE = "its codes could not be read, so a clear could not be confirmed"

    fun plan(report: ScanReport): ClearPlan {
        val targets = mutableListOf<ClearTarget>()
        val excluded = mutableListOf<ClearExcluded>()
        report.engine?.let { e ->
            val hasCodes = listOf(e.stored, e.pending).any { it.outcome == Outcome.READ && it.dtcs.isNotEmpty() }
            if (hasCodes) targets += ClearTarget(ENGINE, engineLabel(e), SERVICE_MODE_04)
        }
        val tableHolds = tableHolds(report)
        for (m in report.modules) {
            val label = m.name?.let { "$it (${m.address})" } ?: "module ${m.address}"
            val faults = m.codes.dtcs.any { it.fault }
            when {
                m.restraint -> excluded += ClearExcluded(label, RESTRAINT_RULE)
                m.codes.outcome != Outcome.READ -> excluded += ClearExcluded(label, UNREAD_RULE)
                !faults -> Unit
                ModuleNaming.isEmissionsAddress(m.address) || (m.name != null && m.address in tableHolds) ->
                    targets += ClearTarget(m.address, label, serviceFor(m.codes))
                else -> excluded += ClearExcluded(label, UNNAMED_RULE)
            }
        }
        return ClearPlan(targets, excluded)
    }

    /** The table-named addresses that may be trusted: only when one of the table's restraint ids answered. */
    private fun tableHolds(report: ScanReport): Set<String> {
        val known = ModuleCandidates.known(report.make)
        val answered = report.modules.map { it.address }.toSet()
        val restraintSeen = known.any { it.restraint && Hex.id(it.requestId) in answered }
        return if (restraintSeen) known.filterNot { it.restraint }.map { Hex.id(it.requestId) }.toSet() else emptySet()
    }

    private fun serviceFor(read: CodeRead) = if (read.service.startsWith("KWP")) SERVICE_KWP else SERVICE_UDS

    private fun engineLabel(e: EngineReport) =
        if (e.responders.isEmpty()) {
            "engine (generic)"
        } else {
            "engine (generic, answered by ${e.responders.joinToString()})"
        }
}
