package com.kevin.legion.brief

import android.content.Context

/** The last brief this phone read, kept for when the engine cannot be reached. */
data class CachedBrief(val brief: ServerBrief, val savedAtMs: Long)

/**
 * The offline copy (ai-upgrade ticket 02, ruling 6: "The phone caches the latest brief for offline
 * use"). **It only ever receives the engine's own reply body**: the type takes a raw server body, so
 * the phone's live calendar, weather and the Gmail newsletter summary have no way in. Newsletters are
 * third-party mail and are read-through only (CLAUDE.md section 7); the server never stores them and
 * neither does this.
 */
interface BriefCache {
    fun read(): CachedBrief?

    fun write(rawServerBody: String, savedAtMs: Long)
}

/**
 * SharedPreferences, one key for the body and one for when it was saved. No Room table: one row,
 * replaced whole, never queried. A body that no longer parses (an older phone build wrote it) reads
 * as no cache rather than a crash.
 */
class PrefsBriefCache(context: Context) : BriefCache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): CachedBrief? {
        val brief = prefs.getString(KEY_BODY, null)?.let { raw -> runCatching { parseBrief(raw) }.getOrNull() }
        return brief?.let { CachedBrief(it, prefs.getLong(KEY_SAVED_AT, 0L)) }
    }

    override fun write(rawServerBody: String, savedAtMs: Long) {
        prefs.edit().putString(KEY_BODY, rawServerBody).putLong(KEY_SAVED_AT, savedAtMs).apply()
    }

    private companion object {
        const val PREFS = "daily_brief"
        const val KEY_BODY = "latest_body"
        const val KEY_SAVED_AT = "latest_saved_at_ms"
    }
}
