package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.backend.engine.EngineAuth
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.MeResult

/**
 * What the assistant calls him: the one source every voice path reads (2026-11-20 ruling: the app
 * may be given his name; before, no voice had one and the persona's "use their first name if you
 * know it" let the model invent one).
 *
 * Two inputs, one answer ([resolve]): the name he types under Settings > Assistant ("What should
 * the assistant call you?") wins; otherwise the engine's own name for his account (`User.first_name`,
 * from `GET /api/auth/me`, kept here by [noteEngine]). Only the first word is used. None of either:
 * no name, and the persona says to use none ([personaLine]).
 */
object OwnerName {
    private const val PREFS = "owner_name"
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_ENGINE = "engine"
    private const val KEY_ENGINE_AT = "engine_at_ms"
    private const val MAX_CHARS = 40

    /** How often the engine's name is re-read at most ([refreshFromEngine]). */
    const val ENGINE_REFRESH_MS = 24L * 60 * 60 * 1000

    /** The name to use, or null when none is known. */
    fun of(context: Context): String? = resolve(chosen(context), engine(context))

    /** The typed name wins over the engine's; each is cut to its first word. Pure. */
    fun resolve(chosen: String?, engine: String?): String? = clean(chosen) ?: clean(engine)

    /** First word, letters, apostrophes and hyphens only; null when nothing usable is left. Pure. */
    fun clean(raw: String?): String? {
        val first = raw?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        val kept = first.filter { it.isLetter() || it == '\'' || it == '-' }.trim('\'', '-')
        return kept.take(MAX_CHARS).ifEmpty { null }
    }

    /**
     * The persona's line about his name (`ai/Personas.kt`). With a name: use it now and then, and no
     * other. With none: use no name at all, never a made-up one. Pure.
     */
    fun personaLine(name: String?): String {
        val known = clean(name)
            ?: return "You do not know their name. Call them by no name at all; never make one up, and no " +
                "nickname or title."
        return "Their first name is $known. Use it now and then, not in every reply, and never any other " +
            "name, nickname or title for them."
    }

    fun chosen(context: Context): String = context.ownerPrefs.getString(KEY_CHOSEN, "").orEmpty()

    /** Saves what he typed; blank clears it, so the engine's name is used again. */
    fun setChosen(context: Context, name: String) {
        context.ownerPrefs.edit().putString(KEY_CHOSEN, name.trim()).apply()
    }

    fun engine(context: Context): String = context.ownerPrefs.getString(KEY_ENGINE, "").orEmpty()

    /** Keeps the engine's name for his account, as `GET /api/auth/me` returned it. */
    fun noteEngine(context: Context, name: String, nowMs: Long = System.currentTimeMillis()) {
        context.ownerPrefs.edit().putString(KEY_ENGINE, name.trim()).putLong(KEY_ENGINE_AT, nowMs).apply()
    }

    /** True when the engine's name was last read more than [ENGINE_REFRESH_MS] before [nowMs]. Pure. */
    fun stale(lastReadMs: Long, nowMs: Long): Boolean = nowMs - lastReadMs > ENGINE_REFRESH_MS

    /**
     * Re-reads the engine's name for his account at most once a day. Signed out, unreachable or
     * refused keeps what is kept ([EngineAuth.me] turns a network failure into a result).
     */
    suspend fun refreshFromEngine(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!stale(app.ownerPrefs.getLong(KEY_ENGINE_AT, 0L), nowMs)) return
        val config = EngineConfig(app)
        if (!config.isConfigured() || !config.isSignedIn()) return
        val me = EngineAuth(config).me()
        if (me is MeResult.Ok) noteEngine(app, me.name, nowMs)
    }

    private val Context.ownerPrefs
        get() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
