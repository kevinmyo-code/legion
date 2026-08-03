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
 * **Part 2 (`ui/CompanionsScreen.kt`) adds the roster/picker screen** and,
 * with it, [roster]/[activeProfile]/[saveProfile]/[switchActive]/[deleteProfile]
 * below - the CRUD and switch operations the screen needs, kept here rather
 * than in the screen itself so the Room/[ActiveCompanionProfile] sequencing
 * (write-then-materialise, delete-then-maybe-reactivate) has exactly one
 * implementation. [CompanionProfileEntity.persona] holds a [Persona.key]
 * (`"alfred"`/`"dorothy"`), never assembled clause text - see [AssistantIdentity],
 * which resolves the key through [personaFor] at read time.
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
     * No-op if no profile is active yet, or if the active id no longer
     * resolves to a row. Both cases leave [CompanionProfile]'s keys exactly
     * as they were rather than blanking them - an unmaterialisable state must
     * never erase an identity that's already sitting in the flat keys.
     */
    suspend fun materializeActive(context: Context) {
        val activeId = ActiveCompanionProfile.activeProfileId(context) ?: return
        val dao = CarDatabase.getDatabase(context).companionProfileDao()
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

    // --- Part 2: roster screen CRUD + switch -------------------------------

    /** The full synced roster, newest edit first (thin pass-through to the DAO's own ordering). */
    suspend fun roster(context: Context): List<CompanionProfileEntity> =
        CarDatabase.getDatabase(context).companionProfileDao().getAll()

    /**
     * The device's ACTIVE profile row, or null if none is active yet (a fresh
     * install pre-onboarding) or the active id no longer resolves (its row was
     * deleted from another device before this one's next sync - a known gap,
     * same shape as [materializeActive]'s own no-op fallback). Read-only
     * convenience so `SettingsScreen`'s "who is active" line and
     * `CompanionsScreen`'s roster don't each hand-roll the
     * [ActiveCompanionProfile] + DAO lookup.
     */
    suspend fun activeProfile(context: Context): CompanionProfileEntity? {
        val activeId = ActiveCompanionProfile.activeProfileId(context) ?: return null
        return CarDatabase.getDatabase(context).companionProfileDao().getById(activeId)
    }

    /**
     * Upserts [profile] (a create with a fresh UUID, or an edit reusing its
     * existing id - the roster screen builds both the same way, see
     * `CompanionsScreen`'s editor). Re-materialises immediately if [profile] is
     * the device's active one, since a rename/re-voice/re-persona of the
     * ASSISTANT YOU ARE TALKING TO right now must take effect without a
     * restart; a create, or an edit to some other (non-active) profile, only
     * touches the row.
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
     * Pure and internal for the same reason [canDelete] and
     * [nextActiveAfterDeleting] are: this is the branch the creation-adoption
     * fix turns on, and it was the one decision in this file with no test.
     * Takes the already-looked-up row rather than a DAO so it stays testable
     * without Room.
     */
    internal fun activeIdResolves(activeId: String?, row: CompanionProfileEntity?): Boolean =
        activeId != null && row != null

    /**
     * Switches the device's active profile to [profileId] and materialises it,
     * in that fixed order - [ActiveCompanionProfile.setActiveProfileId]'s own
     * doc comment is explicit that callers must follow it with
     * [materializeActive] for the switch to actually reach [CompanionProfile]'s
     * flat keys, and this is the one place `CompanionsScreen`'s tap-to-switch
     * calls through.
     */
    suspend fun switchActive(context: Context, profileId: String) {
        ActiveCompanionProfile.setActiveProfileId(context, profileId)
        materializeActive(context)
    }

    /**
     * Deletes [profileId], refusing (no-op, returns false) if it is the
     * roster's last remaining row - the assistant must never end up with no
     * identity at all. If the deleted profile was this device's ACTIVE one,
     * activates [nextActiveAfterDeleting]'s pick and materialises it in the
     * same order [switchActive] uses, so this device is never left pointing at
     * an active id that no longer resolves to anything.
     */
    suspend fun deleteProfile(context: Context, profileId: String): Boolean {
        val dao = CarDatabase.getDatabase(context).companionProfileDao()
        val roster = dao.getAll()
        if (!canDelete(roster.size)) return false
        dao.delete(profileId)
        if (ActiveCompanionProfile.activeProfileId(context) == profileId) {
            val next = nextActiveAfterDeleting(roster, profileId) ?: return true
            switchActive(context, next)
        }
        return true
    }

    /**
     * Pure guard for [deleteProfile]: true only when the roster has more than
     * one row, i.e. deleting one still leaves at least one identity behind.
     * Split out for the same unit-testability reason as [shouldSeedFromLegacy] -
     * see `CompanionProfileStoreTest`.
     */
    internal fun canDelete(rosterSize: Int): Boolean = rosterSize > 1

    /**
     * Pure decision for [deleteProfile]: which profile becomes active after
     * removing [deletedId] from [roster]. Picks the first remaining row in
     * [roster]'s own order (the DAO returns newest-edit-first, so this lands on
     * the next-most-recently-touched profile rather than an arbitrary one).
     * Returns null only if [roster] held nothing but [deletedId] - callers
     * gate that case with [canDelete] first, so in practice this only fires
     * once a second row is confirmed to exist.
     */
    internal fun nextActiveAfterDeleting(roster: List<CompanionProfileEntity>, deletedId: String): String? =
        roster.firstOrNull { it.profileId != deletedId }?.profileId
}
