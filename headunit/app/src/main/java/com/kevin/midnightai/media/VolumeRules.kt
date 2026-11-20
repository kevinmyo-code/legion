package com.kevin.midnightai.media

/**
 * Limits on what a voice "set the volume to N" may do (review L4, 2026-11-20). A misheard number
 * ("fifteen" heard as "fifty", "a hundred") must not blast the cabin in one call:
 *  - never above [VOICE_MAX_PERCENT]: leaves headroom on a head-unit amp, and the hands keys and
 *    the knob still reach 100;
 *  - never more than [VOICE_MAX_RAISE_POINTS] above where it is now in one call: a second request
 *    goes further, so a real "louder, louder" still works.
 * Lowering is never limited. Pure, JVM-tested.
 */
object VolumeRules {
    const val VOICE_MAX_PERCENT = 80
    const val VOICE_MAX_RAISE_POINTS = 30

    data class VoiceSet(val target: Int, val capped: Boolean)

    fun voiceSet(currentPercent: Int, requestedPercent: Int): VoiceSet {
        val requested = requestedPercent.coerceIn(0, 100)
        if (requested <= currentPercent) return VoiceSet(requested, capped = false)
        val target = minOf(requested, VOICE_MAX_PERCENT, currentPercent + VOICE_MAX_RAISE_POINTS)
            .coerceAtLeast(currentPercent.coerceIn(0, 100))
        return VoiceSet(target, capped = target < requested)
    }

    /** What the tool tells the model when a request was limited, so it says so instead of claiming the level. */
    fun cappedMessage(target: Int, requested: Int): String =
        "Set to $target%, not $requested%: by voice the volume goes up at most $VOICE_MAX_RAISE_POINTS points " +
            "at a time and never above $VOICE_MAX_PERCENT%. Ask again to go higher, or use the volume keys."
}
