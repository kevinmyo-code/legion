package com.kevin.legion.watches

/**
 * Every sentence watches say, once, for the voice tool, the Watches screen and the raise alike, so
 * they never disagree about what happened (the [com.kevin.legion.memory.MemoryWording] posture).
 * Each failure says what did NOT happen (CLAUDE.md section 7's outcome-verb rule): nothing claims
 * a watch is set unless the engine answered with the row it wrote.
 */
object WatchWording {

    /** How many facts one spoken line carries; the rest are counted and left on the screen. */
    const val SPOKEN_FACTS = 3

    const val READ_THROUGH_REFUSAL =
        "No watch was set or changed: this turn read mail or a web page, and nothing other people wrote " +
            "may become a watch. Ask the user to say it again in their own words."

    fun amountNeeded(kind: String): String =
        "No watch was set: $kind needs an amount in dollars, like 250."

    fun unreachable(sentence: String, verb: String): String =
        "Nothing was $verb: the engine could not be reached, and watches live there. $sentence".trim()

    fun failed(outcome: WatchOutcome<*>, verb: String): String = when (outcome) {
        is WatchOutcome.Unreachable -> unreachable(outcome.sentence, verb)
        is WatchOutcome.Refused -> outcome.sentence.ifBlank { "Nothing was $verb: the engine refused." }
        is WatchOutcome.Unconfirmed ->
            "It is not known whether anything was $verb: ${outcome.sentence} List watches to check."
        is WatchOutcome.Ok -> ""
    }

    fun set(made: WatchSet): String {
        val shot = if (made.watch.repeat) "" else " It fires once, then stops."
        val note = made.note?.let { " $it" }.orEmpty()
        val where = if (made.watch.evaluatedOn == "phone") {
            " This phone checks it; it works while the assistant is on or LEGION is open."
        } else {
            ""
        }
        return "Watch set on the engine: ${made.watch.label}.$shot$where$note"
    }

    fun paused(watch: Watch): String =
        if (watch.paused) "Paused: ${watch.label}. Nothing it finds is said until it is resumed."
        else "Resumed: ${watch.label}. Only what happens from now on fires."

    fun removed(label: String?): String =
        "Stopped watching${label?.let { ": $it" } ?: ""}. It will not fire again."

    fun listed(watches: List<Watch>): String = when {
        watches.isEmpty() -> "No watches are set."
        else -> "${watches.size} watches. Each id can be passed to pause, resume or remove."
    }

    /**
     * The proactive prompt for the facts one poll found (ticket 10: the prompt states its facts and
     * forbids every other subject). Several firings are ONE line, never one each.
     */
    fun prompt(facts: String, more: Int): String {
        val rest = if (more > 0) " There are $more more on the Watches screen; say that number only." else ""
        return "Something the user asked to be told about has happened. The facts, and the only things you " +
            "may say: $facts$rest Tell the user in one or two short sentences, in your own words, as one " +
            "line. Do not guess at causes, add advice, or mention any other subject."
    }

    /** What the raise row stores and "why did you say that?" reads: the watch and the fact. */
    fun reason(firings: List<WatchFiring>): String =
        firings.joinToString(" ") { "Watch \"${it.watchLabel}\" found: ${it.facts}" }

    fun facts(firings: List<WatchFiring>): String = firings.joinToString(" ") { it.facts }
}
