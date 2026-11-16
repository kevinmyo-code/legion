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

    /**
     * [lead], when given, is marked `[LEAD]` in the block: the prompt names the subject in a few
     * words and points at this line for its facts, rather than pasting the fact text in as words to
     * say (2026-11-16: handed the fact text, the model read it out with every cent).
     */
    fun render(pack: FactsPack, place: GreetingSource, now: LocalTime, lead: GreetingItem? = null): String {
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
                        val mark = when {
                            !offerable(pack, it) -> " [already raised or answered: do not mention]"
                            it == lead -> " $LEAD_MARK"
                            else -> ""
                        }
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

    /** The topic a greeting leads with, and the source it came from. */
    data class Lead(val source: String, val item: GreetingItem) {
        val kind: String get() = kindOfSource(source)
    }

    /**
     * What this phone has led with lately ([GreetingPackCache.history]): the lead kinds of today's
     * greetings in order, and the kind of the very last one whatever its day. A greeting with no
     * topic is the kind [HELLO], so it ends a run.
     */
    data class History(val today: List<String> = emptyList(), val last: String? = null) {
        companion object {
            val NONE = History()
        }
    }

    /**
     * The single topic the greeting leads with: the first source in [order] with an offerable item
     * that the caps allow. Never an item in `said`, `closed` or marked raised. Within a source: the
     * most recent ended event, the workout before the weigh-in. Null means no topic: the greeting is
     * a hello about nothing ([GreetingPrompt.hello]).
     *
     * The caps (Kevin, 2026-11-16: "most of them were for budget"; each budget is its own topic, so
     * once the others were raised every later open led with another budget):
     * - money leads only for a budget OVER its cap, and at most once a day; near the cap or on pace is
     *   not a topic (nothing to say, and money is the easiest topic to nag about);
     * - body (workout or weigh-in) leads at most once a day (its engine key is per day too);
     * - no kind leads twice in a row ([History.last]); the two calendar sources are one kind.
     */
    fun lead(pack: FactsPack, place: GreetingSource, hour: Int, history: History = History.NONE): Lead? {
        val by = allSources(pack, place).associateBy { it.name }
        return order(hour).firstNotNullOfOrNull { name ->
            val kind = kindOfSource(name)
            val capped = kind == history.last || (kind in ONCE_A_DAY && kind in history.today)
            if (capped) return@firstNotNullOfOrNull null
            by[name]?.takeIf { it.state == SourceState.FACTS }
                ?.let { choose(name, it.items.filter { i -> offerable(pack, i) }) }
                ?.let { Lead(name, it) }
        }
    }

    /** [lead]'s item alone. */
    fun pick(pack: FactsPack, place: GreetingSource, hour: Int, history: History = History.NONE): GreetingItem? =
        lead(pack, place, hour, history)?.item

    private fun choose(name: String, open: List<GreetingItem>): GreetingItem? = when (name) {
        "ended_events" -> open.lastOrNull()
        "body" -> open.firstOrNull { it.kind == "workout" } ?: open.firstOrNull()
        "money" -> open.firstOrNull { it.says.contains(OVER_CAP) }
        else -> open.firstOrNull()
    }

    /** A source's lead kind: the two calendar sources are one kind, every other source its own. */
    fun kindOfSource(source: String): String = when (source) {
        "ended_events", "today_events" -> CALENDAR
        "due_soon" -> "due"
        else -> source
    }

    /**
     * A topic key's lead kind, so a raise recorded by key ([GreetingPackCache.noteRaised]) counts
     * against its source. The keys are the engine's (`server/greeting/facts.py`: `event:`, `due:`,
     * `body:<day>`, `money:<month>:<category>`) and the phone's `place:<area>`. Null for a key of
     * no known shape, which then caps nothing.
     */
    fun kindOfTopic(topic: String): String? = when (topic.substringBefore(':', "")) {
        "event" -> CALENDAR
        "due" -> "due"
        "body" -> "body"
        "money" -> "money"
        GreetingPlace.SOURCE -> GreetingPlace.SOURCE
        else -> null
    }

    /** The lead kind of a greeting with no topic. */
    const val HELLO = "hello"

    const val LEAD_MARK = "[LEAD]"

    /** The engine's words for a budget over its cap (`server/greeting/facts.py`, `money_source`). */
    private const val OVER_CAP = "Over the cap."
    private const val CALENDAR = "calendar"
    private val ONCE_A_DAY = setOf("money", "body")

    private const val MORNING_START = 5
    private const val MIDDAY_START = 11
    private const val EVENING_START = 17
    private const val NIGHT_START = 22
}
