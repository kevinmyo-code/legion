package com.kevin.legion.service.conversationlog

import com.kevin.legion.backend.engine.EngineHttp
import java.io.File
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tool a turn ran. [result] is one clipped line, null when the tool read third-party content.
 * [action] is the tool a folded wrapper (`look_up`, `day`) resolved to, so the engine can check a
 * mail or sitrep call by its own list and not only by the phone's flag.
 */
data class SavedToolCall(val name: String, val ok: Boolean, val result: String?, val action: String? = null)

/**
 * One finished conversation turn, as the engine's `conversation_turns` row holds it (memory chain
 * ticket 450): who spoke is the signed-in member, [chatId] is the Live session or typed chat, [seq]
 * is the turn's place in it. [assistantText] is null and [readThrough] true when the turn read mail
 * or the web (CLAUDE.md sec 7); the engine withholds such a turn again whatever this says.
 */
data class SavedTurn(
    val chatId: String,
    val seq: Int,
    val surface: String,
    val userText: String?,
    val assistantText: String?,
    val toolCalls: List<SavedToolCall>,
    val readThrough: Boolean,
    val spokenAtMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put("chat_id", chatId)
        .put("seq", seq)
        .put("surface", surface)
        .put("user_text", userText ?: JSONObject.NULL)
        .put("assistant_text", assistantText ?: JSONObject.NULL)
        .put(
            "tool_calls",
            JSONArray(
                toolCalls.map {
                    JSONObject().put("name", it.name).put("ok", it.ok).put("result", it.result ?: JSONObject.NULL)
                        .apply { it.action?.let { action -> put("action", action) } }
                },
            ),
        )
        .put("read_through", readThrough)
        .put("spoken_at", Instant.ofEpochMilli(spokenAtMs).toString())
        .toString()
}

/** The one call the outbox makes. Production is [EngineTurnTransport]. */
interface TurnTransport {
    /** True when the phone has an engine address and a device token. */
    fun isUsable(): Boolean

    /** `POST /api/member-memories/turns` with [json] as the body. */
    suspend fun post(json: String): PostResult
}

class EngineTurnTransport(private val http: EngineHttp) : TurnTransport {
    override fun isUsable(): Boolean = http.isUsable()

    override suspend fun post(json: String): PostResult = http.post(PATH, json).toPostResult()

    companion object {
        const val PATH = "/api/member-memories/turns"
    }
}

/**
 * The per-turn save queue (memory chain ticket 450, ADR 0044: a write path is a Django endpoint
 * first; with the server down the phone queues the write and says so). One JSON turn per line in
 * [file], oldest first, at most [max] (the oldest go first when it is full). Only a 2xx removes a
 * turn, so offline loses nothing. The engine is idempotent on `(chat_id, seq)`, so a turn sent twice
 * because the reply was lost is stored once.
 *
 * A turn the engine refuses (a 4xx other than sign-in) is dropped and counted: it would be refused
 * every time, and keeping it would block the turns behind it. An engine that does not have the route
 * yet (404, or an old "unknown" answer) keeps the queue instead: the turns wait for the update, and
 * the cap trims the oldest if it never comes. A 409 (another member holds that chat id and turn
 * number) drops only that turn.
 */
class TurnOutbox(
    private val file: File,
    private val transport: TurnTransport,
    private val max: Int = MAX_QUEUED,
) {
    sealed interface Outcome {
        val sentence: String

        data object NothingToSend : Outcome {
            override val sentence = "No chat turns waiting."
        }

        data object NoEngine : Outcome {
            override val sentence =
                "Chat turns not saved: this phone is not signed in to an engine. They are kept on this phone."
        }

        data class Sent(val turns: Int, val refused: Int) : Outcome {
            override val sentence = "Saved ${turnsWord(turns)}." +
                (if (refused > 0) " ${turnsWord(refused)} refused by the engine and not saved." else "")
        }

        /** The engine could not take [left] turns now; they are kept and tried again. */
        data class Kept(val sent: Int, val left: Int, val reason: String) : Outcome {
            override val sentence =
                (if (sent > 0) "Saved ${turnsWord(sent)}, then stopped. " else "") +
                    "${turnsWord(left).replaceFirstChar { it.uppercase() }} not saved yet: $reason " +
                    "They are kept on this phone and will be tried again."
        }
    }

    private val lock = Any()
    private val drainLock = Mutex()

    /** Throws away everything queued (the log was switched off: nothing may be sent later). */
    fun clear() {
        synchronized(lock) { if (file.exists()) write(emptyList()) }
    }

    /** Appends [turn]; trims the oldest past [max]. Safe from any thread. */
    fun enqueue(turn: SavedTurn) {
        synchronized(lock) {
            val lines = read() + turn.toJson()
            write(if (lines.size > max) lines.takeLast(max) else lines)
        }
    }

    fun pending(): Int = synchronized(lock) { read().size }

    /**
     * Sends queued turns oldest first until one fails. Single-flight: a second caller waits, then
     * finds the queue empty.
     */
    suspend fun drain(): Outcome = drainLock.withLock { drainLocked() }

    private suspend fun drainLocked(): Outcome {
        var sent = 0
        var refused = 0
        while (true) {
            val next = synchronized(lock) { read().firstOrNull() }
            if (next == null) {
                return if (sent + refused == 0) Outcome.NothingToSend else Outcome.Sent(sent, refused)
            }
            if (!transport.isUsable()) return Outcome.NoEngine
            val result = transport.post(next)
            val kind = RetryRules.classify(result)
            when {
                kind == null -> {
                    removeFirst(next)
                    sent++
                }
                isMissingRoute(result, kind) -> return Outcome.Kept(sent, pending(), OLD_ENGINE_REASON)
                kind in DROPPED -> {
                    removeFirst(next)
                    refused++
                }
                else -> return Outcome.Kept(sent, pending(), RetryRules.sentence(kind, result))
            }
        }
    }

    private fun removeFirst(line: String) = synchronized(lock) {
        val lines = read()
        if (lines.firstOrNull() == line) write(lines.drop(1))
    }

    private fun read(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val MAX_QUEUED = 500

        // The engine said no to this turn itself; it would say it again, and keeping it would block the rest.
        private val DROPPED = setOf(FailureKind.REFUSED, FailureKind.TOO_LARGE)

        private const val NOT_FOUND = 404
        private const val OLD_ENGINE_REASON = "the engine does not take saved turns yet and needs updating."

        /** An engine without the route answers 404 (or the old "unknown" 400): its turns are kept, not dropped. */
        private fun isMissingRoute(result: PostResult, kind: FailureKind) =
            kind == FailureKind.OLD_ENGINE || (result is PostResult.Http && result.status == NOT_FOUND)

        fun turnsWord(n: Int) = if (n == 1) "1 chat turn" else "$n chat turns"
    }
}
