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
 */
internal enum class GreetingGate(val logLine: String?, val scripted: Boolean) {
    SPEAK(null, false),
    HE_SPOKE("greeting_dropped reason=he_spoke", false),
    SLOW("greeting model_slow scripted", true),
    NAMED("greeting_name_blocked", true),
    ;

    companion object {
        fun decide(heSpoke: Boolean, whole: Boolean, named: Boolean): GreetingGate = when {
            heSpoke -> HE_SPOKE
            !whole -> SLOW
            named -> NAMED
            else -> SPEAK
        }
    }
}
