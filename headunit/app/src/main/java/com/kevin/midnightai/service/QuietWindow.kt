package com.kevin.midnightai.service

/**
 * Whether the idle timer in [GeminiLiveSession.armIdleTimeout] should run another window instead
 * of parking, as pure functions so the rule is a JVM test.
 *
 * 2026-10-27, Kevin: "the conversation sometimes ends randomly while im still talking". The idle
 * timer's only cancel was the first input transcript, and the transcript trails speech onset by up
 * to seconds. Speech that starts late in the window loses that race and the session parks
 * mid-sentence. So the mic loop's own level counts too: a loud buffer within [RECENT_MS] of the
 * deadline runs the window again, at most [MAX_EXTENSIONS] times so road noise above [SPEECH_PEAK]
 * cannot hold a conversation open forever.
 */
internal object QuietWindow {
    const val RECENT_MS = 1_500L
    const val MAX_EXTENSIONS = 2
    /**
     * Peak amplitude (of 32767) counted as the driver talking. GUESSED, not measured in the car.
     * The phone measured speech at only ~200-400 on its own mic (A25, 2026-10-27; room 16-31) and
     * dropped its threshold to 150. Left at 2,000 here on purpose: the head unit's mic and cabin
     * differ, and this number must be set from its own drive log (`peakSinceMicOpen`).
     */
    const val SPEECH_PEAK = 2_000

    fun shouldExtend(msSinceLocalSpeech: Long?, extensionsUsed: Int): Boolean =
        msSinceLocalSpeech != null && msSinceLocalSpeech <= RECENT_MS && extensionsUsed < MAX_EXTENSIONS

    /** Peak absolute amplitude of little-endian PCM16 in `buffer[0 until length)`. Zero allocation. */
    fun peakAmplitude(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }
}
