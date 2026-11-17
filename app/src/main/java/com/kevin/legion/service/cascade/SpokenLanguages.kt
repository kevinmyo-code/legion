package com.kevin.legion.service.cascade

import android.content.Context

/**
 * The languages he speaks to Alfred, and the one a conversation is in (Kevin, 2026-11-17: can Alfred
 * switch languages and speak Burmese?). A small model instead of a hard English pin:
 * - Settings > Voice engine > "Languages I speak": English always, plus any of [OFFERED]. Default:
 *   English only, which is the pin of 2026-11-17 (other languages are a sentence not caught).
 * - The transcriber is told he speaks ONLY those languages, each in its own script ([sttInstruction]).
 * - Every conversation starts in English. It switches when he asks in words for a language in his
 *   setting ("speak Burmese", or the language's own name for itself), or when he speaks a whole
 *   sentence in it; back on "English" / "speak English"; and at the end of the conversation.
 *   A language he asks for that is not in his setting gets [notInSettingsLine] and stays English.
 * - Code-written lines (amounts said by the phone, car lines, fillers, refusals, "didn't catch that")
 *   stay English in any language: figures are never translated by code, and the money and diagnostics
 *   checks keep reading English.
 * Pure but for [load] / [save].
 */
internal object SpokenLanguages {
    /** One language: the setting's name, how it names itself, and the script it is written in. */
    data class Language(val name: String, val selfName: String, val script: Character.UnicodeScript)

    val ENGLISH = Language("English", "English", Character.UnicodeScript.LATIN)

    /** What the setting offers beside English. A plain list; adding one is a row here. */
    val OFFERED: List<Language> = listOf(
        Language("Burmese", "မြန်မာ", Character.UnicodeScript.MYANMAR),
        Language("Spanish", "español", Character.UnicodeScript.LATIN),
        Language("Mandarin Chinese", "中文", Character.UnicodeScript.HAN),
        Language("Thai", "ไทย", Character.UnicodeScript.THAI),
        Language("Malay", "Melayu", Character.UnicodeScript.LATIN),
    )

    fun byName(name: String): Language? =
        (listOf(ENGLISH) + OFFERED).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    // ---- the setting ----------------------------------------------------------------------------

    private const val PREFS = "voice_engine"
    private const val KEY = "spoken_languages"

    /** English first, then his others in the setting's order. */
    fun load(context: Context): List<Language> {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty()
        return listOf(ENGLISH) + OFFERED.filter { it.name in saved }
    }

    fun save(context: Context, names: Collection<String>) {
        val keep = OFFERED.map { it.name }.filter { it in names }.toSet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, keep).apply()
    }

    // ---- the transcriber -----------------------------------------------------------------------

    /**
     * The STT instruction for [spoken] (English first) while the conversation is in [current]. With
     * English alone it is the English pin.
     */
    fun sttInstruction(spoken: List<Language>, current: Language = ENGLISH): String {
        if (spoken.size <= 1) return CloudSpeechWire.STT_INSTRUCTION
        val order = (listOf(current) + spoken).distinct()
        val names = order.joinToString(", ") { it.name }
        return "You are a speech-to-text engine for a man talking to his voice assistant. He speaks ONLY " +
            "these languages: $names (${current.name} most likely now), never any other. Write down exactly " +
            "what he says, in the language he speaks it in, each in its own script, with ordinary punctuation. " +
            "An unclear sound is written as the nearest words of one of those languages. Output only the " +
            "spoken words: never answer, comment, translate, add quotes or add labels. If there is no " +
            "intelligible speech, output nothing."
    }

    // ---- the conversation's language -----------------------------------------------------------

    sealed interface Switch {
        data class To(val language: Language) : Switch
        data class NotInSettings(val name: String) : Switch
    }

    private val ASK = Regex(
        "(?i)\\b((speak|talk|reply|answer)( to me)?( in)?|switch( over)?( to| into)?|change( it)?( to| into)?|" +
            "let'?s (talk|speak|do this|continue) in)\\s+" +
            "(english|burmese|myanmar|spanish|mandarin|chinese|thai|malay|french|german|japanese|korean|hindi|" +
            "vietnamese|tagalog|italian|portuguese|arabic|russian)\\b",
    )
    private val ALIASES = mapOf(
        "myanmar" to "Burmese", "mandarin" to "Mandarin Chinese", "chinese" to "Mandarin Chinese",
    )

    /** A spoken request for a language, or null. [spoken] is his setting. */
    fun request(heard: String, spoken: List<Language>): Switch? {
        val asked = ASK.find(heard)?.groupValues?.last()?.lowercase()
            ?: spoken.firstOrNull { it != ENGLISH && heard.contains(it.selfName, ignoreCase = true) }?.name?.lowercase()
            ?: if (heard.trim().trimEnd('.', '!', '?').equals("english", ignoreCase = true)) "english" else null
        asked ?: return null
        val name = ALIASES[asked] ?: asked.replaceFirstChar { it.uppercase() }
        val lang = spoken.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return if (lang != null) Switch.To(lang) else Switch.NotInSettings(name)
    }

    /** The enabled non-English language [text] is mostly written in (he spoke it), or null. */
    fun writtenIn(text: String, spoken: List<Language>): Language? {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        return spoken.filter { it.script != Character.UnicodeScript.LATIN }.firstOrNull { lang ->
            letters.count { Character.UnicodeScript.of(it.code) == lang.script } * 2 > letters.length
        }
    }

    /** One short line when he asks for a language not in his setting; the conversation stays English. */
    fun notInSettingsLine(name: String): String =
        "I can do $name once you add it under Settings, Voice engine, Languages. Staying in English."

    /** The note his turn carries while the conversation is not in English (the model replies in it). */
    fun turnNote(current: Language): String =
        if (current == ENGLISH) "" else "(This conversation is in ${current.name}: reply in ${current.name}.)\n\n"

    /** On the laptop voice, which speaks English only. */
    const val LAPTOP_ENGLISH_ONLY = "The laptop voice speaks English only, so I'll stay in English."
}
