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
 * **`events` and `checklists` default to [Transport.DJANGO] as of 2026-09-06** - that is Phase 3's
 * "only when all six pass does the slice's transport flip to django in the committed default",
 * taken after the A25 run: a checklist tick reached Postgres in about a second, a task tick reached
 * it after commit `4d71da8`, a server-side write reached the phone on the 60 s poll, and an
 * unreachable engine was reported in words with the write queued and drained on return. **The other
 * seven aspects are untouched and still default to [Transport.SUPABASE]** - they have no Django
 * backend written yet, so flipping them would be flipping to nothing.
 *
 * **The default is CONDITIONAL on this device having a usable engine, and that is the guard the
 * flip lives or dies on.** A [Transport.DJANGO] aspect with no engine token resolves to no backend
 * at all ([EngineBackends.eventsBackendNow] returns null), so shipping an unconditional Django
 * default would have silently taken every `events` write off Supabase on a fresh install that has
 * never seen an engine - a phone that syncs nothing, reported nowhere. So [defaultFor] answers
 * [Transport.SUPABASE] until [EngineConfig] holds both an address and a token, which makes a fresh
 * install behave EXACTLY as it did before this change, and [isFallingBackToSupabase] exists so the
 * Setup screen's SYNC NOW row can say in words that that is what happened (CLAUDE.md section 7:
 * nothing degrades quietly).
 *
 * **The fallback applies to the DEFAULT only, never to an explicit flip.** An aspect Kevin flipped
 * by hand in the debug row is honoured verbatim even with no token - it resolves to no backend and
 * says so, rather than quietly routing that aspect's writes to Supabase behind his back. Silently
 * re-pointing a chosen transport is precisely the split-brain this whole switch exists to prevent
 * (it is the bug fixed in `NotesController.backend` the same day), and "the write failed loudly"
 * is a better outcome than "the write went somewhere else".
 *
 * **No `object` singleton, deliberately** - same Hilt-readiness note as [EngineConfig]/[EngineAuth]:
 * a plain class taking its [Context] as a constructor parameter.
 */
enum class Transport { SUPABASE, DJANGO }

class EngineTransport(
    context: Context,
    /** Injectable purely so a test can exercise the signed-in and signed-out defaults without a
     * real Android Keystore - the same seam, for the same reason, [EngineConfig]'s own doc comment
     * gives for its `encrypt`/`decrypt` parameters. */
    private val config: EngineConfig = EngineConfig(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The transport [aspect] is on: whatever was explicitly stored for it, else [defaultFor].
     * An aspect name this class has never heard of is [Transport.SUPABASE], so a future aspect is
     * safe by construction rather than needing a line added here first before it can be asked
     * about.
     */
    fun transportFor(aspect: String): Transport {
        val stored = prefs.getString(keyFor(aspect), null) ?: return defaultFor(aspect)
        return runCatching { Transport.valueOf(stored) }.getOrElse { defaultFor(aspect) }
    }

    /** The debug-only Setup screen row is the one caller today - see KeyScreen.kt's Engine
     * section. Storing a value takes [aspect] out of [defaultFor]'s reach permanently, in both
     * directions: an explicit SUPABASE is not the same as never having chosen. */
    fun setTransport(aspect: String, transport: Transport) {
        prefs.edit().putString(keyFor(aspect), transport.name).apply()
    }

    /**
     * True when [aspect] would be on Django by its shipped default but this device has no usable
     * engine, so [transportFor] answered [Transport.SUPABASE] instead. **The one caller is the
     * sentence [EngineSyncNow] hands back**, because a fallback nobody is told about is
     * indistinguishable from a setting that was never applied.
     *
     * False for an explicitly-flipped aspect either way - see the class doc's third paragraph:
     * an explicit choice is never overridden, so there is nothing to report.
     */
    fun isFallingBackToSupabase(aspect: String): Boolean =
        prefs.getString(keyFor(aspect), null) == null &&
            aspect in DJANGO_BY_DEFAULT &&
            !engineUsable()

    /** The shipped default for an aspect nobody has flipped by hand - see the class doc for why
     * this is conditional rather than a constant. */
    private fun defaultFor(aspect: String): Transport =
        if (aspect in DJANGO_BY_DEFAULT && engineUsable()) Transport.DJANGO else Transport.SUPABASE

    /** Address plus token, the same pair [EngineHttp.isUsable] gates a request on - read straight
     * off [EngineConfig] here rather than through [EngineHttp] so nothing builds an HTTP client
     * merely to answer which transport an aspect is on. */
    private fun engineUsable(): Boolean = config.isConfigured() && config.isSignedIn()

    private fun keyFor(aspect: String) = "transport_$aspect"

    companion object {
        private const val PREFS = "engine_transport"

        /**
         * The aspects whose shipped default is [Transport.DJANGO] - and as of 2026-09-10 that is
         * ALL NINE, which is a deliberate departure from how this set was grown until now.
         *
         * **The old rule, and why it stopped applying.** This comment used to read: "Adding an
         * aspect here is the committed cutover for it and belongs with that aspect's own
         * end-to-end run on the phone, never ahead of it." That rule existed to stop an unverified
         * Django path replacing a WORKING Supabase one. It was right, and it is now moot for the
         * last three: ADR 0045's tenancy migration put `household_id NOT NULL` with no default on
         * all forty-three `public` tables, and the phone's Supabase backends do not know that
         * column exists - grep `household` in `SupabaseLedgerBackend`, `SupabasePantryBackend` or
         * `SupabaseFleetBackend` and you get nothing. So every write on that path now fails a
         * NOT NULL constraint. There is no working path left to protect, and Django's backends do
         * set the column.
         *
         * Kevin, 2026-09-10, told the above: *"yes flip it, fully django."*
         *
         * **What is verified and what is not, stated plainly rather than implied by membership
         * here.** events, checklists, body and memory were run end to end on the A25. places and
         * voice notes had their pull path exercised and nothing more. **ledger, pantry and fleet
         * have never run against Django on hardware at all** - they are here because the
         * alternative is a path that cannot work, not because they were proven. The first person
         * to run them should expect to find things; three of the four aspects verified this way
         * produced defects a green suite had missed.
         *
         * Still conditional on the engine being usable (see [defaultFor]): an install with no
         * engine or no token falls back to Supabase for everything, exactly as before.
         */
        val DJANGO_BY_DEFAULT: Set<String> = setOf(
            "events",
            "checklists",
            "body",
            "memory",
            "places",
            "voice_notes",
            "ledger",
            "pantry",
            "fleet",
        )

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
