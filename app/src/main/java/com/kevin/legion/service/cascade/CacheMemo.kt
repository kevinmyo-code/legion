package com.kevin.legion.service.cascade

import android.content.Context

/**
 * The context cache's name, kept across app restarts (voice-fast step 2, 2026-11-15): an app update
 * or a killed process used to forget a cache that was still alive (and billed for storage) and pay
 * for a second one. App-private; a cache name is usable only with the key that created it.
 */
internal class PrefsCacheMemo(context: Context) : ContextCacheStore.Memo {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): ContextCacheStore.Entry? {
        val key = prefs.getString(KEY, null) ?: return null
        val name = prefs.getString(NAME, null) ?: return null
        return ContextCacheStore.Entry(key, name, prefs.getLong(EXPIRES, 0L))
    }

    override fun save(entry: ContextCacheStore.Entry?) {
        val e = prefs.edit()
        if (entry == null) {
            e.remove(KEY).remove(NAME).remove(EXPIRES)
        } else {
            e.putString(KEY, entry.key).putString(NAME, entry.name).putLong(EXPIRES, entry.expiresAtMs)
        }
        e.apply()
    }

    private companion object {
        const val PREFS = "cascade_cache"
        const val KEY = "key"
        const val NAME = "name"
        const val EXPIRES = "expires_at"
    }
}
