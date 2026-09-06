package com.kevin.legion.backend.engine

import android.content.Context
import com.kevin.legion.backend.ChecklistsBackend
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.SupabaseAuth
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseEventsBackend

/**
 * The one place the per-aspect transport switch ([EngineTransport]) turns into an actual backend
 * object. Everything that used to write `SupabaseEventsBackend(SupabaseClientProvider.get(app)!!)`
 * inline now asks here instead, so "which transport is this aspect on" is answered once rather
 * than at five call sites that could drift apart.
 *
 * **[EventsSync.pull][com.kevin.legion.backend.EventsSync.pull] itself is deliberately NOT changed
 * to consult this.** That function takes its [EventsBackend] as a parameter and its merge rules
 * and tests are frozen by this ticket's brief; the resolution therefore happens at every place a
 * backend is CONSTRUCTED (`EventsSync.maybeAutoPull`, `EventsOutboxDrain.maybeDrain`,
 * `EventsAppointmentWriter`, `EnginePoll`), which is the same switch expressed where it can be
 * made without touching a tested function's signature.
 *
 * **Two ways to ask, and the difference is whether the caller may suspend.**
 * [eventsBackendNow] is synchronous and takes whatever auth state exists this instant - the
 * write-through path, which has never waited for auth and must not start (a voice-created
 * appointment cannot block on a token refresh). [eventsBackendAfterAuth] is suspending and, on the
 * Supabase branch only, awaits [SupabaseAuth.resolveSignedInUserId]'s single bounded retry - the
 * cold-start fix traced on the A25 2026-09-02, which the pull and drain paths depend on. The
 * Django branch has no equivalent wait because its token is read straight out of
 * [EngineConfig]: there is no session to restore asynchronously.
 *
 * **No `object` singleton** - a plain class taking [Context] and its two collaborators as
 * constructor parameters, per CLAUDE.md section 8's controller rule and this ticket's brief.
 */
class EngineBackends(
    context: Context,
    private val transport: EngineTransport = EngineTransport(context),
    private val config: EngineConfig = EngineConfig(context.applicationContext),
) {
    private val app: Context = context.applicationContext

    /**
     * True when the aspect's CURRENT transport has something to talk to - a Supabase client for
     * [Transport.SUPABASE], an address plus a stored token for [Transport.DJANGO]. Cheap,
     * synchronous and side-effect-free, so a caller may use it to decide whether to reserve a
     * throttle slot before launching any coroutine at all (which is exactly what
     * `EventsSync.maybeAutoPull` needs: see its own doc comment on why that reservation must not
     * move inside the launch).
     */
    fun isConfiguredFor(aspect: String): Boolean = when (transport.transportFor(aspect)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app) != null
        Transport.DJANGO -> engineHttp().isUsable()
    }

    /** The events backend for whatever transport `events` is set to, or null when that transport
     * is not usable on this device. No auth wait - see this class's own doc comment. */
    fun eventsBackendNow(): EventsBackend? = when (transport.transportFor(ASPECT_EVENTS)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app)?.let { SupabaseEventsBackend(it) }
        Transport.DJANGO -> djangoEventsBackend()
    }

    /** As [eventsBackendNow], but awaits the Supabase session restore's one bounded retry before
     * giving up on that branch. */
    suspend fun eventsBackendAfterAuth(): EventsBackend? = when (transport.transportFor(ASPECT_EVENTS)) {
        Transport.SUPABASE -> {
            val client = SupabaseClientProvider.get(app)
            if (client == null || SupabaseAuth(app).resolveSignedInUserId() == null) {
                null
            } else {
                SupabaseEventsBackend(client)
            }
        }
        Transport.DJANGO -> djangoEventsBackend()
    }

    /**
     * The checklists backend, or null.
     *
     * **Null whenever `checklists` is still on [Transport.SUPABASE], because there is no Supabase
     * checklists backend to fall back to** - those three tables are Django-owned end to end
     * (`server/checklists/models.py`: "the FIRST tables Django owns end to end, no legacy to
     * honour"). Since every aspect defaults to [Transport.SUPABASE] until the debug Setup row
     * flips it, an untouched install syncs no checklists at all and behaves exactly as it did
     * before this ticket - which is the conservative reading of the brief's "its transport gate is
     * signed in to the engine or not", and the one that keeps the Setup screen's own promise
     * ("Supabase remains the truth for every aspect until it is explicitly flipped here") true.
     * The other reading - sync checklists the moment a device signs in, ignoring the toggle - is a
     * one-line change here and nowhere else.
     */
    fun checklistsBackend(): ChecklistsBackend? =
        if (transport.transportFor(ASPECT_CHECKLISTS) != Transport.DJANGO) {
            null
        } else {
            engineHttp().takeIf { it.isUsable() }?.let { DjangoChecklistsBackend(it) }
        }

    private fun djangoEventsBackend(): EventsBackend? =
        engineHttp().takeIf { it.isUsable() }?.let { DjangoEventsBackend(it) }

    /** Built per call rather than held - [EngineHttp] is a thin wrapper around a lazily-created
     * Ktor client and holds no per-request state, and building one is cheaper than reasoning about
     * whether a cached one still matches an address the driver just changed in Setup. */
    private fun engineHttp() = EngineHttp(config)

    companion object {
        const val ASPECT_EVENTS = "events"
        const val ASPECT_CHECKLISTS = "checklists"
    }
}
