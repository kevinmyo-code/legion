package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named, synced assistant identity (multi-companion, 2026-08-02).
 *
 * Kevin's decision: two people share one Google account and two phones. Kevin
 * wants an Alfred-style assistant; his wife wants a different one. Rather than
 * bolt a second global identity onto [com.kevin.legion.ai.CompanionProfile]'s
 * flat SharedPreferences keys, the identity itself becomes a SET of named rows
 * that sync via [com.kevin.legion.sync.SyncEngine]'s normal LWW registry (see
 * `Spec("companion_profiles", ...)`), and each device picks which one is
 * ACTIVE locally (see `ai/ActiveCompanionProfile.kt` - deliberately
 * device-local, does not sync).
 *
 * [profileId] is a portable UUID string, not an autoincrement id, because it
 * is the row's cross-device identity - the same shape as every other
 * natural-key synced table in [com.kevin.legion.sync.SyncEngine.REGISTRY]
 * (`vehicles/obdMac`, `places/label`, etc). Mode is LWW, not UNION: a profile
 * is edited over time (renamed, re-personified, a new voice picked) and the
 * newer edit should win, the same way a vehicle or a maintenance item does.
 *
 * The fields mirror [com.kevin.legion.ai.CompanionProfile]'s IDENTITY keys
 * exactly (name/persona/traits/voice/voiceStyle/voiceStyleTraits) - reusing
 * the existing [com.kevin.legion.ai.PersonaTraits]/[com.kevin.legion.ai.Voices]/
 * [com.kevin.legion.ai.VoiceStyle] machinery rather than inventing a second
 * persona concept. Everything that is NOT identity (Gemini/Shelly/Spotify
 * credentials, the spend hash, sync-enabled, first-session-done) stays where
 * it already lives, in [com.kevin.legion.ai.CompanionProfile]'s global
 * (unkeyed) SharedPreferences fields - those belong to the INSTALL, not to a
 * named companion, and must never sync (CLAUDE.md sec 7, no hosted anything -
 * a leaked key riding along in a synced identity row would be a real leak).
 *
 * `traits`/`voiceStyleTraits` are the same encoded-picker-selections strings
 * [com.kevin.legion.ai.encodeSelections] already produces for
 * `Vehicle.personaTraits` - kept as opaque strings here too so round-tripping
 * through sync is a plain field copy, no re-decode/re-encode needed.
 */
@Entity(tableName = "companion_profiles")
data class CompanionProfileEntity(
    @PrimaryKey val profileId: String,
    val assistantName: String,
    val persona: String,
    val traits: String,
    val voice: String,
    val voiceStyle: String,
    val voiceStyleTraits: String,
    /** LWW sync clock - see [com.kevin.legion.sync.SyncEngine.REGISTRY]'s `clock = "updatedAt"`. */
    val updatedAt: Long,
)
