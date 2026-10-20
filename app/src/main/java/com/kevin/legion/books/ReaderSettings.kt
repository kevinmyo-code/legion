package com.kevin.legion.books

import android.content.Context

/** The three reading themes of the prototype (`.scratch/books/research/Reader.dc.html`). */
enum class ReaderTheme(val label: String) {
    DAY("Day"),
    SEPIA("Sepia"),
    NIGHT("Night"),
}

/** Reader settings. Local to the phone (ADR 0061), one set for every book. */
data class ReaderSettings(
    /** The EPUB body size in dp-ish pixels; the prototype's range. */
    val textSize: Int = DEFAULT_TEXT_SIZE,
    val theme: ReaderTheme = ReaderTheme.NIGHT,
) {
    fun biggerText() = copy(textSize = (textSize + 1).coerceAtMost(MAX_TEXT_SIZE))

    fun smallerText() = copy(textSize = (textSize - 1).coerceAtLeast(MIN_TEXT_SIZE))

    /** Readium's font size is a multiplier of its 100% body size; 17 is the prototype's 100%. */
    val epubFontScale: Double get() = textSize / DEFAULT_TEXT_SIZE.toDouble()

    companion object {
        const val MIN_TEXT_SIZE = 14
        const val MAX_TEXT_SIZE = 24
        const val DEFAULT_TEXT_SIZE = 17
    }
}

/** SharedPreferences: a handful of values, no query, nothing worth a Room table. */
class ReaderSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): ReaderSettings = ReaderSettings(
        textSize = prefs.getInt(KEY_SIZE, ReaderSettings.DEFAULT_TEXT_SIZE)
            .coerceIn(ReaderSettings.MIN_TEXT_SIZE, ReaderSettings.MAX_TEXT_SIZE),
        theme = runCatching { ReaderTheme.valueOf(prefs.getString(KEY_THEME, null).orEmpty()) }
            .getOrDefault(ReaderTheme.NIGHT),
    )

    fun write(settings: ReaderSettings) {
        prefs.edit().putInt(KEY_SIZE, settings.textSize).putString(KEY_THEME, settings.theme.name).apply()
    }

    private companion object {
        const val PREFS = "books_reader"
        const val KEY_SIZE = "text_size"
        const val KEY_THEME = "theme"
    }
}
