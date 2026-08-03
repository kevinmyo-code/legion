package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.vehicle.ActiveVehicle

/**
 * The companion's identity plus this install's BYO credentials.
 *
 * **Identity is PER CAR as of 2026-07-16 (car profiles).** Name, persona, traits,
 * voice, and the avatar id are all keyed by the active vehicle ([ActiveVehicle])
 * - the reasoning and the free-tier carve-out are in library/decisions.md
 * 2026-07-16. This per-car keying predates, and is untouched by, the
 * multi-companion change below; it is called out in CLAUDE.md sec 2 as stale
 * (LEGION §1 killed per-car identity) but has not been ripped out, so the
 * behaviour described here is what actually ships.
 *
 * **Named, synced companion profiles (Kevin, 2026-08-02) sit ON TOP of this
 * class, not inside it.** Two people share one Google account and two phones
 * and each wants a different assistant, so identity became a SET of named
 * rows ([com.kevin.legion.data.local.CompanionProfileEntity], synced via
 * [com.kevin.legion.sync.SyncEngine]'s normal LWW registry) with a
 * device-local active selection ([ActiveCompanionProfile] - deliberately
 * does NOT sync, see its doc comment). Rather than rewrite every reader of
 * this object (`AriaBrain`, `LiveSessionController`, `GeminiLiveSession`,
 * ...), switching the active profile - or pulling a newer version of it from
 * sync - MATERIALISES that profile's fields into the KEY_NAME/KEY_PERSONA/
 * KEY_TRAITS/KEY_VOICE/KEY_VOICE_STYLE/KEY_VOICE_STYLE_TRAITS keys below via
 * the plain setters ([savePersona]/[saveProfileFull]/[saveVoice]/
 * [saveVoiceStyle]), so every existing reader keeps working untouched. See
 * [CompanionProfileStore.materializeActive]. The old bespoke single-identity
 * sync path (`SyncEngine.syncCompanion`, per-car `companion-<id>.json`,
 * `CompanionSync.decideCompanion`'s "two companions met" clash prompt) is
 * retired in the same change: two named profiles simply coexist, there is
 * nothing left to clash over.
 *
 * Free-tier Zero is unaffected: she has no name, so nothing is keyed, and she
 * rides along in whatever car you're in. That is the correct behaviour for a
 * companion who is explicitly not the car.
 *
 * **Everything that is NOT identity stays global** - the Gemini/Shelly
 * credentials, the spend hash, the sync-enabled flag, first-session-done.
 * Those belong to the install, not to a car OR a named companion, and MUST
 * NEVER be added to [com.kevin.legion.data.local.CompanionProfileEntity] -
 * that table syncs to the driver's Drive, and a leaked secret riding along
 * in a synced identity row would be a real leak (CLAUDE.md sec 7).
 *
 * Per-car vehicle DATA (odometer, maintenance schedule, service history) lives in
 * [com.kevin.legion.vehicle.VehicleController], keyed the same way.
 */
object CompanionProfile {
    /** Fixed id for the single companion's cached avatar faces (see [AvatarStudio]). */
    const val AVATAR_ID = "companion"

    private const val PREFS = "companion_profile"
    private const val KEY_NAME = "name"
    private const val KEY_PERSONA = "persona"
    private const val KEY_TRAITS = "traits"
    private const val KEY_VOICE = "voice"
    // Delivery-style picker selections (pace/tone/energy, VoiceStyle.kt,
    // 2026-07-22) and their assembled "Delivery notes" text - layered on top of
    // KEY_VOICE (the preset name) to steer HOW that preset speaks, not which
    // preset. Per-car like persona/voice (same identityString/k(context,...)
    // pattern), but deliberately NOT yet added to CompanionSync's
    // CompanionIdentity data class - cross-device sync for a new identity
    // field is its own schema/LWW-clock decision, not a side effect of adding
    // a picker. Known gap: this does not sync across devices yet.
    private const val KEY_VOICE_STYLE = "voice_style"
    private const val KEY_VOICE_STYLE_TRAITS = "voice_style_traits"
    // SHA-256 hash of the owner's spend passphrase (never the plaintext). Blank =
    // no passphrase set. Storage-only field; the gating logic (SpendGate) that used
    // to read this was retired 2026-07-31 with no replacement built yet - this hash
    // is currently unread, kept in case a future gate reuses the same field.
    private const val KEY_SPEND_HASH = "spend_hash"
    // User-supplied Gemini API key. When set, used in place of BuildConfig.GEMINI_API_KEY.
    // KEY_GEMINI_KEY is the legacy plaintext slot (kept for migration + Keystore-failure
    // fallback); KEY_GEMINI_KEY_ENC holds the KeyVault-encrypted blob.
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_GEMINI_KEY_ENC = "gemini_api_key_enc"
    // User-supplied Shelly Cloud "Authorization cloud key" (BYO, required for the
    // garage/gate feature - Shelly app: User Settings -> Authorization cloud key ->
    // Get key). KEY_SHELLY_AUTH_KEY_ENC holds the KeyVault-encrypted blob;
    // KEY_SHELLY_AUTH_KEY is a plaintext fallback used only if encryption fails
    // (broken Keystore on this unit) - same shape as the Gemini key, a new field
    // with no prior plaintext-only version to migrate from. The account's server
    // host is not secret and lives in [com.kevin.legion.vehicle.GaragePreferences]
    // instead. See [com.kevin.legion.vehicle.ShellyCloudOpener].
    private const val KEY_SHELLY_AUTH_KEY = "shelly_auth_key"
    private const val KEY_SHELLY_AUTH_KEY_ENC = "shelly_auth_key_enc"
    // User-supplied Spotify App Remote client ID (BYO, optional power-user add-on,
    // 2026-07-21 reopen of the sec 8 freeze). The driver registers their OWN Spotify
    // dev app and pastes only its client ID; the redirect URI is app-fixed
    // ([com.kevin.legion.media.SpotifyController.REDIRECT_URI], which must match
    // the manifest scheme AND what the driver enters in the Spotify dashboard). Not
    // strictly a secret - a client ID is a public OAuth identifier - but stored
    // through KeyVault for one consistent credential path, same shape as the Gemini
    // key, a new field with no plaintext-only version to migrate from.
    private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_CLIENT_ID_ENC = "spotify_client_id_enc"
    // Spotify Web API OAuth tokens (PKCE, 2026-07-23). Needed because App Remote
    // has no free-text search - "play <name>" has to resolve a URI via the Web API
    // before App Remote can play it in-app. PKCE specifically because the BYO shape
    // gives us a client ID and no secret, which rules out client-credentials.
    private const val KEY_SPOTIFY_ACCESS_TOKEN_ENC = "spotify_access_token_enc"
    private const val KEY_SPOTIFY_REFRESH_TOKEN_ENC = "spotify_refresh_token_enc"
    private const val KEY_SPOTIFY_TOKEN_EXPIRY = "spotify_token_expiry"
    // Flipped to true the moment the very first Live session successfully connects,
    // so one-time first-run behaviours (the spoken introduction) fire exactly once.
    private const val KEY_FIRST_SESSION_DONE = "first_session_done"
    // Cross-device BYO-cloud sync (S1): true once the driver has connected their
    // own Google Drive (authorized the drive.appdata scope). Opt-in, off by
    // default; gates all sync work via [com.kevin.legion.sync.SyncCapability].
    // No token is stored here - the Google Identity Authorization API mints/refreshes
    // the Drive access token on demand (see [com.kevin.legion.sync.DriveAuth]).
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    // KEY_COMPANION_UPDATED_AT / KEY_COMPANION_SYNC_RECONCILED (the old bespoke
    // single-identity sync clock/reconciled-flag pair) were retired 2026-08-02
    // along with SyncEngine.syncCompanion and CompanionSync.decideCompanion:
    // identity now syncs as named CompanionProfileEntity rows with their OWN
    // `updatedAt` clock, so this class no longer needs one of its own. See
    // this object's class doc comment.

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Per-car identity keying (car profiles, 2026-07-16) -----------------
    //
    // Identity (name/persona/traits/voice + its sync clock) is PER CAR. Everything
    // else in this file - the Gemini/Shelly credentials, the spend hash, the
    // sync-enabled flag, first-session-done - stays global to the install, because
    // none of it is about who the companion is.
    //
    // Why per-car at all: CLAUDE.md §1 makes the paid companion the CAR itself. A
    // driver with two cars has two cars with faces, so one global identity is
    // incoherent the moment the car speaks as itself. Free-tier Zero is unaffected
    // and stays global by construction - she rides along in whatever car you're in,
    // and has no name to key on. See [CompanionIdentity].

    /** Per-car variant of an identity key, for an explicit car. */
    private fun k(base: String, vehicleId: String): String = "$base:$vehicleId"

    /** Per-car variant of an identity key, for the active car. */
    private fun k(context: Context, base: String): String = k(base, ActiveVehicle.current(context))

    /**
     * Reads a per-car identity string, falling back to the LEGACY FLAT KEY.
     *
     * The fallback is permanent and deliberate, not a migration step. A one-shot
     * migration would have to pick a vehicle id to move the old profile onto, and
     * at app start the dongle often hasn't connected yet - so it would resolve to
     * `default`, and the moment the dongle DID connect the driver's companion would
     * vanish and revert to Zero. Reading through instead means an existing profile
     * keeps working under every id.
     *
     * It also gives the feature a sane default: the legacy profile is the companion
     * for any car that hasn't been given one of its own yet. Writes always go to
     * the per-car key, so setting up car A never disturbs car B.
     */
    private fun identityString(context: Context, base: String, vehicleId: String): String {
        val p = prefs(context)
        return p.getString(k(base, vehicleId), null) ?: p.getString(base, "").orEmpty()
    }

    private fun identityString(context: Context, base: String): String =
        identityString(context, base, ActiveVehicle.current(context))

    fun name(context: Context): String = identityString(context, KEY_NAME)

    /** The persona prompt fed into the system instruction (blank until onboarded). */
    fun persona(context: Context): String = identityString(context, KEY_PERSONA)

    /** The chosen prebuilt voice, or blank to use the Live session's default. */
    fun voice(context: Context): String = identityString(context, KEY_VOICE)

    /** The saved personality-picker selections, for re-editing the persona. */
    fun selections(context: Context): Map<String, PersonaSelection> =
        decodeSelections(identityString(context, KEY_TRAITS))

    /**
     * The assembled "Delivery notes" text ([assembleVoiceStyle]), fed into the
     * system instruction alongside [persona]. Blank until the driver has used
     * the voice-style picker, in which case delivery is whatever the chosen
     * [voice] preset does on its own.
     */
    fun voiceStyle(context: Context): String = identityString(context, KEY_VOICE_STYLE)

    /** The saved voice-style-picker selections, for re-editing. */
    fun voiceStyleSelections(context: Context): Map<String, PersonaSelection> =
        decodeSelections(identityString(context, KEY_VOICE_STYLE_TRAITS))

    /** Assembles and saves the voice-style picks (mirrors [savePersona]'s shape). */
    fun saveVoiceStyle(context: Context, selections: Map<String, PersonaSelection>) {
        prefs(context).edit()
            .putString(k(context, KEY_VOICE_STYLE), assembleVoiceStyle(selections))
            .putString(k(context, KEY_VOICE_STYLE_TRAITS), encodeSelections(selections))
            .apply()
    }

    // --- Explicit-vehicleId reads (car manager, 2026-07-16) -----------------
    //
    // The accessors above resolve the ACTIVE car, which is right for every prompt
    // and every session but useless for a ROSTER: CarsScreen has to render car #7's
    // companion while car #3 is selected. These read a named car without touching
    // the selection. Same legacy flat-key fallback, so a car that has never been
    // given its own identity shows the pre-car-profiles companion rather than
    // nothing.

    /** [name] for an explicit car, without changing the active selection. */
    fun nameFor(context: Context, vehicleId: String): String =
        identityString(context, KEY_NAME, vehicleId)

    /** [persona] for an explicit car. */
    fun personaFor(context: Context, vehicleId: String): String =
        identityString(context, KEY_PERSONA, vehicleId)

    /** [voice] for an explicit car. */
    fun voiceFor(context: Context, vehicleId: String): String =
        identityString(context, KEY_VOICE, vehicleId)

    /** [avatarId] for an explicit car. */
    fun avatarIdFor(vehicleId: String): String = "$AVATAR_ID:$vehicleId"

    /**
     * The avatar cache id for the active car (see [AvatarStudio]). Per-car, so each
     * car's face is its own set of files rather than every car sharing one.
     * [AVATAR_ID] remains as the legacy/global id that existing installs' art sits
     * under.
     */
    fun avatarId(context: Context): String = avatarIdFor(ActiveVehicle.current(context))

    /** Onboarding picker: stores the name, the assembled persona, and the raw picks. */
    fun savePersona(context: Context, name: String, selections: Map<String, PersonaSelection>) {
        prefs(context).edit()
            .putString(k(context, KEY_NAME), name.trim())
            .putString(k(context, KEY_PERSONA), assemblePersona(name, vehicleDesc = null, selections = selections))
            .putString(k(context, KEY_TRAITS), encodeSelections(selections))
            .apply()
    }

    /** Settings freeform editor: stores the name + persona text as typed. */
    fun saveProfile(context: Context, name: String, persona: String) {
        prefs(context).edit()
            .putString(k(context, KEY_NAME), name.trim())
            .putString(k(context, KEY_PERSONA), persona.trim())
            .apply()
    }

    /**
     * AI Profile editor: stores the name, the persona text exactly as shown (the
     * canonical system prompt - may be the assembled picker output OR a hand-edit),
     * and the raw picker [selections] so the quick-question picker still round-trips
     * for a later rebuild. Unlike [savePersona] this does NOT re-assemble the string
     * from selections, so a hand-edited prompt is never clobbered.
     */
    fun saveProfileFull(
        context: Context,
        name: String,
        persona: String,
        selections: Map<String, PersonaSelection>,
    ) {
        prefs(context).edit()
            .putString(k(context, KEY_NAME), name.trim())
            .putString(k(context, KEY_PERSONA), persona.trim())
            .putString(k(context, KEY_TRAITS), encodeSelections(selections))
            .apply()
    }

    fun saveVoice(context: Context, voiceName: String) {
        prefs(context).edit()
            .putString(k(context, KEY_VOICE), voiceName.trim())
            .apply()
    }

    /** Hashed spend passphrase (blank if none set). Storage only - logic in [SpendGate]. */
    fun spendPassphraseHash(context: Context): String =
        prefs(context).getString(KEY_SPEND_HASH, "").orEmpty()

    fun saveSpendPassphraseHash(context: Context, hash: String) {
        prefs(context).edit().putString(KEY_SPEND_HASH, hash).apply()
    }

    /**
     * User-supplied Gemini API key (stored encrypted via [KeyVault]; legacy
     * plaintext migrated to the encrypted slot on first read). Blank if not set
     * (falls back to BuildConfig in [GeminiKeyProvider]).
     */
    fun geminiKey(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_GEMINI_KEY_ENC, null)?.let { enc ->
            KeyVault.decrypt(enc)?.let { return it }
        }
        // Legacy plaintext (or Keystore-failure fallback): migrate if possible.
        val legacy = p.getString(KEY_GEMINI_KEY, "").orEmpty()
        if (legacy.isNotBlank()) {
            KeyVault.encrypt(legacy)?.let { enc ->
                p.edit().putString(KEY_GEMINI_KEY_ENC, enc).remove(KEY_GEMINI_KEY).apply()
            }
        }
        return legacy
    }

    fun saveGeminiKey(context: Context, key: String) {
        val trimmed = key.trim()
        val enc = KeyVault.encrypt(trimmed)
        prefs(context).edit().apply {
            if (enc != null) {
                putString(KEY_GEMINI_KEY_ENC, enc)
                remove(KEY_GEMINI_KEY)
            } else {
                // Keystore broken on this unit: plaintext beats a bricked key entry.
                putString(KEY_GEMINI_KEY, trimmed)
            }
        }.apply()
    }

    fun hasGeminiKey(context: Context): Boolean = geminiKey(context).isNotBlank()

    /**
     * User-supplied Shelly Cloud auth_key (stored encrypted via [KeyVault]).
     * Blank if not set - garage/gate control stays unconfigured and
     * [com.kevin.legion.vehicle.ShellyCloudOpener] throws
     * [com.kevin.legion.vehicle.GarageException.NotConfigured].
     */
    fun shellyAuthKey(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_SHELLY_AUTH_KEY_ENC, null)?.let { enc ->
            KeyVault.decrypt(enc)?.let { return it }
        }
        return p.getString(KEY_SHELLY_AUTH_KEY, "").orEmpty()
    }

    fun saveShellyAuthKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            prefs(context).edit().remove(KEY_SHELLY_AUTH_KEY_ENC).remove(KEY_SHELLY_AUTH_KEY).apply()
            return
        }
        val enc = KeyVault.encrypt(trimmed)
        prefs(context).edit().apply {
            if (enc != null) {
                putString(KEY_SHELLY_AUTH_KEY_ENC, enc)
                remove(KEY_SHELLY_AUTH_KEY)
            } else {
                // Keystore broken on this unit: plaintext beats a bricked key entry.
                putString(KEY_SHELLY_AUTH_KEY, trimmed)
            }
        }.apply()
    }

    fun hasShellyAuthKey(context: Context): Boolean = shellyAuthKey(context).isNotBlank()

    /**
     * User-supplied Spotify App Remote client ID (stored via [KeyVault]). Blank if
     * not set - Spotify stays disconnected and music routing keeps to phone BT /
     * mixtape. See [com.kevin.legion.media.SpotifyController].
     */
    fun spotifyClientId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_SPOTIFY_CLIENT_ID_ENC, null)?.let { enc ->
            KeyVault.decrypt(enc)?.let { return it }
        }
        return p.getString(KEY_SPOTIFY_CLIENT_ID, "").orEmpty()
    }

    /**
     * Saves the client ID, and **discards any stored tokens when the ID actually
     * changes** (2026-07-29).
     *
     * Why: OAuth tokens are bound to the client that minted them. Keeping a
     * refresh token across a client-ID swap made [SpotifyWebApi.isAuthorized]
     * report true on credentials the new client cannot use, so Setup's CONNECT
     * skipped re-authorization entirely, the next refresh 400'd on client
     * mismatch, and `play_music` silently lost its Web API search and fell back
     * to foregrounding the Spotify app - the exact thing the search path exists
     * to prevent. Clearing here (the single choke point every caller goes
     * through) makes `isAuthorized` false the moment the ID changes, which is
     * what re-arms the CONNECT flow.
     *
     * Only on a real change: re-saving the SAME id (Setup's SAVE/CONNECT buttons
     * both write unconditionally) must not log the driver out.
     */
    fun saveSpotifyClientId(context: Context, clientId: String) {
        val trimmed = clientId.trim()
        if (trimmed != spotifyClientId(context)) clearSpotifyTokens(context)
        if (trimmed.isBlank()) {
            prefs(context).edit().remove(KEY_SPOTIFY_CLIENT_ID_ENC).remove(KEY_SPOTIFY_CLIENT_ID).apply()
            return
        }
        val enc = KeyVault.encrypt(trimmed)
        prefs(context).edit().apply {
            if (enc != null) {
                putString(KEY_SPOTIFY_CLIENT_ID_ENC, enc)
                remove(KEY_SPOTIFY_CLIENT_ID)
            } else {
                // Keystore broken on this unit: plaintext beats a bricked entry.
                putString(KEY_SPOTIFY_CLIENT_ID, trimmed)
            }
        }.apply()
    }

    fun hasSpotifyClientId(context: Context): Boolean = spotifyClientId(context).isNotBlank()

    /** Spotify Web API access token (encrypted). Blank if never authorized. */
    fun spotifyAccessToken(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_ACCESS_TOKEN_ENC, null)
            ?.let { KeyVault.decrypt(it) }.orEmpty()

    /** Spotify refresh token (encrypted). Blank if never authorized. */
    fun spotifyRefreshToken(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_REFRESH_TOKEN_ENC, null)
            ?.let { KeyVault.decrypt(it) }.orEmpty()

    /** Epoch millis the access token expires at; 0 if unknown. */
    fun spotifyTokenExpiry(context: Context): Long =
        prefs(context).getLong(KEY_SPOTIFY_TOKEN_EXPIRY, 0L)

    /**
     * Stores a token pair. [refreshToken] blank leaves the stored one alone -
     * Spotify omits it on a refresh response, and dropping it would silently
     * force a full re-authorization on the next expiry.
     */
    fun saveSpotifyTokens(context: Context, accessToken: String, refreshToken: String, expiresInSec: Long) {
        prefs(context).edit().apply {
            KeyVault.encrypt(accessToken.trim())?.let { putString(KEY_SPOTIFY_ACCESS_TOKEN_ENC, it) }
            if (refreshToken.isNotBlank()) {
                KeyVault.encrypt(refreshToken.trim())?.let { putString(KEY_SPOTIFY_REFRESH_TOKEN_ENC, it) }
            }
            // 60s of slack so a token that expires mid-request is treated as stale.
            putLong(KEY_SPOTIFY_TOKEN_EXPIRY, System.currentTimeMillis() + (expiresInSec - 60) * 1000L)
        }.apply()
    }

    fun clearSpotifyTokens(context: Context) {
        prefs(context).edit()
            .remove(KEY_SPOTIFY_ACCESS_TOKEN_ENC)
            .remove(KEY_SPOTIFY_REFRESH_TOKEN_ENC)
            .remove(KEY_SPOTIFY_TOKEN_EXPIRY)
            .apply()
    }

    /**
     * True once the very first Live session has successfully connected. Used to
     * gate the one-time spoken introduction so it fires exactly once and never
     * again on subsequent cold starts.
     */
    fun isFirstSessionDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_SESSION_DONE, false)

    fun markFirstSessionDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_SESSION_DONE, true).apply()
    }

    /**
     * True once the driver has connected their own Google Drive for cross-device
     * sync (S1). Off by default; a driver who never connects keeps every byte
     * on-device. See [com.kevin.legion.sync.SyncCapability].
     */
    fun isSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYNC_ENABLED, false)

    fun setSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    /** Wipes the companion's identity (name, persona, picks, voice). Does NOT clear the API key. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_NAME).remove(KEY_PERSONA).remove(KEY_TRAITS).remove(KEY_VOICE).remove(KEY_SPEND_HASH)
            .remove(KEY_VOICE_STYLE).remove(KEY_VOICE_STYLE_TRAITS)
            .apply()
    }
}
