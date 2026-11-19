package com.kevin.legion.service.cascade

/**
 * Writes that went through and that he has not heard said yet (2026-11-19: a write was done and
 * never said). A turn's code line ([ReplySpeaker.line]) pays its writes only when it played to the
 * end; whatever a turn leaves unpaid (a stop, a barge-in, a line cut off, a voice that failed on
 * it) is owed here and said at the start of the next thing said ([open]); a close that cannot say
 * it writes it down instead ([writeDown]: the record and the panel). One per conversation.
 *
 * What is owed: a write that went through and was not heard, and, after a turn that was cut off, a
 * write whose result never came back ("I couldn't confirm ..."). Never a failure or a refusal (a
 * held lone word, "which car?"): nothing changed, and saying "didn't go through" for those would
 * be wrong (review F4, F9).
 *
 * Facts are matched by identity: two identical writes are two debts. A fact once heard is never
 * owed again, whichever line paid it.
 */
internal class OwedWrites {
    private val facts = mutableListOf<ScriptedLines.WriteFact>()
    private val heard = mutableListOf<ScriptedLines.WriteFact>()

    /** The line to say for everything owed now, and the facts it pays once it has played. */
    data class Due(val line: String, val facts: List<ScriptedLines.WriteFact>)

    /** At a turn's end: [turn]'s writes not heard are owed; on a cut-off turn its unconfirmed ones too. */
    @Synchronized
    fun owe(turn: List<ScriptedLines.WriteFact>, played: List<ScriptedLines.WriteFact>, cutOff: Boolean) {
        for (f in turn) {
            if (played.any { it === f } || heard.any { it === f } || facts.any { it === f }) continue
            if (f.said || (cutOff && f.unconfirmed)) facts += f
        }
    }

    @Synchronized
    fun due(): Due? = ScriptedLines.doneLine(facts)?.let { Due(it, facts.toList()) }

    /** [paid]'s line played to the end: those are heard. */
    @Synchronized
    fun paid(paid: List<ScriptedLines.WriteFact>) {
        facts.removeAll { f -> paid.any { it === f } }
        heard += paid
    }

    @Synchronized
    fun isEmpty(): Boolean = due() == null

    /** [turn]'s end ([owe]): what played is heard, the rest is owed. */
    fun endTurn(turn: TurnWrites, cutOff: Boolean) {
        val (f, p) = turn.snapshot()
        owe(f, p, cutOff)
    }

    /** A new reply: what is owed is said first, and paid only once its line played to the end. */
    fun open(speaker: ReplySpeaker) {
        due()?.let { d -> speaker.line(d.line) { paid(d.facts) } }
    }

    /** The loop's code line for [facts] in [turn]'s reply; heard only once it played whole. */
    fun line(turn: TurnWrites, speaker: ReplySpeaker, text: String, facts: List<ScriptedLines.WriteFact>) {
        speaker.line(text) { turn.played(facts) }
    }

    /**
     * Nothing more will be said (a close, a hand-over, a lost voice): what is owed goes to the
     * record and the panel instead, once. Null when nothing is owed.
     */
    @Synchronized
    fun writeDown(): String? {
        val d = due() ?: return null
        paid(d.facts)
        return d.line
    }
}

/** One turn's writes: every fact as its result came in, and the ones whose line played whole. */
internal class TurnWrites {
    private val facts = mutableListOf<ScriptedLines.WriteFact>()
    private val played = mutableListOf<ScriptedLines.WriteFact>()

    @Synchronized
    fun add(fact: ScriptedLines.WriteFact) {
        facts += fact
    }

    @Synchronized
    fun played(paid: List<ScriptedLines.WriteFact>) {
        played += paid
    }

    @Synchronized
    fun snapshot(): Pair<List<ScriptedLines.WriteFact>, List<ScriptedLines.WriteFact>> = facts.toList() to played.toList()
}
