package com.kevin.legion.service.cascade

/**
 * Writes that went through and that he has not heard said yet (2026-11-19: a write was done and
 * never said). A turn's code line ([ReplySpeaker.line]) pays its writes only when it played to the
 * end; whatever a turn leaves unpaid (a stop, a barge-in, a line cut off) is owed here, and the
 * session says it at the start of the next thing it says, or before a close. One per conversation.
 *
 * Facts are matched by identity: two identical writes are two debts.
 */
internal class OwedWrites {
    private val facts = mutableListOf<ScriptedLines.WriteFact>()

    /** The line to say for everything owed now, and the facts it pays once it has played. */
    data class Due(val line: String, val facts: List<ScriptedLines.WriteFact>)

    /**
     * At a turn's end: its writes that went through and whose line did not play are owed. A turn
     * that was cut off ([cutOff]) also owes its failed writes: the model never got to say them.
     */
    @Synchronized
    fun owe(turn: List<ScriptedLines.WriteFact>, played: List<ScriptedLines.WriteFact>, cutOff: Boolean) {
        for (f in turn) {
            if (played.any { it === f } || facts.any { it === f }) continue
            if (f.said || (cutOff && !f.ok)) facts += f
        }
    }

    @Synchronized
    fun due(): Due? = ScriptedLines.doneLine(facts)?.let { Due(it, facts.toList()) }

    /** [paid]'s line played to the end: those are heard. */
    @Synchronized
    fun paid(paid: List<ScriptedLines.WriteFact>) {
        facts.removeAll { f -> paid.any { it === f } }
    }

    @Synchronized
    fun isEmpty(): Boolean = due() == null
}
