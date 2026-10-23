package com.kevin.legion.books

import android.content.Context

/** The three reading themes of the prototype (`.scratch/books/research/Reader.dc.html`). */
enum class ReaderTheme(val label: String) {
    DAY("Day"),
    SEPIA("Sepia"),
    NIGHT("Night"),
}

/** The next theme in the one-tap cycle Day -> Sepia -> Night -> Day. */
fun ReaderTheme.next(): ReaderTheme = ReaderTheme.entries[(ordinal + 1) % ReaderTheme.entries.size]

/** Reader settings. Local to the phone (ADR 0061), one set for every book. */
data class ReaderSettings(
    /** The EPUB body size in dp-ish pixels; the prototype's range. */
    val textSize: Int = DEFAULT_TEXT_SIZE,
    val theme: ReaderTheme = ReaderTheme.NIGHT,
    /** Hide the status and navigation bars while reading (swipe from an edge to peek). Default on. */
    val fullScreen: Boolean = true,
    /** The warm filter (LEGION's own "night light" overlay inside the reader); see [WarmFilter]. */
    val warmOn: Boolean = false,
    /** 0 to 100; the overlay's alpha curve is [WarmFilter.alpha]. */
    val warmStrength: Int = WarmFilter.DEFAULT_STRENGTH,
    /** On from local sunset to sunrise; with no location fix it falls back to [warmOn]. */
    val warmAuto: Boolean = false,
) {
    fun biggerText() = copy(textSize = (textSize + 1).coerceAtMost(MAX_TEXT_SIZE))

    /** The top bar's one-tap cycle: Day, Sepia, Night, Day. */
    fun nextTheme() = copy(theme = theme.next())

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
        fullScreen = prefs.getBoolean(KEY_FULL_SCREEN, true),
        warmOn = prefs.getBoolean(KEY_WARM_ON, false),
        warmStrength = prefs.getInt(KEY_WARM_STRENGTH, WarmFilter.DEFAULT_STRENGTH)
            .coerceIn(0, WarmFilter.MAX_STRENGTH),
        warmAuto = prefs.getBoolean(KEY_WARM_AUTO, false),
    )

    fun write(settings: ReaderSettings) {
        prefs.edit()
            .putInt(KEY_SIZE, settings.textSize)
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_FULL_SCREEN, settings.fullScreen)
            .putBoolean(KEY_WARM_ON, settings.warmOn)
            .putInt(KEY_WARM_STRENGTH, settings.warmStrength)
            .putBoolean(KEY_WARM_AUTO, settings.warmAuto)
            .apply()
    }

    /**
     * Whether this book's two-page spreads are split, kept per book id in the same preferences file
     * (no Room column, so no migration). Null means never chosen, which reads as Split.
     */
    fun spreadSplit(documentId: String): Boolean? =
        if (prefs.contains(spreadKey(documentId))) prefs.getBoolean(spreadKey(documentId), true) else null

    fun writeSpreadSplit(documentId: String, split: Boolean) {
        prefs.edit().putBoolean(spreadKey(documentId), split).apply()
    }

    private fun spreadKey(documentId: String) = "$KEY_SPREAD_PREFIX$documentId"

    private companion object {
        const val KEY_SPREAD_PREFIX = "spreads:"
        const val PREFS = "books_reader"
        const val KEY_SIZE = "text_size"
        const val KEY_THEME = "theme"
        const val KEY_FULL_SCREEN = "full_screen"
        const val KEY_WARM_ON = "warm_on"
        const val KEY_WARM_STRENGTH = "warm_strength"
        const val KEY_WARM_AUTO = "warm_auto"
    }
}
