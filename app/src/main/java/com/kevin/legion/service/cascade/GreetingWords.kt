package com.kevin.legion.service.cascade

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
    fun of(written: String): String? =
        LeadingFiller.strip(TtsTags.strip(written).trim()).trim().ifEmpty { null }

    /** Placeholder: the name check is not wired yet. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun namesHim(written: String): Boolean = false
}
