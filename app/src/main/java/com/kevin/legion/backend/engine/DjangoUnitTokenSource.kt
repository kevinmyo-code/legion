package com.kevin.legion.backend.engine

import android.util.Log
import com.kevin.legion.carlink.UnitRecord
import com.kevin.legion.carlink.UnitToken
import com.kevin.legion.carlink.UnitTokenDirectory
import com.kevin.legion.carlink.UnitTokenSource
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private const val HEAD_UNITS = "/api/auth/head-units"
private const val KIND = "head_unit"

/**
 * The real [UnitTokenSource] (T3b break 1, 2026-11-24): the phone mints the HEAD UNIT's own engine
 * sign-in on the member's own session while it pairs, and hands it over the car link. It replaces
 * `UnitTokenNotYetAvailable`, whose `mint` returned null, which is why every pairing until today
 * handed over `engine_token: null` and left the unit unable to read even its own release.
 *
 * Three routes, each on the phone's own device token through [EngineHttp] (`POST /api/auth/login`'s
 * token, the same one every other `Django*Backend` here uses): `POST /api/auth/head-units` to mint,
 * `POST .../<id>/rotate` to replace a live one, `DELETE .../<id>` for "Forget head unit". The
 * engine refuses all three to a browser session, a recruiter and a head-unit token, so this is the
 * only client that can call them.
 *
 * **Every failure is null and one fixed log line, never the engine's body.** A mint's 2xx body
 * carries the token itself, and a decode exception's message can quote the text it choked on, so
 * nothing here logs a body or an exception message: the status and the route are enough to tell
 * "the engine said no" from "the phone never reached it", and the person sees the hand-over's own
 * words on the pairing screen instead. The branches that matter in practice:
 *
 * - **404.** An engine that does not serve these routes. Until 2026-11-24 that meant
 *   `LEGION_HEADUNIT_SIGNIN` was unset; the switch is gone now, but a rollback or an engine behind
 *   this build answers the same way, so the branch stays.
 * - **401 or 403.** This phone's own sign-in is gone, or it is a demo visitor. Nothing to retry.
 * - **429.** Five mints an hour per member, six rotations an hour per unit. A person re-pairing in
 *   a loop hits it; the pairing then hands over no token rather than failing.
 * - **400.** A `vehicle_id` the household does not have (a car whose engine row was deleted).
 * - **Unreachable.** Pairing happens on the phone's own hotspot with the unit, where the engine may
 *   well be out of reach. That is exactly why a failed mint must not fail the PAIRING: the link
 *   itself is what the person asked for, and a later re-pair with signal mints the token.
 *
 * **[revoke] answers true for a 404.** "No live head unit of yours has that id" is the outcome the
 * caller asked about: it is gone either way, and reporting false would make Forget look failed.
 */
class DjangoUnitTokenSource(private val http: EngineHttp) : UnitTokenSource, UnitTokenDirectory {

    override suspend fun mint(name: String, vehicleId: String?): UnitToken? {
        val body = JsonObject(
            buildMap {
                put("name", JsonPrimitive(name))
                put("kind", JsonPrimitive(KIND))
                if (vehicleId != null) put("vehicle_id", JsonPrimitive(vehicleId))
            },
        ).toString()
        return issued("mint", http.post(HEAD_UNITS, body))
    }

    /**
     * The member's own live units (5.6): id, when the token runs out and which car it is bound to.
     * Null when the engine could not be asked, which is never the same as "this member has none":
     * the renewal must not treat an unreachable engine as a revoked unit.
     */
    override suspend fun list(): List<UnitRecord>? {
        val result = http.get(HEAD_UNITS)
        val ok = result.getOrNull()
            ?: run {
                Log.w(TAG, "list refused: ${status((result.exceptionOrNull() as? EngineHttpException)?.failure)}")
                return null
            }
        return try {
            engineSyncedJson.parseToJsonElement(ok.body).jsonArray.mapNotNull { row ->
                val o = row.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                UnitRecord(
                    id = id,
                    expiresAtMs = millis(o["expires_at"]?.jsonPrimitive?.contentOrNull),
                    vehicleId = o["vehicle_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: SerializationException) {
            Log.w(TAG, "list reply did not decode")
            null
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "list reply did not decode")
            null
        }
    }

    /**
     * An ISO-8601 instant as milliseconds, or null when it is absent or in a shape this build does
     * not read. Null means "do not renew on this answer", never "renew now": a date nobody parsed
     * is not a reason to mint a credential every few hours.
     */
    private fun millis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            Log.w(TAG, "list gave an expiry this build does not read")
            null
        }
    }

    override suspend fun rotate(id: String): UnitToken? =
        issued("rotate", http.post("$HEAD_UNITS/${id.encodeURLPathPart()}/rotate", "{}"))

    override suspend fun revoke(id: String): Boolean {
        val result = http.delete("$HEAD_UNITS/${id.encodeURLPathPart()}")
        val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
        // A 404 counts as gone: "no live head unit of yours has that id" is the outcome Forget
        // asked about, and answering false would make a Forget that worked look failed.
        val gone = result.isSuccess || (failure is EngineFailure.Refused && failure.status == NOT_FOUND)
        if (!gone) Log.w(TAG, "revoke refused: ${status(failure)}")
        return gone
    }

    /** The one place a `{id, token, expires}` reply becomes a [UnitToken], or null and a log line. */
    private fun issued(what: String, result: Result<EngineOk>): UnitToken? {
        val ok = result.getOrNull()
            ?: run {
                Log.w(TAG, "$what refused: ${status((result.exceptionOrNull() as? EngineHttpException)?.failure)}")
                return null
            }
        return try {
            val row = engineSyncedJson.parseToJsonElement(ok.body).jsonObject
            val token = row["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val id = row["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (token == null || id == null) {
                Log.w(TAG, "$what reply named no id or token")
                null
            } else {
                UnitToken(id = id, token = token, expires = row["expires"]?.jsonPrimitive?.contentOrNull)
            }
        } catch (_: SerializationException) {
            // Deliberately not `e.message`: it quotes the body it choked on, and that body is the
            // one place the token appears.
            Log.w(TAG, "$what reply did not decode")
            null
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "$what reply did not decode")
            null
        }
    }

    /** The status alone, never the engine's sentence: a refusal body may echo what was sent. */
    private fun status(failure: EngineFailure?): String = when (failure) {
        is EngineFailure.Refused -> "HTTP ${failure.status}"
        is EngineFailure.Unauthorized -> "HTTP ${failure.status}, this phone's own sign-in"
        is EngineFailure.Unreachable -> "the engine was not reachable"
        is EngineFailure.Malformed -> "the reply did not decode"
        null -> "no reason given"
    }

    private companion object {
        const val TAG = "UnitToken"
        const val NOT_FOUND = 404
    }
}
