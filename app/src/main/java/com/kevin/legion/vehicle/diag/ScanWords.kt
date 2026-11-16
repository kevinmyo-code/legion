package com.kevin.legion.vehicle.diag

/**
 * The plain words a scan, a clear question and a clear result are spoken and shown in. One place,
 * so the voice result and the Faults screen cannot say different things about the same scan.
 */
object ScanWords {
    const val ESTIMATE_RULE = "Any explanation of a manufacturer-specific code that is not in this result is an " +
        "estimate and must be said as one. Unverified readings must be called unverified."

    fun summary(r: ScanReport): String {
        r.stopped?.let { return it }
        val faulty = r.modules.filter { m -> m.codes.dtcs.any { it.fault } }
        val unread = r.modules.filter { it.codes.outcome != Outcome.READ }
        val engineCodes = r.engine?.let { e ->
            (e.stored.dtcs + e.pending.dtcs + e.permanent.dtcs).map { d -> d.code }.distinct()
        }
        return buildList {
            add(engineLine(r, engineCodes))
            if (r.bus.isCan) {
                val list = faulty.joinToString(prefix = if (faulty.isEmpty()) "" else ": ") {
                    label(it) + " " + codes(it)
                }
                add("${r.modules.size} module(s) answered; ${faulty.size} with fault codes$list.")
                if (unread.isNotEmpty()) {
                    val why = unread.joinToString { "${label(it)} (${it.codes.detail ?: it.codes.outcome.words})" }
                    add("Codes not read from $why.")
                }
                r.coverage.interrupted?.let { add(it) }
                if (!r.complete) add("Partial scan: ${r.coverage.probed} of ${r.coverage.planned} addresses probed.")
            }
            addAll(r.notes.filterNot { it == ModuleScanner.SECOND_NETWORK_NOTE })
        }.joinToString(" ")
    }

    private fun engineLine(r: ScanReport, codes: List<String>?): String {
        val e = r.engine ?: return "Generic engine codes were not read."
        val failed = listOf(e.stored, e.pending).filter { it.outcome != Outcome.READ }
        return when {
            codes == null -> "Generic engine codes were not read."
            failed.size == 2 -> "The engine did not give its generic codes (" +
                (e.stored.detail ?: e.stored.outcome.words) + ")."
            // One of the two reads failed: say which, never "no stored or pending codes".
            failed.size == 1 -> engineHalf(storedFailed = failed.single() === e.stored, failed.single(), codes)
            codes.isEmpty() -> "Engine (generic): no stored or pending codes."
            else -> "Engine (generic): ${codes.joinToString()}."
        }
    }

    private fun engineHalf(storedFailed: Boolean, failed: CodeRead, codes: List<String>): String {
        val (lost, got) = if (storedFailed) "stored" to "pending" else "pending" to "stored"
        val have = if (codes.isEmpty()) "no $got codes" else codes.joinToString()
        return "Engine (generic): $have; $lost codes not read (${failed.detail ?: failed.outcome.words})."
    }

    fun label(m: ModuleReport) = m.name?.let { "$it at ${m.address}" } ?: "an unidentified module at ${m.address}"

    private fun codes(m: ModuleReport) = m.codes.dtcs.filter { it.fault }
        .joinToString(prefix = "(", postfix = ")") { "${it.code} ${it.words.joinToString("/")}" }

    /** The confirm question: names the car, every module that would be cleared, and what is left out. */
    fun clearQuestion(carLabel: String, plan: ClearPlan): String {
        if (plan.targets.isEmpty()) {
            return "Nothing on the $carLabel can be cleared from the last scan." + excludedLine(plan)
        }
        return "On the $carLabel, clear codes in: ${plan.targets.joinToString { it.label }}. Clearing wipes the " +
            "stored codes and freeze data in those modules, and the engine's readiness monitors if the engine is " +
            "included. Engine off, key at ON, parked." + excludedLine(plan) + " Do you want me to clear?"
    }

    private fun excludedLine(plan: ClearPlan) = if (plan.excluded.isEmpty()) {
        ""
    } else {
        " Not cleared: " + plan.excluded.joinToString("; ") { "${it.label}, ${it.reason}" } + "."
    }

    /** Exactly which modules confirmed, and what happened to each of the others. */
    fun clearSummary(carLabel: String, run: ClearRun): String {
        run.refusedAll?.let { return it }
        val byOutcome = run.results.groupBy { it.outcome }
        return buildList {
            val confirmed = byOutcome[ClearOutcome.CONFIRMED].orEmpty()
            add(if (confirmed.isEmpty()) "No module on the $carLabel confirmed a clear." else
                "Confirmed clear on the $carLabel: ${confirmed.joinToString { it.target.label }}.")
            for (o in ClearOutcome.entries.filter { it != ClearOutcome.CONFIRMED }) {
                byOutcome[o]?.let { list ->
                    add("${list.joinToString { it.target.label }}: ${o.words}" +
                        list.mapNotNull { it.after?.takeIf { a -> a.isNotEmpty() } }.flatten().let {
                            if (it.isEmpty()) "" else " (${it.joinToString()})"
                        } + ".")
                }
            }
            run.note?.let { add(it) }
            if (confirmed.isNotEmpty()) add("A confirmed clear means the erase took, not that the fault is fixed.")
        }.joinToString(" ")
    }
}
