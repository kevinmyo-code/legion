package com.kevin.legion.backend.engine

import android.content.Context

/**
 * Per-aspect switch between the two backends every aspect can theoretically read from during the
 * Django-engine port (ADR 0044, .scratch/django-engine/research/execution-plan.md Phase 2's
 * "5. A per-aspect switch in SupabaseConfig: transport = supabase | django. Default supabase. The
 * slice aspects flip to django when Phase 3 passes." - this file IS that switch, factored into
 * its own home under backend/engine/ rather than added to
 * [com.kevin.legion.backend.SupabaseConfig] itself, since it is the Django side of the seam, not
 * the Supabase side).
 *
 * **Nothing reads this yet.** No aspect's sync/backend code branches on [transportFor] as of this
 * ticket - it exists so ticket 09's second half (wiring EventsSync/EventsBackend to actually check
 * it) has a stable seam to read from, per the map's "write it now so the events backend can branch
 * on it next" instruction. Every aspect defaults to [Transport.SUPABASE], so building this class
 * changes no behaviour anywhere in the app until something is explicitly flipped - and the only
 * way to flip one today is the debug-only row this ticket's Setup UI adds.
 *
 * **No `object` singleton, deliberately** - same Hilt-readiness note as [EngineConfig]/[EngineAuth]:
 * a plain class taking its [Context] as a constructor parameter.
 */
enum class Transport { SUPABASE, DJANGO }

class EngineTransport(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * [Transport.SUPABASE] for any [aspect] never explicitly flipped - including an aspect name
     * this class has never heard of, so a future aspect is safe by construction rather than
     * needing a line added here first before it can be asked about.
     */
    fun transportFor(aspect: String): Transport {
        val stored = prefs.getString(keyFor(aspect), null) ?: return Transport.SUPABASE
        return runCatching { Transport.valueOf(stored) }.getOrDefault(Transport.SUPABASE)
    }

    /** The debug-only Setup screen row is the one caller today - see KeyScreen.kt's Engine
     * section. */
    fun setTransport(aspect: String, transport: Transport) {
        prefs.edit().putString(keyFor(aspect), transport.name).apply()
    }

    private fun keyFor(aspect: String) = "transport_$aspect"

    companion object {
        private const val PREFS = "engine_transport"

        /**
         * The aspects the debug toggle screen lists - the Phase 2 slice (events, checklists) plus
         * the aspects Phase 5 widens to, per the execution plan's own ordering. Naming an aspect
         * here does not build it; it only gives the debug UI something to list before its backend
         * exists, matching [transportFor]'s "any unknown aspect defaults to Supabase" contract.
         */
        val KNOWN_ASPECTS: List<String> = listOf(
            "events",
            "checklists",
            "ledger",
            "pantry",
            "body",
            "memory",
            "fleet",
            "voice_notes",
            "places",
        )
    }
}
