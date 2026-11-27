package com.kevin.legion.service.conversationlog

import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.service.LiveAudit
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One finished turn as `POST /api/member-memories/turns` takes it (`server/member_memory/turn_views.py`). */
@Serializable
internal data class TurnBody(
    val session: String,
    val turn: Int,
    val source: String = "phone",
    @SerialName("user_text") val userText: String,
    @SerialName("assistant_text") val assistantText: String,
    val tools: List<String>,
    @SerialName("spoken_at") val spokenAt: String,
)

/** What a closed turn asks the engine for: keep this turn, or forget a whole conversation. */
internal sealed interface TurnAction {
    data class Save(val body: TurnBody) : TurnAction
    data class Forget(val session: String) : TurnAction
}

/**
 * Builds each finished turn from the same events `LiveAudit` logs (issue #450): the person's final
 * words, the reply as it was spoken, and the names of the tools the turn called. One per
 * conversation source (a Live or cascade session, typed chat), fed under `LiveAudit`'s own lock.
 *
 * **CLAUDE.md section 7.** A turn that called a read-through tool, was marked read-through (a
 * look, a brief, grounding, web text, the `carry` turn after one) or would have logged a redacted
 * line is never sent at all, the person's words included, as consolidation drops it. Tool
 * arguments and results are never seen here.
 *
 * **Crisis.** Once a crisis phrase is heard in his words or the reply, nothing more of the
 * conversation is saved and the engine is asked to forget the turns already saved under it: they
 * are the same exchange (the head unit's `CrisisMemoryGate.forgetsSession`). A source with session
 * events holds that until the next session starts; typed chat, which has none, starts a fresh
 * conversation id after the forget.
 */
internal class TurnAssembler(
    private val emit: (TurnAction) -> Unit,
    private val newSession: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val isCrisis: (String) -> Boolean = CrisisDetector::detect,
) {
    private var session: String? = null
    private var sawSessionEvents = false
    private var turn = 0
    private var crisis = false
    private val user = mutableListOf<String>()
    private val assistant = StringBuilder()
    private val tools = linkedSetOf<String>()
    private var readThrough = false

    fun sessionStart() {
        closeTurn()
        sawSessionEvents = true
        session = newSession()
        turn = 0
        crisis = false
    }

    fun sessionEnd() {
        closeTurn()
        session = null
    }

    fun user(text: String, final: Boolean) {
        if (!final) return
        val cleaned = text.trim().replace(WHITESPACE, " ")
        if (cleaned.isEmpty()) return
        if (isCrisis(cleaned)) crisis = true
        user += cleaned
    }

    fun assistant(chunk: String, readThroughTurn: Boolean) {
        if (readThroughTurn) readThrough = true
        assistant.append(chunk)
    }

    fun toolCall(name: String, readThroughTool: Boolean) {
        if (readThroughTool) readThrough = true
        if (name.isNotBlank()) tools += name
    }

    fun readThrough() {
        readThrough = true
    }

    fun turnComplete() = closeTurn()

    private fun closeTurn() {
        val closed = takeTurn() ?: return
        if (isCrisis(closed.reply)) crisis = true
        val id = session ?: newSession().also { session = it }
        val index = turn++
        when {
            crisis -> forget(id)
            closed.readThrough || closed.silent -> Unit
            else -> emit(
                TurnAction.Save(
                    TurnBody(
                        session = id,
                        turn = index,
                        userText = LiveAudit.scrub(closed.user),
                        assistantText = LiveAudit.scrub(closed.reply),
                        tools = closed.tools,
                        spokenAt = Instant.ofEpochMilli(now()).toString(),
                    ),
                ),
            )
        }
    }

    /** The turn heard so far, cleared for the next one; null when nothing at all happened in it. */
    private fun takeTurn(): Closed? {
        val closed = Closed(
            user = user.joinToString(" "),
            reply = assistant.toString().trim().replace(WHITESPACE, " "),
            tools = tools.toList(),
            readThrough = readThrough,
        )
        user.clear()
        assistant.setLength(0)
        tools.clear()
        readThrough = false
        return closed.takeUnless { it.silent && it.tools.isEmpty() }
    }

    private fun forget(id: String) {
        emit(TurnAction.Forget(id))
        // Typed chat has no session events: its next message is a new conversation.
        if (!sawSessionEvents) {
            session = null
            turn = 0
            crisis = false
        }
    }

    private data class Closed(val user: String, val reply: String, val tools: List<String>, val readThrough: Boolean) {
        val silent: Boolean get() = user.isEmpty() && reply.isEmpty()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
