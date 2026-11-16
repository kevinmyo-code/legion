package com.kevin.legion.service.testturn

/**
 * One test conversation ([TestTurns]): which case is running, whether that case allows writes,
 * its microphone ([TestMic]) and the times the phone's own watchdog closes it by. Lives exactly as
 * long as its conversation, so test mode can never outlive it or reach a real conversation.
 */
class TestSession(id: String, allowWrites: Boolean, val openedAtMs: Long = System.currentTimeMillis()) {
    @Volatile var id: String = id
        private set

    @Volatile var allowWrites: Boolean = allowWrites
        private set

    @Volatile var lastCommandAtMs: Long = openedAtMs
        private set

    val mic = TestMic()

    /** The next command from the computer: its case and write rule apply from now on. */
    fun touch(id: String, allowWrites: Boolean, now: Long = System.currentTimeMillis()) {
        this.id = id
        this.allowWrites = allowWrites
        lastCommandAtMs = now
    }

    /** Why the phone should close this test conversation now, or null. */
    fun expired(now: Long = System.currentTimeMillis()): String? = when {
        now - openedAtMs > TestTurns.MAX_OPEN_MS -> "max_open"
        now - lastCommandAtMs > TestTurns.IDLE_CLOSE_MS -> "idle"
        else -> null
    }
}
