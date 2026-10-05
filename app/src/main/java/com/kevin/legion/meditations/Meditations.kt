package com.kevin.legion.meditations

import android.content.Context

/**
 * The bundled text, loaded once per process from `assets/meditations/meditations.txt`.
 *
 * Bundled, never fetched: CLAUDE.md "Assets are bundled in assets/ or res/, never fetched at
 * runtime". The file is ~250 KB and parses in a few milliseconds, so it is read on first use (the
 * first time Marcus consults himself) rather than at app start, where it would cost every
 * companion for the benefit of one.
 */
object Meditations {

    const val ASSET_PATH = "meditations/meditations.txt"

    /** Spoken and shown wherever a passage appears, so the translator is never anonymous. */
    const val TRANSLATION = "George Long's 1862 translation (public domain, via Project Gutenberg #15877)"

    @Volatile
    private var cached: MeditationsSearch? = null

    fun search(context: Context): MeditationsSearch =
        cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

    private fun load(context: Context): MeditationsSearch {
        val raw = context.applicationContext.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
        return MeditationsSearch(MeditationsText.parse(raw))
    }
}
