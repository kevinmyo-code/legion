package com.kevin.legion.assistant.cards

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The phone's builders for the general kinds (2026-11-15), one per domain whose data a phone tool
 * reads: the laptop, a trip, weight, sleep, today's workout, groceries with their macros, an email
 * draft and the memory list. Each takes the SAME rows or result the tool built its answer from,
 * never the model's words, and is pure (no Context), so every one runs in a JVM test.
 *
 * What a source does not state is said in words: an unread reading is "not read", a night with
 * nothing logged is left out (never zero), a macro is an estimate, a drive time is the map's.
 */
object DomainCards {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** "2026-11-15T14:20" on [zone]'s clock, for a card's `at`. */
    fun at(instant: Instant?, zone: ZoneId): String? = instant?.atZone(zone)?.format(AT)

    internal fun dec(value: Double?, places: Int): BigDecimal? =
        value?.let { BigDecimal.valueOf(it).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros() }

    // --- Laptop: claude_session check ------------------------------------------------------------

    /**
     * A machine's latest health snapshot, as `claude_session` `check` read it: temperatures, load,
     * memory (a percent: the worker reports no gigabytes), disk, fans, state and power. Null when
     * the machine sends no health. Alerts and a pending restart are said in the note.
     */
    fun laptop(
        name: String,
        state: String,
        health: com.kevin.legion.claude.LaptopHealth?,
        zone: ZoneId,
    ): ConversationCard.Readings? {
        val h = health ?: return null
        val ramAlert = h.limits?.let { dec(it.ramHighPct, 0) }
        // An open alert marks the reading it is about, found by the engine's own threshold; with no
        // threshold sent, neither is marked and the note says it in words.
        fun hot(c: Double?) = "hot" in h.alerts && c != null && h.limits != null && c >= h.limits.hotC
        val items = buildList {
            add(Reading("State", null, "", text = state.replaceFirstChar(Char::uppercase)))
            add(Reading("CPU", dec(h.cpuC, 1), "°C", attention = hot(h.cpuC)))
            add(Reading("GPU", dec(h.gpuC, 1), "°C", attention = hot(h.gpuC)))
            add(Reading("CPU load", dec(h.cpuLoadPct, 0), "%", scale = PERCENT))
            add(
                Reading(
                    "Memory used",
                    dec(h.ramUsedPct, 0),
                    "%",
                    scale = PERCENT,
                    mark = ramAlert?.let { CardMark(it, "Alert at ${it.toPlainString()}%") },
                    attention = "ram_high" in h.alerts,
                ),
            )
            add(Reading("Disk C: free", dec(h.diskFreeGb, 1), "GB", attention = "disk_low" in h.alerts))
            h.fansRpm.forEach { (fan, rpm) -> add(Reading("Fan $fan", BigDecimal(rpm), "rpm")) }
            add(Reading("Power", null, "", text = h.onMains?.let { if (it) "On mains" else "On battery" }))
        }
        return ConversationCard.Readings(
            title = name,
            items = items,
            at = at(h.at, zone),
            note = laptopNote(h),
            open = CardDestination.LAPTOP,
            lead = 0,
        )
    }

    /** Open alerts and a pending restart in words; null with none. */
    private fun laptopNote(h: com.kevin.legion.claude.LaptopHealth): String? = buildList {
        com.kevin.legion.claude.LaptopHealthWording.alertWords(h).takeIf { it.isNotBlank() }?.let { add("$it.") }
        if (h.rebootPending == true) add("A Windows restart is pending.")
        if (h.laptopctlAgent == false) add("The laptopctl agent is not running.")
    }.joinToString(" ").ifBlank { null }

    /** The last 24 hours of CPU and GPU temperature the same snapshot carries; null with none. */
    fun laptopTemps(
        name: String,
        health: com.kevin.legion.claude.LaptopHealth?,
        zone: ZoneId,
    ): ConversationCard.Trend? {
        val points = health?.temps24h.orEmpty().filter { it.at != null }
        fun line(label: String, pick: (com.kevin.legion.claude.TempPoint) -> Double?) = TrendSeries(
            label,
            points.mapNotNull { p -> pick(p)?.let { TrendPoint(at(p.at, zone)!!, dec(it, 1)!!) } }
                .takeLast(ConversationCard.MAX_TREND),
        )
        val series = listOf(line("CPU") { it.cpuC }, line("GPU") { it.gpuC }).filter { it.points.isNotEmpty() }
        if (series.isEmpty()) return null
        val hot = health?.limits?.hotC?.let { dec(it, 1) }
        return ConversationCard.Trend(
            title = "$name temperatures",
            unit = "°C",
            series = series,
            period = "Last 24 hours",
            open = CardDestination.LAPTOP,
            guide = hot?.let { CardMark(it, "Alert at ${it.toPlainString()} °C") },
        )
    }

    /**
     * The laptop voice service, from the cascade's probe of its `/health` ([state]: ready, busy,
     * not_configured, unauthorized, unreachable). FOLLOW-UP: the publish call belongs in
     * `service/cascade/` (another branch), so nothing calls this yet.
     */
    fun voiceService(state: String, detail: String? = null): ConversationCard.StatusList {
        val (status, words) = when (state) {
            "ready" -> RowStatus.OK to "Answering."
            "busy" -> RowStatus.ATTENTION to "Busy with another conversation."
            "not_configured" -> RowStatus.UNKNOWN to "Not set up on this phone."
            "unauthorized" -> RowStatus.FAILED to "Refused this phone's key."
            else -> RowStatus.NOT_REACHED to "Did not answer."
        }
        return ConversationCard.StatusList(
            title = "Laptop voice",
            items = listOf(StatusRow("Voice service", status, listOfNotNull(words, detail).joinToString(" "))),
            open = CardDestination.LAPTOP,
        )
    }

    // --- Navigation: trip_status -----------------------------------------------------------------

    /**
     * The trip `trip_status` worded, from the same snapshot: arrival, time and distance left,
     * stops and road. Each value the map does not have is "not read", never zero. Never called
     * when not navigating ([com.kevin.legion.navigation.TripStatus.NotNavigating] is no card).
     */
    fun trip(
        destination: String,
        stops: Int,
        guidance: com.kevin.legion.navigation.GuidanceSnapshot,
        zone: ZoneId,
        routeDistanceM: Double? = null,
        units: com.kevin.legion.navigation.UnitSystem = com.kevin.legion.navigation.UnitSystem.device(),
    ): ConversationCard.Readings {
        val f = com.kevin.legion.navigation.NavFormat
        val metric = units == com.kevin.legion.navigation.UnitSystem.METRIC
        val per = if (metric) M_PER_KM else M_PER_MI
        val left = guidance.distanceLeftM?.takeIf { it >= 0 }
        val whole = routeDistanceM?.takeIf { left != null && it > 0 && it >= left }
        val items = buildList {
            val arrival = guidance.arrivalAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(CLOCK) }
            add(Reading("Arrival", null, "", text = arrival))
            add(Reading("Time left", null, "", text = guidance.durationLeftS?.let(f::duration)))
            if (left != null && whole != null) {
                // A bar of the distance left on the route's whole length, both the map's own figures.
                val leftIn = dec(left / per, 1)!!
                val scale = CardScale(BigDecimal.ZERO, dec(whole / per, 1)!!.max(leftIn).max(TENTH))
                add(Reading("Distance left", leftIn, if (metric) "km" else "mi", scale = scale))
            } else {
                add(Reading("Distance left", null, "", text = left?.let { f.distance(it) }))
            }
            if (stops > 0) add(Reading("Stops on the way", BigDecimal(stops), ""))
            guidance.road?.let { add(Reading("Road", null, "", text = it)) }
        }
        return ConversationCard.Readings(
            title = "To $destination",
            items = items,
            note = "Arrival and time left are the map's estimate from its latest update." +
                if (whole != null) " The bar is the distance left of the route as planned or last rerouted." else "",
            open = CardDestination.NAVIGATION,
            lead = 0,
        )
    }

    private val PERCENT = CardScale(BigDecimal.ZERO, BigDecimal.valueOf(PERCENT_MAX))
    private val TENTH = BigDecimal("0.1")
    private const val PERCENT_MAX = 100L
    private const val M_PER_MI = 1609.344
    private const val M_PER_KM = 1000.0

    // --- Email: the draft before send ------------------------------------------------------------

    private val REPLY = Regex("""^\s*(re|fw|fwd)\s*:""", RegexOption.IGNORE_CASE)

    /**
     * Kevin's own draft, from `email` `draft`'s result: who it is to and its subject. The tool
     * returns no body, so there is no gist. A subject that looks like a reply or a forward may
     * quote mail someone else wrote (read-through only, CLAUDE.md section 7), so it is not shown.
     */
    fun draft(to: String, subject: String): ConversationCard.Readings {
        val reply = REPLY.containsMatchIn(subject)
        val items = buildList {
            add(Reading("To", null, "", text = to))
            if (!reply) add(Reading("Subject", null, "", text = subject))
        }
        val note = listOfNotNull(
            "Not sent. Say send to send it, or open it in Gmail's Drafts.",
            "The subject is not shown: it may quote someone else's mail.".takeIf { reply },
            "The draft's text is not shown: the draft tool does not return it.",
        ).joinToString(" ")
        return ConversationCard.Readings(title = "Email draft", items = items, note = note)
    }

    // --- Memory: memory list ----------------------------------------------------------------------

    /**
     * What is kept about him, newest first, each with its day and how it was learned; [ids] lets the
     * card offer Forget (a hard delete through `MemoryController.forget`, the Memory screen's own
     * path). [total] counts every memory, so the rest are said as more.
     */
    fun memories(
        rows: List<com.kevin.legion.memory.MemberMemory>,
        total: Int,
        fromCache: Boolean,
    ): ConversationCard.Readings {
        val shown = rows.take(ConversationCard.MAX_ROWS)
        val items = shown.map { m ->
            Reading(
                label = "${com.kevin.legion.memory.MemoryWording.day(m.createdAtMs)}, " +
                    com.kevin.legion.memory.MemoryWording.provenance(m),
                value = null,
                unit = "",
                text = m.text,
                note = "Shared with the household.".takeIf { m.shared },
            )
        }
        val note = listOfNotNull(
            "Nothing is kept yet.".takeIf { total == 0 },
            "From the phone's copy; the engine could not be reached.".takeIf { fromCache },
            "Forget deletes a memory for good.".takeIf { shown.isNotEmpty() && !fromCache },
        ).joinToString(" ").ifBlank { null }
        return ConversationCard.Readings(
            title = "Memories",
            items = items,
            more = (total - shown.size).coerceAtLeast(0),
            note = note,
            open = CardDestination.MEMORY,
            // Offline, a forget could not reach the engine: the copy is shown read-only.
            forgetIds = if (fromCache) null else shown.map { it.id },
        )
    }

}
