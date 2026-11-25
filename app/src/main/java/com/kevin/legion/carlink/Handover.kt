package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.HandoverBody
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Mints the unit's sign-in for the car [vehicleId] (null = none chosen), once per pairing.
     *
     * **Bounded, because the pairing waits for it.** The caller awaits this inside the pairing
     * conversation (`PairingRunner.onStage` at `DigitsShown`), which is what keeps the token from
     * landing after the frame it belongs in - and the unit drops the whole stage after
     * `PairingSession.STAGE_LIFETIME_MS`, 2 minutes, while the person is still reading six digits
     * off the car screen. A mint on a weak signal costs the HTTP client's connect plus read
     * timeouts, so without a bound a slow engine would spend the person's window rather than its
     * own. Past [MINT_TIMEOUT_MS] the pairing goes on with no token: a link with no engine sign-in
     * is worth far more than a failed pairing, and a later re-pair mints one.
     */
    suspend fun prepare(vehicleId: String?) {
        vehicle = vehicleId
        if (minted != null) return
        minted = withTimeoutOrNull(MINT_TIMEOUT_MS) { tokens.mint(UNIT_NAME, vehicleId) }
            ?.also { redact(it.token) }
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

    /**
     * The pairing failed: revoke a token minted for it, so none is left live on the engine.
     *
     * **Bounded for the same reason [prepare] is.** This runs in the pairing's `finally`, under
     * `NonCancellable`, and the person's next "Pair" tap does nothing until it returns. The HTTP
     * client sets no timeouts of its own, so on a weak signal an unbounded revoke would spend tens
     * of seconds in silence on exactly the retry path a failed pairing leads to. Past
     * [REVOKE_TIMEOUT_MS] the credential is left to expire on its own instead, the lesser cost.
     */
    suspend fun discard() {
        minted?.let { token -> withTimeoutOrNull(REVOKE_TIMEOUT_MS) { tokens.revoke(token.id) } }
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

        /**
         * How long [prepare] may spend minting before the pairing goes on without a token. Well
         * inside the unit's 2-minute stage lifetime and the phone's own 135 s digits wait, and
         * comfortably over one HTTP round trip on a hotspot.
         */
        const val MINT_TIMEOUT_MS = 20_000L

        /** How long [discard] may spend revoking before the pairing's failure is reported anyway. */
        const val REVOKE_TIMEOUT_MS = 10_000L
    }
}
