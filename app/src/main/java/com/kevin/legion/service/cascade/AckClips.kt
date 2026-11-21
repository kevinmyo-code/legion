package com.kevin.legion.service.cascade

import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * The acknowledgement's clip at end of turn (2026-11-21): a word of [Backchannel.LINES] whose audio
 * is already in memory, trimmed and measured, ready to hand to the player. Out of the session so the
 * end-of-turn path can be run and timed whole in a test (`AckTimingTest`): from the end-of-turn
 * decision to the first sample handed to the player within [FillerTiming.ACK_MAX_START_MS].
 */
internal object AckClips {
    /** A clip from memory comes at once; past this it was not in memory and is dropped. */
    const val FETCH_MS = 300L

    private const val MS_PER_S = 1_000L
    private const val BYTES_PER_SAMPLE = 2

    /** [line]'s whole audio if it comes within [FETCH_MS] (it is from memory), else empty. */
    suspend fun fromMemory(tts: TextToSpeech, line: String, take: Int): ByteArray {
        val clip = tts.speakTake(SpokenText.forSpeech(line), take)
        return withTimeoutOrNull(FETCH_MS) {
            val out = ByteArrayOutputStream()
            for (frame in clip.frames) out.write(frame)
            out.toByteArray()
        } ?: ByteArray(0).also { clip.cancel() }
    }

    /** [pcm] trimmed to its voice ([Backchannel.trimSilence]) and its length in ms. */
    suspend fun trimmed(tts: TextToSpeech, line: String, take: Int): Pair<ByteArray, Long> {
        val pcm = Backchannel.trimSilence(fromMemory(tts, line, take), tts.sampleRate)
        return pcm to pcm.size.toLong() * MS_PER_S / (tts.sampleRate * BYTES_PER_SAMPLE)
    }

    /** A chosen acknowledgement and its audio. */
    class Picked(val chosen: Backchannel.Chosen, val pcm: ByteArray)

    /** The acknowledgement to play now ([Backchannel.choose]), from memory only; null when none fits. */
    suspend fun pick(tts: TextToSpeech, recent: RecentLines, index: Int): Picked? {
        var pcm = ByteArray(0)
        val inHand = { w: String, t: Int -> tts.inHandTake(SpokenText.forSpeech(w), t) }
        val chosen = Backchannel.choose(recent, index, inHand) { w, t ->
            val (audio, ms) = trimmed(tts, w, t)
            pcm = audio
            ms
        } ?: return null
        return Picked(chosen, pcm)
    }
}
