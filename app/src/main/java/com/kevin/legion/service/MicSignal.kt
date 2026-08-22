package com.kevin.legion.service

/**
 * Pure signal-level math pulled out of [GeminiLiveSession]'s mic loop so it is unit-testable
 * with no `Context`, no `AudioRecord`, nothing Android at all - see ticket 15
 * (`.scratch/wake-word/issues/15-see-a-deaf-mic.md`). Both functions here take exactly the
 * inputs the mic loop already has in hand (the raw PCM16 buffer, or the elapsed/peak numbers
 * derived from it) and nothing else.
 *
 * **Levels only, never content.** Everything below reduces a buffer of audio to a single
 * loudness number. Nothing here returns, logs, or stores a sample; that distinction is the
 * whole reason a level is safe to log at all where the raw audio never could be.
 */
object MicSignal {

    /**
     * Peak absolute amplitude of the 16-bit little-endian PCM samples in `buffer[0 until
     * length)`. O(length), zero allocation - this is called on every `AudioRecord.read()` in a
     * realtime loop, not once a second (the once-a-second cadence the ticket asks for is how
     * often a CALLER logs/resets its accumulator, not how often this function itself may run;
     * see [GeminiLiveSession]'s `LEVEL_LOG_INTERVAL_MS` for that throttle).
     *
     * [length] is expected to be even (whole 16-bit samples). A trailing odd byte, if one ever
     * arrived, is silently ignored rather than thrown on - `AudioRecord.read()` always returns
     * byte counts in whole frames for mono PCM16 in this codebase's own capture path, so this
     * is defensive rather than an expected case.
     */
    fun peakAmplitude(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            // Little-endian PCM16: low byte first. Reassembling as Short before widening to Int
            // is what makes the sign bit land correctly - a raw (hi shl 8) or lo as an Int would
            // never be negative, and half the waveform would read as twice as loud as it is.
            val sample = (((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF))
                .toShort()
                .toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    /**
     * Below this peak amplitude (out of a possible 32767 for 16-bit PCM), a signal is treated
     * as digital silence rather than a quiet room. **GUESSED, not measured** - the ticket's
     * field evidence (`.scratch/wake-word/issues/15-see-a-deaf-mic.md`) never captured a raw
     * level from the actual failure, only byte counts, because this instrumentation did not
     * exist yet. Chosen to sit comfortably above 0 (a genuinely dead capture reads exactly or
     * almost exactly zero) and comfortably below the noise floor a phone mic with hardware echo
     * cancellation/suppression still passes through in a quiet room. Revisit once a real capture
     * from this watchdog exists to measure against - that is precisely the gap this ticket's
     * logging closes for next time.
     */
    const val SILENCE_PEAK_THRESHOLD = 50

    /**
     * How long the mic must have been open before a near-zero peak is treated as a fault rather
     * than an unremarkable quiet instant mid-turn. Deliberately well past the existing
     * short-capture retry window (1200ms in [GeminiLiveSession]) - that path already recovers
     * from a brief false end-of-turn, and this watchdog exists for the ticket's actual failure
     * shape instead: a turn that never ends AT ALL because server VAD never hears speech begin
     * in the first place. **GUESSED, same caveat as [SILENCE_PEAK_THRESHOLD].**
     */
    const val DEAF_MIC_ELAPSED_MS = 5_000L

    /**
     * The watchdog's whole decision, extracted to a pure function so the trap the ticket names
     * cannot quietly get reintroduced by someone who only reads the effect. **The trap: "no
     * bytes forwarded" is the wrong trigger.** A user who triggers the wake word and then says
     * nothing produces exactly that signature - zero bytes forwarded, mic open a long time - and
     * is completely indistinguishable from a broken mic BY BYTE COUNT ALONE. Firing on that would
     * nag the user for being quiet: wrong diagnostically, and a compulsion-shaped behaviour
     * besides (CLAUDE.md §7).
     *
     * The honest discriminator is the LEVEL. A working mic sitting in a silent room still
     * returns room noise - some small non-zero peak, from the room itself or the hardware's own
     * noise floor. A genuinely deaf capture (dead HAL path, wrong routing, whatever the actual
     * fault turns out to be) returns digital silence: a peak at or pathologically near zero, for
     * as long as it stays open. This function fires only when BOTH hold: the window has been
     * open past [elapsedThresholdMs], AND the peak over that WHOLE window never rose above
     * [peakThreshold]. An intentionally silent turn from a working mic still clears the peak
     * threshold on room noise alone and never trips this.
     *
     * [peakSinceOpen] must be the running peak across the ENTIRE window the mic has been open
     * this capture, not a single buffer's peak - a momentarily quiet buffer between two words is
     * ordinary speech, and only a peak that never rises for the whole window is a fault.
     */
    fun isDeafMic(
        elapsedMs: Long,
        peakSinceOpen: Int,
        elapsedThresholdMs: Long = DEAF_MIC_ELAPSED_MS,
        peakThreshold: Int = SILENCE_PEAK_THRESHOLD,
    ): Boolean = elapsedMs >= elapsedThresholdMs && peakSinceOpen <= peakThreshold
}
