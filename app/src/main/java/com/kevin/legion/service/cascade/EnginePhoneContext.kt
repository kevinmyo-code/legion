package com.kevin.legion.service.cascade

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * What the engine cannot know about this conversation unless the phone says so (Contract v2
 * `client_facts`): the turns and lines the PHONE answered since the engine's last turn, the last line
 * it spoke, whether that line read outside text, his language, the clock's offset, and how much of a
 * cut-off engine reply he heard. Sent on the engine's next turn, then cleared.
 *
 * Read-through answers are never sent in clear: a tainted phone turn goes with its answer blanked
 * (the engine blanks it too), and a read-through line is never a `prior_line`; it sets
 * `carry_tainted` instead.
 */
internal class EnginePhoneContext(private val clock: () -> Long = System::currentTimeMillis) {
    private val turns = ArrayDeque<JSONObject>()
    private var priorLine: String? = null
    private var carryTainted = false
    private var heardUntil: JSONObject? = null
    private var engineTurnRecorded = false
    private var keepPrivate = false
    private var privateContinues = false

    /**
     * The phone is in a mail exchange (review round 3, R3): its last turn was kept for mail, or was a
     * follow-up that still holds mail state (a draft or a send waiting for his yes). His next words
     * stay on the phone ([EngineBrainRules.phoneAwaits]) and reach the engine only as the placeholder.
     */
    @Volatile var mailExchange = false
        private set

    /** The engine turn whose reply is (or was last) playing, for `heard_until`; null after a phone turn. */
    @Volatile var lastEngineTurn: String? = null

    /** His conversation language from the phone's whole-turn switch (a name, e.g. "Spanish"). */
    @Volatile var language: String? = null

    /** The engine is answering a turn: the facts were sent with it; its own record is not the phone's. */
    @Synchronized
    fun engineAnswering() {
        turns.clear()
        priorLine = null
        carryTainted = false
        heardUntil = null
        engineTurnRecorded = true
        mailExchange = false
    }

    /**
     * A new turn is being routed: the last turn's own record is in (review R5: a cut-off engine turn
     * that was never recorded must not make the next phone turn look like the engine's).
     */
    @Synchronized
    fun newTurn() {
        engineTurnRecorded = false
        keepPrivate = false
    }

    /**
     * The phone keeps this turn for a reason that keeps his words off the engine (mail, review R3):
     * it goes as a blanked record, so the engine knows a turn happened and nothing of it. [continues]:
     * the mail exchange goes on after it (mail words, or phone state still waiting on his yes); a
     * follow-up that leaves no state ends it, so the exchange never chains on its own.
     */
    @Synchronized
    fun keptPrivately(continues: Boolean) {
        keepPrivate = true
        privateContinues = continues
    }

    /** The greeting the phone spoke: the engine's `prior_line`, never phone state his reply answers. */
    @Synchronized
    fun greeted(line: String) {
        if (line.isNotBlank()) priorLine = line.take(MAX_PRIOR_CHARS)
    }

    /** [chars] of the engine's last reply were heard before he cut in (a barge-in, a tap). */
    @Synchronized
    fun cutOff(chars: Int) {
        val turn = lastEngineTurn ?: return
        heardUntil = JSONObject().put("turn_id", turn).put("chars", chars.coerceAtLeast(0))
        lastEngineTurn = null
    }

    /**
     * One exchange the conversation recorded ([said] his words, or blank for a line the phone spoke
     * on its own, [answer] what was said). The engine's own turn is skipped once.
     */
    @Synchronized
    fun recorded(said: String, answer: String, readThrough: Boolean, fromUser: Boolean) {
        if (engineTurnRecorded) {
            engineTurnRecorded = false
            return
        }
        val private = keepPrivate
        keepPrivate = false
        if (fromUser) mailExchange = private && privateContinues
        if (answer.isNotBlank()) {
            priorLine = if (readThrough || private) null else answer.take(MAX_PRIOR_CHARS)
            carryTainted = readThrough || private
        }
        if (fromUser && said.isNotBlank()) {
            turns.addLast(
                JSONObject()
                    .put("said", if (private) KEPT_PRIVATE else said.take(MAX_SAID_CHARS))
                    .put("answer", if (readThrough || private) "" else answer.take(MAX_ANSWER_CHARS))
                    .put("tainted", readThrough || private),
            )
            while (turns.size > MAX_TURNS || size() > MAX_TOTAL_CHARS) turns.removeFirst()
        }
    }

    /** The `client_facts` object for the engine's next turn. */
    @Synchronized
    fun facts(): JSONObject {
        val offsetMinutes = TimeZone.getDefault().getOffset(clock()) / MS_PER_MINUTE
        val out = JSONObject().put("utc_offset_minutes", offsetMinutes)
        language?.let { out.put("language", it.take(MAX_LANGUAGE_CHARS)) }
        if (carryTainted) out.put("carry_tainted", true)
        priorLine?.let { out.put("prior_line", it) }
        heardUntil?.let { out.put("heard_until", it) }
        if (turns.isNotEmpty()) out.put("phone_turns", JSONArray(turns.toList()))
        return out
    }

    private fun size(): Int = turns.sumOf { it.optString("said").length + it.optString("answer").length }

    companion object {
        /** The engine's `client_facts` limits (`agents/legion/client_facts.py`). */
        const val MAX_TURNS = 6
        const val MAX_SAID_CHARS = 500
        const val MAX_ANSWER_CHARS = 1_000
        const val MAX_TOTAL_CHARS = 4_000
        const val MAX_PRIOR_CHARS = 300
        const val MAX_LANGUAGE_CHARS = 32

        /** What the engine gets for a turn the phone kept privately (the engine's `said` may not be blank). */
        const val KEPT_PRIVATE = "[a turn the device answered itself; not shared]"
        private const val MS_PER_MINUTE = 60_000
    }
}
