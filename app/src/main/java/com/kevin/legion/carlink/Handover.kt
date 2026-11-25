package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.HandoverBody

/**
 * A sign-in the engine minted FOR the head unit (5.1): `{id, token, expires}`, shown once. Never the
 * phone's own token.
 */
class UnitToken(val id: String, val token: String, val expires: String?) {
    override fun toString() = "UnitToken(redacted)"
}

/**
 * The engine's head-unit routes (5.1, 5.2, 5.4), each called with the member's own write-scoped device
 * token:
 * - [mint]: `POST /api/auth/head-units` `{"name", "kind": "head_unit", "vehicle_id"}`;
 * - [rotate]: `POST /api/auth/head-units/<id>/rotate` (a member action from the phone; the new token
 *   goes to the unit over the link);
 * - [revoke]: `DELETE /api/auth/head-units/<id>`.
 * A token lives 30 days and never past 90 days from pairing (the engine enforces both).
 * [com.kevin.legion.backend.engine.DjangoUnitTokenSource] is the one implementation; every method
 * answers null or false rather than throwing, so a mint the engine refuses never fails the pairing.
 */
interface UnitTokenSource {
    suspend fun mint(name: String, vehicleId: String?): UnitToken?
    suspend fun rotate(id: String): UnitToken?
    suspend fun revoke(id: String): Boolean
}

/** One of the household's cars, for "which car is this head unit in" (the engine binds ONE). */
data class VehicleOption(val id: String, val label: String)

/** What the hand-over can carry that the phone already has. Null or blank = none. */
class HandoverSources(
    /** The phone's own Gemini key, as the app uses it at runtime (5.3 rev 8). Never a build-baked key. */
    val geminiKey: () -> String?,
    /** The phone's public Mapbox token as the app uses it at runtime (pasted in the app, else built in). */
    val mapboxToken: () -> String?,
    val phoneName: () -> String?,
    /**
     * The driver's own Spotify client ID (Kevin, 2026-11-23: the car link should carry it, so the
     * head unit's Spotify setup does not have to be typed in again). Public by Spotify's own
     * design, and the only Spotify credential either app holds: both use PKCE, so there is no
     * client secret, and the phone's own Spotify TOKENS are never sent (the two apps sign in as
     * different accounts on purpose).
     */
    val spotifyClientId: () -> String? = { null },
)

/** What the hand-over will NOT carry because this phone does not have it; said in words. */
enum class HandoverGap { NO_GEMINI_KEY, NO_MAP_TOKEN, NO_SPOTIFY_CLIENT_ID }

/**
 * What the phone hands over (3.5) for one pairing. The unit token is minted only once the unit has
 * proven itself (after a verified `ack`), for the car the person chose, and revoked again if the
 * pairing then fails. The Gemini key and the map token are the phone's own (5.3 rev 8). Every value
 * is registered with the log redactor before it is used, and none is ever shown.
 */
class HandoverPlan(
    private val sources: HandoverSources,
    private val tokens: UnitTokenSource,
    private val redact: (String) -> Unit,
) {
    @Volatile private var minted: UnitToken? = null
    @Volatile private var vehicle: String? = null

    /** Mints the unit's sign-in for the car [vehicleId] (null = none chosen), once per pairing. */
    suspend fun prepare(vehicleId: String?) {
        vehicle = vehicleId
        if (minted != null) return
        minted = tokens.mint(UNIT_NAME, vehicleId)?.also { redact(it.token) }
    }

    fun body(): HandoverBody {
        val token = minted
        return HandoverBody(
            engineToken = token?.token,
            geminiKey = present(sources.geminiKey())?.also(redact),
            mapboxToken = present(sources.mapboxToken())?.also(redact),
            engineVehicleId = vehicle,
            phoneName = sources.phoneName(),
            // Not redacted: a client ID is public, and a log redactor that hides it would make the
            // one field anyone may paste into a bug report unreadable.
            spotifyClientId = present(sources.spotifyClientId()),
        )
    }

    /** What this phone does not have to hand over, for the screen's words. */
    fun gaps(): Set<HandoverGap> = buildSet {
        if (present(sources.geminiKey()) == null) add(HandoverGap.NO_GEMINI_KEY)
        if (present(sources.mapboxToken()) == null) add(HandoverGap.NO_MAP_TOKEN)
        if (present(sources.spotifyClientId()) == null) add(HandoverGap.NO_SPOTIFY_CLIENT_ID)
    }

    private fun present(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** The minted token's engine id, kept so "Forget head unit" can revoke it (5.4). */
    fun mintedId(): String? = minted?.id

    /** The pairing failed: revoke a token minted for it, so none is left live on the engine. */
    suspend fun discard() {
        minted?.let { tokens.revoke(it.id) }
        minted = null
    }

    /** The pairing held: the token now belongs to the unit. */
    fun keep() {
        minted = null
    }

    suspend fun revoke(id: String): Boolean = tokens.revoke(id)

    companion object {
        /** 5.1's device name, listed as "Head unit" in the phone's device list. */
        const val UNIT_NAME = "Head unit"
    }
}
