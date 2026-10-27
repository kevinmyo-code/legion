package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live mic level, published from [GeminiLiveSession]'s mic loop so a screen can show that the
 * microphone is actually hearing something (Matrix HOME's push-to-talk, Kevin 2026-10-27). A level is
 * one loudness number per read, never audio; see [MicSignal] for why that is safe to pass around.
 *
 * Nothing resets it when the mic closes: the loop simply stops publishing. A reader judges freshness
 * from [Reading.atMs], so a stopped mic reads as silence instead of a frozen bar, and the mic loop
 * needs one added line rather than one at every place it stops recording.
 */
object MicLevel {
    /** Peak PCM16 amplitude (0..32767) of one read, and when it was read. */
    data class Reading(val peak: Int, val atMs: Long)

    private val _reading = MutableStateFlow(Reading(0, 0L))
    val reading: StateFlow<Reading> = _reading.asStateFlow()

    fun publish(peak: Int, atMs: Long = System.currentTimeMillis()) {
        _reading.value = Reading(peak, atMs)
    }
}
