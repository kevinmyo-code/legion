package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.BuildConfig

/**
 * Single source of truth for the Gemini API key at runtime.
 *
 * **Key priority:** user-entered BYO key (stored in [CompanionProfile]) >
 * [BuildConfig.GEMINI_API_KEY] (set via local.properties for dev builds;
 * blank in distributed APKs — every user brings their own key).
 *
 * Call [init] once at service/activity start so the key is cached for the
 * process lifetime. Call it again after the user saves a new key so the cache
 * is refreshed before the next session connects.
 */
object GeminiKeyProvider {
    @Volatile private var cached: String = ""

    fun init(context: Context) {
        cached = CompanionProfile.geminiKey(context)
    }

    /** The active key. BYO key takes priority; falls back to BuildConfig for dev. Blank = no key. */
    fun key(): String = cached.ifBlank { BuildConfig.GEMINI_API_KEY }

    /** True if there is a usable key (user-entered or BuildConfig). */
    fun hasKey(): Boolean = key().isNotBlank()
}
