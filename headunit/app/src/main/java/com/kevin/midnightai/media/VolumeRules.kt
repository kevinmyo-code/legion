package com.kevin.midnightai.media

/**
 * Limits on what a voice "set the volume to N" may do (review L4, 2026-11-20). A misheard number
 * ("fifteen" heard as "fifty", "a hundred") must not blast the cabin in one call:
 *  - never above [VOICE_MAX_PERCENT]: leaves headroom on a head-unit amp, and the hands keys and
 *    the knob still reach 100;
 *  - never more than [VOICE_MAX_RAISE_POINTS] above where it is now in one call: a second request
 *    goes further, so a real "louder, louder" still works.
 * Lowering is never limited. "Louder" by voice stops at the same ceiling (recheck 2026-11-20), and
 * a "set" while the music is muted never lands louder than the level it had before the mute.
 * The volume keys and the knob are not limited. Pure, JVM-tested.
 */
object VolumeRules {
    const val VOICE_MAX_PERCENT = 80
    const val VOICE_MAX_RAISE_POINTS = 30

    data class VoiceSet(val target: Int, val capped: Boolean)

    /**
     * [mutedAtPercent] non-null = the stream is muted and that is the level it reports (most
     * platforms keep the pre-mute index): the set lands no louder than it, and never above the
     * ceiling. A muted stream reporting 0 gets no more than one raise step from silence.
     */
    fun voiceSet(currentPercent: Int, requestedPercent: Int, mutedAtPercent: Int? = null): VoiceSet {
        val requested = requestedPercent.coerceIn(0, 100)
        if (mutedAtPercent != null) {
            val ceiling = if (mutedAtPercent > 0) minOf(mutedAtPercent, VOICE_MAX_PERCENT) else VOICE_MAX_RAISE_POINTS
            val target = minOf(requested, ceiling)
            return VoiceSet(target, capped = target < requested)
        }
        if (requested <= currentPercent) return VoiceSet(requested, capped = false)
        val target = minOf(requested, VOICE_MAX_PERCENT, currentPercent + VOICE_MAX_RAISE_POINTS)
            .coerceAtLeast(currentPercent.coerceIn(0, 100))
        return VoiceSet(target, capped = target < requested)
    }

    /** Whether one voice "louder" step (index [index] of [max]) stays at or under [VOICE_MAX_PERCENT]. */
    fun voiceUpAllowed(index: Int, max: Int): Boolean =
        max > 0 && index < max && Math.round((index + 1) * 100f / max) <= VOICE_MAX_PERCENT

    const val UP_REFUSED =
        "Not raised: by voice the volume goes no higher than $VOICE_MAX_PERCENT%. The volume keys go louder."

    /** What the tool tells the model when a request was limited, so it says so instead of claiming the level. */
    fun cappedMessage(target: Int, requested: Int): String =
        "Set to $target%, not $requested%: by voice the volume goes up at most $VOICE_MAX_RAISE_POINTS points " +
            "at a time and never above $VOICE_MAX_PERCENT%. Ask again to go higher, or use the volume keys."
}
