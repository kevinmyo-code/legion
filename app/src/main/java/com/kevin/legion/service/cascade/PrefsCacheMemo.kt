package com.kevin.legion.service.cascade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The context caches' names, kept across app restarts (voice-fast step 2, 2026-11-15): an app update
 * or a killed process used to forget a cache that was still alive (and billed for storage) and pay
 * for a second one. App-private; a cache name is usable only with the key that created it. One entry
 * per tool-set variant ([ToolGate], #453), as a JSON list in one preference.
 */
internal class PrefsCacheMemo(context: Context) : ContextCacheStore.Memo {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // an unreadable memo is no memo
    override fun load(): List<ContextCacheStore.Entry> = try {
        val all = JSONArray(prefs.getString(ENTRIES, null) ?: "[]")
        (0 until all.length()).map {
            val o = all.getJSONObject(it)
            ContextCacheStore.Entry(o.getString(KEY), o.getString(NAME), o.getLong(EXPIRES))
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun save(entries: List<ContextCacheStore.Entry>) {
        val all = JSONArray()
        entries.forEach {
            all.put(JSONObject().put(KEY, it.key).put(NAME, it.name).put(EXPIRES, it.expiresAtMs))
        }
        prefs.edit().remove(KEY).remove(NAME).remove(EXPIRES).putString(ENTRIES, all.toString()).apply()
    }

    private companion object {
        const val PREFS = "cascade_cache"
        const val ENTRIES = "entries"
        const val KEY = "key"
        const val NAME = "name"
        const val EXPIRES = "expires_at"
    }
}
