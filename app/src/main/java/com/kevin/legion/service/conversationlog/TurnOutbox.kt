package com.kevin.legion.service.conversationlog

import com.kevin.legion.backend.engine.EngineHttp
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One tool a turn ran: its name and whether it worked. Never its result (CLAUDE.md sec 7). */
data class SavedToolCall(val name: String, val ok: Boolean)

/**
 * One finished conversation turn, as the engine's `conversation_turns` row holds it (memory chain
 * ticket 450): who spoke is the signed-in member, [chatId] is the Live session or typed chat, [seq]
 * is the turn's place in it. A turn is his own words and the tool NAMES, nothing else: the assistant's
 * reply and tool results can carry text other people wrote, so they are never collected, queued on
 * disk or sent.
 */
data class SavedTurn(
    val chatId: String,
    val seq: Int,
    val surface: String,
    val userText: String?,
    val toolCalls: List<SavedToolCall>,
    val spokenAtMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put("chat_id", chatId)
        .put("seq", seq)
        .put("surface", surface)
        .put("user_text", userText ?: JSONObject.NULL)
        .put("tool_calls", JSONArray(toolCalls.map { JSONObject().put("name", it.name).put("ok", it.ok) }))
        .put("spoken_at", Instant.ofEpochMilli(spokenAtMs).toString())
        .toString()
}

/** The one call the outbox makes. Production is [EngineTurnTransport]. */
interface TurnTransport {
    /** True when the phone has an engine address and a device token. */
    fun isUsable(): Boolean

    /**
     * Who the phone is signed in as right now, engine address included, or null when signed out. A
     * queued turn carries it from when it was spoken and is sent only while it still matches: a turn
     * is a member's private record and must never be posted under another member's token.
     */
    fun identity(): String?

    /** `POST /api/member-memories/turns` with [json] as the body. */
    suspend fun post(json: String): PostResult
}

class EngineTurnTransport(
    private val http: EngineHttp,
    private val identity: () -> String?,
) : TurnTransport {
    override fun isUsable(): Boolean = http.isUsable()

    override fun identity(): String? = identity.invoke()

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
 *
 * **Each queued line carries who spoke it** (`owner`, from [TurnTransport.identity]) and is posted
 * only while the phone is still signed in as that identity. After a sign-in as someone else the
 * earlier member's turns are held, not sent and not deleted ([heldForOthers]); they drain if that
 * member signs back in. A line with no owner (an older queue format) cannot be attributed, so it is
 * dropped and counted ([takeRefused]).
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
    private val refusedDropped = AtomicInteger(0)

    /**
     * How many turns the engine refused (and the queue dropped) since the last call, whatever stopped
     * the drain afterwards: a refusal followed by an offline stop must still be counted.
     */
    fun takeRefused(): Int = refusedDropped.getAndSet(0)

    /** Throws away everything queued (the log was switched off: nothing may be sent later). */
    fun clear() {
        synchronized(lock) { if (file.exists()) write(emptyList()) }
    }

    /** Thrown by [enqueue] when nobody is signed in: the turn has no owner to be sent as. */
    class NotSignedIn : IllegalStateException("not signed in to an engine")

    /**
     * Appends [turn] under the current identity; trims the oldest past [max]. Returns how many turns
     * the trim threw away (0 when none), so the caller can say so. Safe from any thread.
     */
    fun enqueue(turn: SavedTurn): Int = synchronized(lock) {
        val owner = transport.identity() ?: throw NotSignedIn()
        val line = JSONObject().put("owner", owner).put("turn", JSONObject(turn.toJson())).toString()
        val lines = read() + line
        val cut = (lines.size - max).coerceAtLeast(0)
        write(lines.drop(cut))
        cut
    }

    /** Every queued turn, whoever spoke it. */
    fun pending(): Int = synchronized(lock) { read().size }

    /** Queued turns of the identity signed in now. */
    fun ownPending(): Int = synchronized(lock) {
        val me = transport.identity()
        read().count { ownerOf(it) == me }
    }

    /** Queued turns spoken under another sign-in, waiting for that member to sign back in. */
    fun heldForOthers(): Int = synchronized(lock) {
        val me = transport.identity()
        if (me == null) 0 else read().count { line -> ownerOf(line).let { it != null && it != me } }
    }

    private fun ownerOf(line: String): String? =
        runCatching { JSONObject(line).optString("owner", "").ifEmpty { null } }.getOrNull()

    private fun bodyOf(line: String): String = JSONObject(line).getJSONObject("turn").toString()

    /** Drops lines with no owner (an older format): they cannot be attributed. Counted as refused. */
    private fun dropUnowned() = synchronized(lock) {
        val lines = read()
        val kept = lines.filter { ownerOf(it) != null }
        if (kept.size != lines.size) {
            write(kept)
            refusedDropped.addAndGet(lines.size - kept.size)
        }
    }

    /**
     * Sends queued turns oldest first until one fails. Single-flight: a second caller waits, then
     * finds the queue empty.
     */
    suspend fun drain(canSend: () -> Boolean = { true }): Outcome = drainLock.withLock { drainLocked(canSend) }

    private suspend fun drainLocked(canSend: () -> Boolean): Outcome {
        var sent = 0
        var refused = 0
        var stop: Outcome? = null
        dropUnowned()
        while (stop == null) {
            val me = transport.identity()
            val next = synchronized(lock) { read().firstOrNull { ownerOf(it) == me } }
            stop = when {
                !canSend() -> Outcome.NothingToSend
                next == null -> if (sent + refused == 0) Outcome.NothingToSend else Outcome.Sent(sent, refused)
                !transport.isUsable() -> Outcome.NoEngine
                else -> when (val attempt = attempt(next)) {
                    Attempt.Saved -> {
                        sent++
                        null
                    }
                    Attempt.Refused -> {
                        refused++
                        refusedDropped.incrementAndGet()
                        null
                    }
                    is Attempt.Stop -> Outcome.Kept(sent, ownPending(), attempt.reason)
                }
            }
        }
        return stop
    }

    private sealed interface Attempt {
        data object Saved : Attempt

        data object Refused : Attempt

        data class Stop(val reason: String) : Attempt
    }

    /** Posts the oldest queued [line]; a saved or dropped turn leaves the queue, a kept one stops the drain. */
    private suspend fun attempt(line: String): Attempt {
        val result = transport.post(bodyOf(line))
        val kind = RetryRules.classify(result)
        return when {
            kind == null -> {
                remove(line)
                Attempt.Saved
            }
            isMissingRoute(result, kind) -> Attempt.Stop(OLD_ENGINE_REASON)
            kind in DROPPED -> {
                remove(line)
                Attempt.Refused
            }
            else -> Attempt.Stop(RetryRules.sentence(kind, result))
        }
    }

    private fun remove(line: String) = synchronized(lock) {
        val lines = read()
        val at = lines.indexOf(line)
        if (at >= 0) write(lines.filterIndexed { i, _ -> i != at })
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
