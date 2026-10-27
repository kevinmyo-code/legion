package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionProfileEntity
import java.util.UUID

/**
 * Room-backed CRUD for named, synced companion profiles, plus the two glue
 * steps that keep [CompanionProfile]'s legacy flat SharedPreferences keys
 * correct: seeding one profile from a pre-multi-companion install
 * ([ensureSeeded]), and materialising the device's ACTIVE profile into those
 * flat keys whenever it might have changed ([materializeActive]). See
 * [CompanionProfileEntity]'s doc comment for the full design and
 * [ActiveCompanionProfile] for the device-local selection this reads.
 *
 * Deliberately not a cache/singleton over the Room rows - there is no hot
 * path here reading a profile every conversational turn, the way
 * [GeminiKeyProvider] caches a key that's checked on every Live tool call.
 * [CompanionProfile]'s flat keys already ARE that hot-path cache; this object
 * just keeps them warm.
 *
 * Part 1 scope (data model + sync only, no UI): there is no picker, roster
 * screen, create/rename/delete UI, or onboarding hookup yet - those are Part
 * 2/3. [CompanionReset.resetCompanion] also does not touch the roster or the
 * active selection yet; a driver who resets still keeps whatever profile row
 * and active id they had, which is a known gap for whichever part wires reset
 * into the roster.
 *
 * **`ui/CompanionsScreen.kt` edits the active profile** through
 * [activeProfile]/[saveProfile] below, kept here rather than in the screen so
 * the Room/[ActiveCompanionProfile] sequencing (write-then-materialise) has
 * exactly one implementation. [CompanionProfileEntity.persona] holds a
 * [Persona.key], never assembled clause text - see [AssistantIdentity], which
 * resolves the key through [personaFor] at read time.
 *
 * **One persona (Kevin, 2026-10-27, ADR 0063).** The phone has only [ALFRED].
 * The table and its sync are unchanged and no row is ever deleted for it;
 * [materializeActive] moves this device's active selection onto an Alfred row
 * when the synced roster holds one ([alfredFallback]).
 */
object CompanionProfileStore {

    /**
     * One-time migration for an install that predates named profiles: if the
     * roster is empty and [CompanionProfile] already holds an onboarded
     * identity (name or persona non-blank), wrap it in a profile row and make
     * it active, so nobody's existing assistant disappears the moment this
     * ships. No-op if a roster already exists (built locally by a previous
     * run of this seed, or pulled fresh from Drive before this device ever
     * ran it) or if the install has no identity yet (a genuinely fresh
     * install still has onboarding ahead of it - Part 2/3's job, not this
     * one's).
     *
     * Call from [com.kevin.legion.MidnightApplication.onCreate] - L12
     * (playbook-coding.md): process-wide seeding belongs in `Application
     * .onCreate`, never a conditionally-started service, so it runs on every
     * launch regardless of whether the assistant service itself is toggled on.
     */
    suspend fun ensureSeeded(context: Context) {
        val dao = CarDatabase.getDatabase(context).companionProfileDao()
        val existingCount = dao.count()
        val name = CompanionProfile.name(context)
        val persona = CompanionProfile.persona(context)
        if (!shouldSeedFromLegacy(existingCount, name, persona)) return

        val profile = buildSeedProfile(
            profileId = UUID.randomUUID().toString(),
            legacyName = name,
            legacyPersona = persona,
            legacyTraits = encodeSelections(CompanionProfile.selections(context)),
            legacyVoice = CompanionProfile.voice(context),
            legacyVoiceStyle = CompanionProfile.voiceStyle(context),
            legacyVoiceStyleTraits = encodeSelections(CompanionProfile.voiceStyleSelections(context)),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(profile)
        ActiveCompanionProfile.setActiveProfileId(context, profile.profileId)
    }

    /**
     * Pure decision for [ensureSeeded]: seed a legacy identity into a profile
     * row only when the roster is genuinely empty AND there is something to
     * migrate (a fresh install with a blank [CompanionProfile] has nothing to
     * seed - onboarding, Part 2/3, is what creates its first profile). Split
     * out (mirrors [com.kevin.legion.sync.SyncMerge]'s "pure planner, then
     * execute" split) so the decision unit-tests without Room or
     * SharedPreferences - see `CompanionProfileStoreTest`.
     */
    internal fun shouldSeedFromLegacy(existingProfileCount: Int, legacyName: String, legacyPersona: String): Boolean =
        existingProfileCount == 0 && (legacyName.isNotBlank() || legacyPersona.isNotBlank())

    /**
     * Pure construction of the seeded profile row from a pre-multi-companion
     * install's [CompanionProfile] fields. Split out for the same
     * unit-testability reason as [shouldSeedFromLegacy].
     */
    internal fun buildSeedProfile(
        profileId: String,
        legacyName: String,
        legacyPersona: String,
        legacyTraits: String,
        legacyVoice: String,
        legacyVoiceStyle: String,
        legacyVoiceStyleTraits: String,
        updatedAt: Long,
    ): CompanionProfileEntity = CompanionProfileEntity(
        profileId = profileId,
        assistantName = legacyName,
        persona = legacyPersona,
        traits = legacyTraits,
        voice = legacyVoice,
        voiceStyle = legacyVoiceStyle,
        voiceStyleTraits = legacyVoiceStyleTraits,
        updatedAt = updatedAt,
    )

    /**
     * Writes the device's ACTIVE profile's fields into [CompanionProfile]'s
     * existing keys via its plain setters ([CompanionProfile.saveProfileFull],
     * [CompanionProfile.saveVoice], [CompanionProfile.saveVoiceStyle]), so
     * every current reader (`AriaBrain`, `LiveSessionController`,
     * `GeminiLiveSession`, ...) keeps working untouched - this is the whole
     * simplification the profiles design rests on.
     *
     * Must run at: app start (after [ensureSeeded]), whenever
     * [ActiveCompanionProfile.setActiveProfileId] switches the active
     * profile, and after a sync pass that may have pulled a newer version of
     * the active profile's row from another device (see
     * [com.kevin.legion.sync.SyncEngine.syncNow]).
     *
     * First applies [alfredFallback], so a device left on a retired persona's
     * row (or on a row deleted elsewhere) lands on an Alfred row when one exists.
     *
     * No-op if no profile is active yet, or if the active id no longer
     * resolves to a row. Both cases leave [CompanionProfile]'s keys exactly
     * as they were rather than blanking them - an unmaterialisable state must
     * never erase an identity that's already sitting in the flat keys.
     */
    suspend fun materializeActive(context: Context) {
        val dao = CarDatabase.getDatabase(context).companionProfileDao()
        alfredFallback(dao.getAll(), ActiveCompanionProfile.activeProfileId(context))?.let {
            ActiveCompanionProfile.setActiveProfileId(context, it)
        }
        val activeId = ActiveCompanionProfile.activeProfileId(context) ?: return
        val profile = dao.getById(activeId) ?: return
        CompanionProfile.saveProfileFull(
            context,
            name = profile.assistantName,
            persona = profile.persona,
            selections = decodeSelections(profile.traits),
        )
        CompanionProfile.saveVoice(context, profile.voice)
        CompanionProfile.saveVoiceStyle(context, decodeSelections(profile.voiceStyleTraits))
    }

    /**
     * Which profile this device should switch to so that it answers from an
     * Alfred row (Kevin, 2026-10-27, ADR 0063), or null to leave the active
     * selection alone. A row counts as Alfred when its persona key is
     * [ALFRED]'s or blank (a legacy seed with no key always resolved to Alfred).
     *
     * - Active row exists and is Alfred: null, nothing to do.
     * - Otherwise the first Alfred row in [roster] (the DAO's newest-edit-first
     *   order), if there is one.
     * - No Alfred row, and the active id resolves to nothing: the first row of
     *   any persona, so the device is never left without an identity. It
     *   answers as Alfred anyway, because [personaFor] does.
     * - No Alfred row and the active row exists: null. It already answers as
     *   Alfred; its name and voice are left as the household set them.
     *
     * Never creates or deletes a row. Pure for the same reason as
     * [shouldSeedFromLegacy] - see `CompanionProfileStoreTest`.
     */
    internal fun alfredFallback(roster: List<CompanionProfileEntity>, activeId: String?): String? {
        fun isAlfred(row: CompanionProfileEntity) = row.persona == ALFRED.key || row.persona.isBlank()
        val active = roster.firstOrNull { it.profileId == activeId }
        return when {
            active != null && isAlfred(active) -> null
            roster.any(::isAlfred) -> roster.first(::isAlfred).profileId
            active == null -> roster.firstOrNull()?.profileId
            else -> null
        }
    }

    /**
     * The device's ACTIVE profile row, or null if none is active yet (a fresh
     * install pre-onboarding) or the active id no longer resolves (its row was
     * deleted from another device before this one's next sync - a known gap,
     * same shape as [materializeActive]'s own no-op fallback). Read-only
     * convenience so `AssistantSettingsScreen`'s "who is active" line and
     * `CompanionsScreen` don't each hand-roll the [ActiveCompanionProfile] +
     * DAO lookup.
     */
    suspend fun activeProfile(context: Context): CompanionProfileEntity? {
        val activeId = ActiveCompanionProfile.activeProfileId(context) ?: return null
        return CarDatabase.getDatabase(context).companionProfileDao().getById(activeId)
    }

    /**
     * Upserts [profile] (a create with a fresh UUID on an install with no
     * profile yet, or an edit reusing its existing id - see `CompanionsScreen`'s
     * editor). Re-materialises immediately if [profile] is the device's active
     * one, since a rename or re-voice of the ASSISTANT YOU ARE TALKING TO right
     * now must take effect without a restart.
     *
     * Callers are responsible for bumping `updatedAt` to "now" before calling
     * this - kept a caller concern (not defaulted here) so a future
     * sync-driven upsert (a newer row pulled from Drive) can pass the REMOTE
     * clock through unmodified rather than always stamping local time.
     */
    suspend fun saveProfile(context: Context, profile: CompanionProfileEntity) {
        val dao = CarDatabase.getDatabase(context).companionProfileDao()
        dao.upsert(profile)

        val activeId = ActiveCompanionProfile.activeProfileId(context)
        // Adopt this profile if the device has no usable active selection.
        //
        // The "never end up with no identity" invariant was implemented for
        // DELETION and missed on CREATION. Found on device 2026-08-02: a fresh
        // install (no legacy identity, so `ensureSeeded` correctly seeded
        // nothing) let two profiles be created with NO active selection at all.
        // The roster showed neither as active, and `AssistantIdentity` fell
        // through to `personaFor(null)` - answering as Alfred by fallback luck
        // rather than by choice, with a blank name.
        //
        // Also covers an active id pointing at a row that no longer exists,
        // which a delete on the other device can produce once the roster syncs.
        val activeResolves = activeIdResolves(activeId, dao.getById(activeId ?: ""))
        if (!activeResolves) {
            ActiveCompanionProfile.setActiveProfileId(context, profile.profileId)
            materializeActive(context)
            return
        }
        if (activeId == profile.profileId) materializeActive(context)
    }

    /**
     * Whether the device's stored active id still points at a real profile.
     *
     * Pure and internal for the same reason [alfredFallback] is: this is the
     * branch the creation-adoption fix turns on, and it was the one decision in this file with no test.
     * Takes the already-looked-up row rather than a DAO so it stays testable
     * without Room.
     */
    internal fun activeIdResolves(activeId: String?, row: CompanionProfileEntity?): Boolean =
        activeId != null && row != null
}
