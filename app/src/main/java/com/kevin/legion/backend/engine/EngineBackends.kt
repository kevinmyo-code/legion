package com.kevin.legion.backend.engine

import android.content.Context
import com.kevin.legion.backend.BodyBackend
import com.kevin.legion.backend.ChecklistsBackend
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.MemoryBackend
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.SupabaseAuth
import com.kevin.legion.backend.SupabaseBodyBackend
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseEventsBackend
import com.kevin.legion.backend.SupabaseMemoryBackend
import com.kevin.legion.backend.SupabasePlacesBackend
import com.kevin.legion.backend.SupabaseVoiceNotesBackend
import com.kevin.legion.backend.VoiceNotesBackend

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
    private val config: EngineConfig = EngineConfig(context.applicationContext),
    /** Defaulted FROM [config], not from [context], and the order of these two parameters is what
     * makes that possible. [EngineTransport]'s shipped default for `events`/`checklists` is
     * conditional on the engine being usable, so a transport reading one [EngineConfig] while the
     * backends here read another could answer "Django" for an aspect this object then has no token
     * to build a Django backend for. One config, one answer. */
    private val transport: EngineTransport = EngineTransport(context, config),
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
     * honour"), so for this aspect "not on Django" simply means "not synced".
     *
     * **This paragraph used to say the opposite of what it now says, and the difference is the
     * 2026-09-06 default flip.** It read: "Since every aspect defaults to [Transport.SUPABASE]
     * until the debug Setup row flips it, an untouched install syncs no checklists at all" - true
     * when written, and no longer. `checklists` now defaults to [Transport.DJANGO] whenever this
     * device has a usable engine ([EngineTransport.DJANGO_BY_DEFAULT]), so an untouched install
     * that is SIGNED IN syncs checklists without anyone flipping a row - which is the reading the
     * old text explicitly deferred ("sync checklists the moment a device signs in, ignoring the
     * toggle - a one-line change here and nowhere else"), taken deliberately after the A25 end-to-end
     * run. An install with no engine still syncs nothing at all, exactly as before.
     */
    fun checklistsBackend(): ChecklistsBackend? =
        if (transport.transportFor(ASPECT_CHECKLISTS) != Transport.DJANGO) {
            null
        } else {
            engineHttp().takeIf { it.isUsable() }?.let { DjangoChecklistsBackend(it) }
        }

    /**
     * True when [aspect] is on Supabase only because this device has no usable engine, and would
     * otherwise be on Django by its shipped default. Pure pass-through to
     * [EngineTransport.isFallingBackToSupabase] - it lives here too so [EngineSyncNow], which
     * already holds an [EngineBackends] and no [EngineTransport], can put it into words without
     * growing a second collaborator that could read a different [EngineConfig].
     */
    fun isFallingBackToSupabase(aspect: String): Boolean = transport.isFallingBackToSupabase(aspect)

    /**
     * The places backend for whatever transport `places` is set to, or null when that transport is
     * not usable on this device (no Supabase project saved; no engine address or token).
     *
     * **No auth wait on either branch, and that is on purpose.**
     * [com.kevin.legion.location.PlaceController] is pure write-through with no outbox - a failed
     * write is spoken as a failure and nothing is queued - and it has never waited for a Supabase
     * session before resolving its backend. Adding a wait here would change when `tag_place`
     * answers, on a path that sits behind a voice tool and a geofence.
     */
    fun placesBackend(): PlacesBackend? = when (transport.transportFor(ASPECT_PLACES)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app)?.let { SupabasePlacesBackend(it) }
        Transport.DJANGO -> engineHttp().takeIf { it.isUsable() }?.let { DjangoPlacesBackend(it) }
    }

    /** The voice-notes backend for whatever transport `voice_notes` is set to, or null. Same
     * no-auth-wait shape as [placesBackend] - `VoiceNoteController` resolves its backend inside a
     * stop-recording path that must not block. */
    fun voiceNotesBackend(): VoiceNotesBackend? = when (transport.transportFor(ASPECT_VOICE_NOTES)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app)?.let { SupabaseVoiceNotesBackend(it) }
        Transport.DJANGO -> engineHttp().takeIf { it.isUsable() }?.let { DjangoVoiceNotesBackend(it) }
    }

    /**
     * The body backend for whatever transport `body` is set to, or null.
     *
     * **"Now" semantics - no Supabase session wait - and every existing caller already does its
     * own.** `BodySync.maybeAutoPull`, `BodyOutboxDrain.maybeDrain` and `BodyBackfill.maybeAutoRun`
     * each await [SupabaseAuth.resolveSignedInUserId] themselves before they get here (the
     * cold-start fix traced in their own doc comments), and `BodyWriteThrough` deliberately does
     * not wait at all. Putting a wait inside this function would either duplicate theirs or impose
     * one on the write path that has never had one, so the resolution stays where it is and this
     * only answers which transport.
     */
    fun bodyBackend(): BodyBackend? = when (transport.transportFor(ASPECT_BODY)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app)?.let { SupabaseBodyBackend(it) }
        Transport.DJANGO -> engineHttp().takeIf { it.isUsable() }?.let { DjangoBodyBackend(it) }
    }

    /** The memory backend for whatever transport `memory` is set to, or null. Same "Now" semantics
     * as [bodyBackend], for the same reason. */
    fun memoryBackend(): MemoryBackend? = when (transport.transportFor(ASPECT_MEMORY)) {
        Transport.SUPABASE -> SupabaseClientProvider.get(app)?.let { SupabaseMemoryBackend(it) }
        Transport.DJANGO -> engineHttp().takeIf { it.isUsable() }?.let { DjangoMemoryBackend(it) }
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

        // The Phase 5 aspects. Every one of these strings already appears in
        // [EngineTransport.KNOWN_ASPECTS] (the debug toggle screen has listed them since the
        // slice landed); naming them here is what lets a call site ask about one without a
        // literal, and it does NOT change any default - all four resolve to
        // [Transport.SUPABASE] until they are in [EngineTransport.DJANGO_BY_DEFAULT], which is a
        // cutover that belongs with that aspect's own run on the phone.
        const val ASPECT_PLACES = "places"
        const val ASPECT_VOICE_NOTES = "voice_notes"
        const val ASPECT_BODY = "body"
        const val ASPECT_MEMORY = "memory"
    }
}
