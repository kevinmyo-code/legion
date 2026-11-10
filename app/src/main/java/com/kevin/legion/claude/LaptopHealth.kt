package com.kevin.legion.claude

import java.time.Duration
import java.time.Instant

/**
 * A machine's latest health snapshot (laptop-health 2026-11-10, `server/claude_session/health.py`):
 * the worker sends one about once a minute, the engine keeps it and a 10-minute history. Every
 * reading is null when the laptop could not read it. Null as a whole for a machine that sends none.
 */
data class LaptopHealth(
    /** When the engine received it. */
    val at: Instant?,
    val cpuC: Double? = null,
    val gpuC: Double? = null,
    val fansRpm: Map<String, Int> = emptyMap(),
    val cpuLoadPct: Double? = null,
    val ramUsedPct: Double? = null,
    val diskFreeGb: Double? = null,
    val uptimeS: Long? = null,
    /** laptopctl-agent answering; null when laptopctl is not installed. */
    val laptopctlAgent: Boolean? = null,
    val onMains: Boolean? = null,
    val rebootPending: Boolean? = null,
    /** Open alerts: offline, hot, disk_low, ram_high, agent_down. */
    val alerts: List<String> = emptyList(),
    /** The last 24 h, oldest first, one point per 10 minutes. */
    val temps24h: List<TempPoint> = emptyList(),
)

data class TempPoint(val at: Instant?, val cpuC: Double?, val gpuC: Double?)

/** Pure: a machine's health to short lines for the sheet and the screen. */
object LaptopHealthWording {

    /** One line for the sheet: "Alienware: online, CPU 61 C, GPU 44 C, RAM 70%, C: 180 GB free". */
    fun summary(m: ClaudeMachine, wording: ClaudeWording): String? {
        val h = m.health ?: return null
        val state = if (m.isOnline) "online" else wording.machineState(m.state, m.lastSeenAt)
        val parts = listOf(state) + readings(h)
        val alerts = alertWords(h)
        return "${m.name}: " + parts.joinToString(", ") + (if (alerts.isEmpty()) "" else ". " + alerts)
    }

    /** The screen's lines under the machine's name. */
    fun lines(h: LaptopHealth, wording: ClaudeWording): List<String> = buildList {
        val r = readings(h)
        if (r.isNotEmpty()) add(r.joinToString(", "))
        val more = buildList {
            h.cpuLoadPct?.let { add("CPU load ${it.toInt()}%") }
            if (h.fansRpm.isNotEmpty()) add("fans " + h.fansRpm.values.joinToString("/") + " rpm")
            h.uptimeS?.let { add("up " + uptime(it)) }
        }
        if (more.isNotEmpty()) add(more.joinToString(", "))
        val flags = buildList {
            if (h.onMains == false) add("on battery")
            if (h.laptopctlAgent == false) add("laptopctl agent not running")
            if (h.rebootPending == true) add("Windows restart pending")
        }
        if (flags.isNotEmpty()) add(flags.joinToString(", ").replaceFirstChar(Char::uppercase))
        alertWords(h).takeIf { it.isNotEmpty() }?.let(::add)
        add("Read " + wording.age(h.at))
    }

    fun readings(h: LaptopHealth): List<String> = buildList {
        h.cpuC?.let { add("CPU ${it.toInt()} C") }
        h.gpuC?.let { add("GPU ${it.toInt()} C") }
        h.ramUsedPct?.let { add("RAM ${it.toInt()}%") }
        h.diskFreeGb?.let { add("C: ${it.toInt()} GB free") }
    }

    fun alertWords(h: LaptopHealth): String =
        h.alerts.mapNotNull { ALERTS[it] }.joinToString(", ").let { if (it.isEmpty()) "" else "Alert: $it" }

    /** "3 d 4 h", "5 h 12 min", "40 min". */
    fun uptime(seconds: Long): String {
        val d = Duration.ofSeconds(seconds)
        return when {
            d.toDays() > 0 -> "${d.toDays()} d ${d.toHoursPart()} h"
            d.toHours() > 0 -> "${d.toHours()} h ${d.toMinutesPart()} min"
            else -> "${d.toMinutes()} min"
        }
    }

    /** The hotter of CPU and GPU per point, nulls dropped: the sparkline's values. */
    fun spark(h: LaptopHealth): List<Float> =
        h.temps24h.mapNotNull { p -> listOfNotNull(p.cpuC, p.gpuC).maxOrNull()?.toFloat() }

    private val ALERTS = mapOf(
        "offline" to "offline",
        "hot" to "running hot",
        "disk_low" to "low disk",
        "ram_high" to "RAM full",
        "agent_down" to "laptopctl stopped",
    )
}
