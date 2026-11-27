package com.kevin.legion.service.conversationlog

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Saves finished chat turns through a [TurnOutbox] and keeps [status]: the state of that save in
 * plain words, read by the Settings screen (memory chain ticket 450, CLAUDE.md section 7: with the
 * engine down the phone queues the write and says so; never only a log line).
 *
 * Nothing here swallows a failure. A turn that could not be written down, and a refused turn, are
 * counted in [lostTurns] and reported in [status] until the process ends; a failure is also passed
 * to [log]. The disk write and the send run off the caller's thread ([writer], [scope]).
 */
class TurnSaver(
    private val box: TurnOutbox,
    private val writer: Executor,
    private val scope: CoroutineScope,
    private val keep: () -> Boolean,
    private val log: (String, Throwable?) -> Unit,
) {
    private val lost = AtomicInteger(0)
    private val _status = MutableStateFlow<String?>(null)

    /** The save in words, or null before anything was queued this run. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Turns that could not be saved this run: refused by the engine, or not writable on this phone. */
    val lostTurns: Int get() = lost.get()

    @Volatile private var lastOutcome: String? = null

    /** Queues [turn] and tries to send; does nothing while the log is switched off. */
    @Suppress("TooGenericExceptionCaught") // a failed save must neither crash the app nor pass unseen
    fun save(turn: SavedTurn) {
        if (!keep()) return
        writer.execute {
            try {
                box.enqueue(turn)
            } catch (e: Exception) {
                lost.incrementAndGet()
                log("a chat turn could not be written on this phone", e)
                publish()
                return@execute
            }
            scope.launch { drain() }
        }
    }

    /** Sends what is queued and records the outcome. */
    @Suppress("TooGenericExceptionCaught") // as above
    suspend fun drain() {
        val outcome = try {
            box.drain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("the chat turn queue could not be sent", e)
            lastOutcome = "Chat turns could not be sent: ${e.javaClass.simpleName}. They are kept on this phone."
            publish()
            return
        }
        if (outcome is TurnOutbox.Outcome.NothingToSend) return
        if (outcome is TurnOutbox.Outcome.Sent) {
            lost.addAndGet(outcome.refused)
            lastOutcome = if (outcome.turns > 0) "Saved ${TurnOutbox.turnsWord(outcome.turns)}." else null
        } else {
            lastOutcome = outcome.sentence
        }
        log(outcome.sentence, null)
        publish()
    }

    /** The log was switched off: nothing queued may be sent later. */
    fun discardQueued() {
        box.clear()
        lastOutcome = null
        publish()
    }

    private fun publish() {
        val gone = lost.get()
        val lostLine = if (gone > 0) {
            "${TurnOutbox.turnsWord(gone).replaceFirstChar { it.uppercase() }} could not be saved this run."
        } else {
            null
        }
        _status.value = listOfNotNull(lastOutcome, lostLine).joinToString(" ").ifBlank { null }
    }
}
