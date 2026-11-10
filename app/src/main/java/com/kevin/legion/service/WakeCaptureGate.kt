package com.kevin.legion.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serialises the wake capture's native work against its teardown (crash records 2026-11-10).
 *
 * Three crashes on one morning's build: twice a SIGSEGV in the "wake-two-stage" thread right at a
 * keyword hit, at a fault address that was UTF-16 text (freed memory reused), and once
 * `startRecording() called on an uninitialized AudioRecord` from the watchdog, where the log line
 * just before showed the record it had just opened already at state 0. Both are the same race:
 * `beginListening` and `releaseSpeechService` ran at the same time on different threads (the
 * watchdog or a grammar rebuild opening a capture while a conversation's mic preemption released
 * one), with nothing between them. A release that ran mid-open closed the new Vosk recognizer, and
 * the capture went on to use it at its first confirm; or released the new AudioRecord between its
 * state check and `startRecording()`.
 *
 * The rule this class holds: every chunk of native work runs inside [whileOpen] with the ticket
 * its capture got from [open], and a teardown calls [close] before it frees anything. [close]
 * waits for a chunk already inside [whileOpen] to finish, and no chunk with an older ticket runs
 * after it. Opening and closing themselves run under [locked], so one cannot interleave the other.
 * Reentrant: a chunk that decides to open the conversation may tear its own capture down.
 */
class WakeCaptureGate {
    private val lock = ReentrantLock()

    @Volatile private var generation = 0

    /** A new capture's ticket. Any earlier ticket is closed by it. */
    fun open(): Int = lock.withLock { ++generation }

    /** Closes every ticket handed out so far; waits for a chunk already running to finish. */
    fun close() {
        lock.withLock { generation++ }
    }

    /**
     * Runs [block] for the capture holding [ticket], unless that capture was closed. False when it
     * was closed (the capture loop then stops), true when [block] ran.
     */
    fun whileOpen(ticket: Int, block: () -> Unit): Boolean = lock.withLock {
        if (ticket != generation) {
            false
        } else {
            block()
            true
        }
    }

    /** Runs an open or a teardown step so no other one, and no chunk, interleaves with it. */
    fun <T> locked(block: () -> T): T = lock.withLock(block)
}

/**
 * How long the wake word waits before retrying a capture that would not open (an AudioRecord that
 * came up uninitialised: the microphone held by another capture, or a route change mid-open). Pure,
 * so a test pins it. Doubles per failure from one watchdog tick, capped, and resets on success.
 */
object WakeOpenBackoff {
    const val MAX_SKIPPED_TICKS = 16

    /** Watchdog ticks to skip after the [failures]-th failure in a row (1-based). */
    fun ticksToSkip(failures: Int): Int {
        if (failures <= 0) return 0
        val shift = (failures - 1).coerceAtMost(MAX_SHIFT)
        return (1 shl shift).coerceAtMost(MAX_SKIPPED_TICKS)
    }

    private const val MAX_SHIFT = 4
}
