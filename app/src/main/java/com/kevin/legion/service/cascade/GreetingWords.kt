package com.kevin.legion.service.cascade

import com.kevin.legion.greeting.GreetingNames

/**
 * The words of a model-written hello, as the greeting's "do not reuse" list keeps them
 * ([com.kevin.legion.greeting.GreetingOpening.noteOpened]): the model's whole text once its stream
 * has ended, without voice tags ([TtsTags]) or a leading filler line ([LeadingFiller]), the two
 * things the speaker drops before anything is heard. Null when nothing is left.
 *
 * Why the text and not what was heard: the hello is noted when the model's stream ends, which on the
 * 11-19 drive was always 0.6 to 1.1 s before the clip's first audio, so what was heard was always
 * empty and the list never filled (phone audit 1119, item i). Pure.
 */
internal object GreetingWords {
    /** What the speaker would say of [written]; [of] and the name check ([namesHim]) both read it. */
    fun spoken(written: String): String? =
        LeadingFiller.strip(TtsTags.strip(written).trim()).trim().ifEmpty { null }

    /**
     * The line to keep, or null. A line that calls him by any name but [allowed] ([GreetingNames]) is
     * never kept: the session speaks the scripted hello instead, and a kept one would be quoted back.
     * A line with his own name is kept, so the next hello can be asked to leave it out.
     */
    fun of(written: String, allowed: String? = null): String? =
        spoken(written)?.takeUnless { GreetingNames.addresses(it, allowed) }

    /** True when the hello calls him by any name but [allowed]: the session speaks the scripted line. */
    fun namesHim(written: String, allowed: String? = null): Boolean =
        spoken(written)?.let { GreetingNames.addresses(it, allowed) } ?: false
}
