package com.kevin.legion.service.cascade

/**
 * What happens to a model-written greeting once it is held whole (or its wait ran out), just before
 * any of it is spoken. Checked in this order:
 *
 * - [HE_SPOKE]: he started talking while the line was held (up to [com.kevin.legion.greeting.GreetingBudget]'s
 *   two waits). The greeting is dropped, nothing is said over him, and his own turn carries on: on
 *   Bluetooth the mic is deaf while a clip plays, so a late hello would land on his words.
 * - [SLOW]: no text, or the line was not whole in time: the scripted hello.
 * - [NAMED]: the line calls him by a name that is not his ([com.kevin.legion.greeting.GreetingNames]):
 *   the scripted hello.
 * - [SPEAK]: say it.
 *
 * [logLine] carries no text. [scripted] says whether the scripted hello plays instead. Pure.
 *
 * A dropped greeting plays the short listen earcon unless he is mid-speech now ([earconAfterDrop],
 * re-check 2, R1): the "speech" may have been a TV, a passenger or a song's vocals, and with nothing
 * at all after the wake word he cannot tell it heard him. A real talk-over stays silent.
 */
internal enum class GreetingGate(val logLine: String?, val scripted: Boolean) {
    SPEAK(null, false),
    HE_SPOKE("greeting_dropped reason=he_spoke", false),
    SLOW("greeting model_slow scripted", true),
    NAMED("greeting_name_blocked", true),
    ;

    /** The audit line; for [HE_SPOKE] it says whether the earcon played. */
    fun logLine(earcon: Boolean): String? =
        if (this == HE_SPOKE) "$logLine earcon=${if (earcon) "yes" else "no"}" else logLine

    companion object {
        /** Speech within this long before now counts as him talking now. */
        const val MID_SPEECH_MS = 400L

        /** True when a dropped greeting should still play the earcon: no speech in the last [MID_SPEECH_MS]. */
        fun earconAfterDrop(nowMs: Long, lastSpeechAtMs: Long): Boolean = nowMs - lastSpeechAtMs > MID_SPEECH_MS

        fun decide(heSpoke: Boolean, whole: Boolean, named: Boolean): GreetingGate = when {
            heSpoke -> HE_SPOKE
            !whole -> SLOW
            named -> NAMED
            else -> SPEAK
        }
    }
}
