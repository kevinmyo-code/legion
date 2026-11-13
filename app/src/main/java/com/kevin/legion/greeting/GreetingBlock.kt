package com.kevin.legion.greeting

import java.time.LocalTime

/**
 * Renders the facts pack as the one text block the greeting model sees, and picks the one topic the
 * greeting leads with (ADR 0091). Pure: no clock, no network, no Android.
 *
 * CLAUDE.md section 1: a proactive prompt states its facts or forbids its subject. So the block
 * states every source's facts, or says in words that the source is empty (read, nothing on file) or
 * could not be read and why, and says outright that nothing outside it may be mentioned.
 */
object GreetingBlock {
    private val LABELS = mapOf(
        "ended_events" to "Calendar events that ended in the last 24 hours",
        "today_events" to "Today's remaining events",
        "due_soon" to "To-dos and Canvas work due within 24 hours",
        "body" to "Today's workout and the latest weigh-in",
        "money" to "This month's budgets",
        GreetingPlace.SOURCE to "Where he is",
    )

    /** Every source in the order the block prints them: the engine's five, then the phone's place. */
    fun allSources(pack: FactsPack, place: GreetingSource): List<GreetingSource> = pack.sources + place

    /** A topic may be raised only when it is neither raised nor answered nor silenced. */
    fun offerable(pack: FactsPack, item: GreetingItem): Boolean =
        !item.raised && item.topic !in pack.said && item.topic !in pack.closed

    /** True when at least one item can be raised: with none, the greeting stays the scripted line. */
    fun hasTopic(pack: FactsPack, place: GreetingSource): Boolean =
        allSources(pack, place).any { s -> s.state == SourceState.FACTS && s.items.any { offerable(pack, it) } }

    fun render(pack: FactsPack, place: GreetingSource, now: LocalTime): String {
        val lines = mutableListOf<String>()
        lines += "FACTS PACK. Today is ${pack.today}; his clock reads UTC${pack.utcOffset} and it is " +
            "${"%02d:%02d".format(now.hour, now.minute)}. These are the ONLY things the greeting may mention."
        pack.zoneNote?.let { lines += it }
        if (!pack.topicsRead) {
            lines += "Which topics were already raised could not be read" +
                (pack.topicsReason?.let { ": $it" } ?: ".") + " Nothing was left out on that account."
        }
        for (s in allSources(pack, place)) {
            val label = LABELS[s.name] ?: s.name
            when (s.state) {
                SourceState.FACTS -> {
                    lines += "- $label (facts):"
                    s.items.forEach {
                        val mark = if (offerable(pack, it)) "" else " [already raised or answered: do not mention]"
                        lines += "    * ${it.says}$mark"
                    }
                }
                SourceState.EMPTY -> lines += "- $label (EMPTY: read, and nothing is on file): ${s.says}"
                SourceState.UNREADABLE -> lines += "- $label (COULD NOT BE READ, which is not the same as empty): " +
                    "${s.reason ?: s.says}"
            }
            s.note?.let { lines += "    note: $it" }
        }
        return lines.joinToString("\n")
    }

    /**
     * The time-of-day order topics are tried in. Place is first when he is away because it is the
     * one fact that changes without his doing anything; after that the morning leans on what is
     * ahead, the evening on what happened, and the small hours on neither.
     */
    internal fun order(hour: Int): List<String> {
        val p = GreetingPlace.SOURCE
        return when (hour) {
            in MORNING_START until MIDDAY_START ->
                listOf(p, "due_soon", "today_events", "ended_events", "body", "money")
            in MIDDAY_START until EVENING_START ->
                listOf(p, "today_events", "due_soon", "ended_events", "money", "body")
            in EVENING_START until NIGHT_START ->
                listOf(p, "ended_events", "body", "due_soon", "today_events", "money")
            else -> listOf(p, "ended_events", "body", "due_soon", "money", "today_events")
        }
    }

    /**
     * The single topic the greeting leads with: the first source in [order] with an offerable item.
     * Never an item in `said`, `closed` or marked raised. Within a source: the most recent ended
     * event, the workout before the weigh-in, an over-cap budget before a near-cap one, and a budget
     * that is on pace is not a topic at all (nothing to say, and money is the easiest topic to nag).
     */
    fun pick(pack: FactsPack, place: GreetingSource, hour: Int): GreetingItem? {
        val by = allSources(pack, place).associateBy { it.name }
        return order(hour).firstNotNullOfOrNull { name ->
            by[name]?.takeIf { it.state == SourceState.FACTS }
                ?.let { choose(name, it.items.filter { i -> offerable(pack, i) }) }
        }
    }

    private fun choose(name: String, open: List<GreetingItem>): GreetingItem? = when (name) {
        "ended_events" -> open.lastOrNull()
        "body" -> open.firstOrNull { it.kind == "workout" } ?: open.firstOrNull()
        "money" -> open.firstOrNull { it.says.contains("Over the cap.") }
            ?: open.firstOrNull { it.says.contains("Past 80% of the cap.") }
        else -> open.firstOrNull()
    }

    private const val MORNING_START = 5
    private const val MIDDAY_START = 11
    private const val EVENING_START = 17
    private const val NIGHT_START = 22
}
