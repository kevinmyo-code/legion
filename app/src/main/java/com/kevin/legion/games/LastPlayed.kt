package com.kevin.legion.games

import android.content.Context

/**
 * The last game launched from LEGION and when. Local to this phone: a launcher convenience, not a
 * record (ADR 0058). [documentId] is a ROM's document id, or `android:<package>` for a native game.
 */
data class LastPlayed(val documentId: String, val atMillis: Long)

/** SharedPreferences, never Room and never synced. */
class LastPlayedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): LastPlayed? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return LastPlayed(id, prefs.getLong(KEY_AT, 0L))
    }

    fun write(documentId: String, atMillis: Long) {
        prefs.edit().putString(KEY_ID, documentId).putLong(KEY_AT, atMillis).apply()
    }

    private companion object {
        const val PREFS = "games_last_played"
        const val KEY_ID = "document_id"
        const val KEY_AT = "at_millis"
    }
}

/** Pure: "just now", "35 min ago", "2 h ago", "yesterday", "5 days ago", from two epoch millis. */
fun agoLabel(nowMillis: Long, thenMillis: Long): String {
    val minutes = ((nowMillis - thenMillis) / MINUTE_MS).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        minutes < 2 * MINUTES_PER_DAY -> "yesterday"
        else -> "${minutes / MINUTES_PER_DAY} days ago"
    }
}

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
