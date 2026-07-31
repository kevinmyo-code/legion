package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.CompanionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds the Shelly Cloud Control API v2 "set switch" URL against the
 * account's own server host - never a shared/global host. [serverHost] and
 * [authKey] both come from the Shelly app (User Settings -> Authorization
 * cloud key -> Get key shows both on the same screen). Pure/no I/O so it's
 * unit-testable without a network (see GarageLogicTest).
 */
fun buildSetSwitchUrl(serverHost: String, authKey: String): String =
    "https://$serverHost/v2/devices/api/set/switch?auth_key=$authKey"

/**
 * Builds the JSON body for a Shelly Cloud "set switch" call. [deviceId] is
 * passed through as a string exactly as copied from the Shelly app - the API
 * accepts either shape, and string is the safe default since not every
 * account's device id is purely numeric. Pure/no I/O, see [buildSetSwitchUrl].
 */
fun buildSetSwitchBody(deviceId: String, channel: Int, on: Boolean): String =
    JSONObject()
        .put("id", deviceId)
        .put("channel", channel)
        .put("on", on)
        .toString()

/**
 * Fires a momentary relay pulse: [sendOn], hold for [pulseMs], then [sendOff].
 * The release is ALWAYS attempted - even if the hold is cancelled (sheet
 * dismissed, app backgrounded, scope torn down) AND even if [sendOn] itself
 * throws. That second case matters: over the flaky hotspot -> cloud -> home-WiFi
 * path the "on" call can fail its response read AFTER the relay already
 * actuated, so "sendOn threw" does NOT reliably mean "not engaged". Sending off
 * is idempotent (a no-op on an already-open relay), so a spurious release is
 * harmless, while a skipped one could latch a real wall-button contact closed.
 * The release is best-effort: a [sendOff] failure is logged, never thrown, and
 * the original [sendOn] exception (if any) still propagates. Android-free except
 * the failure log, so it's unit-testable (see GarageLogicTest).
 *
 * This does NOT survive process death during the hold (a low-memory kill
 * between [sendOn] and [sendOff] skips the finally entirely); guarding that
 * would need a foreground-service/WorkManager-backed release, out of scope here.
 */
suspend fun pulseRelay(
    pulseMs: Long,
    sendOn: suspend () -> Unit,
    sendOff: suspend () -> Unit,
) {
    try {
        sendOn()
        delay(pulseMs)
    } finally {
        withContext(NonCancellable) {
            runCatching { sendOff() }
                .onFailure { Log.e("ShellyCloudOpener", "Failed to release garage relay pulse", it) }
        }
    }
}

/**
 * [GarageOpener] over the Shelly Cloud Control API (v2 set/switch). Unlike
 * [ShellyBleOpener]'s car-local BLE, this relay lives AT THE GARAGE - wired
 * across the opener's wall-button terminals, powered there, and sitting on
 * home WiFi - so the head unit has to reach it through Shelly's cloud over
 * the phone's hotspot rather than any radio the car itself has. Verified
 * protocol, do not change without re-checking against Shelly's docs:
 *
 *  - Auth: an `auth_key` (Shelly app -> User Settings -> Authorization cloud
 *    key -> Get key) plus that same screen's server host
 *    (e.g. `shelly-12-eu.shelly.cloud`) - both pasted once in
 *    [com.kevin.legion.ui.GarageSettings]. The auth_key is a secret
 *    (encrypted via [CompanionProfile.saveShellyAuthKey]/[KeyVault]); the
 *    server host is not and lives in [GaragePreferences].
 *  - Device id + channel: Shelly app -> Device -> Settings -> Device Info,
 *    entered per door in Setup.
 *  - Control: `POST https://<server_host>/v2/devices/api/set/switch?auth_key=<key>`,
 *    JSON body `{"id": <deviceId>, "channel": <channel>, "on": <bool>}`.
 *
 * A trigger pulse is `on:true`, wait ~1s, then `on:false` - this guarantees a
 * clean momentary press and resets the contact for the next activation
 * regardless of the relay's own auto-off config, the cloud equivalent of
 * [ShellyBleOpener]'s BLE `toggle_after` framing. Mirrors a handheld remote's
 * single-button toggle (see [GarageOpener]'s KDoc on why there's no
 * direction/status).
 *
 * A legacy form-encoded `/device/relay/control` endpoint exists in Shelly's
 * docs as a fallback but is deliberately NOT implemented here - v2 is the one
 * Kevin verified end-to-end; the legacy shape should be added only after
 * re-verifying it against a real account, not guessed at.
 */
class ShellyCloudOpener : GarageOpener {

    override suspend fun activate(context: Context, door: GarageDoorConfig) = withContext(Dispatchers.IO) {
        val authKey = CompanionProfile.shellyAuthKey(context)
        val serverHost = GaragePreferences.serverHost(context)
        if (authKey.isBlank() || serverHost.isNullOrBlank()) {
            throw GarageException.NotConfigured(
                "Shelly cloud isn't set up yet - paste your auth key and server address in Settings first."
            )
        }
        pulseRelay(
            pulseMs = PULSE_MS,
            sendOn = { setSwitch(serverHost, authKey, door, on = true) },
            sendOff = { setSwitch(serverHost, authKey, door, on = false) },
        )
    }

    /** One set/switch call. Throws a typed [GarageException] on any failure. */
    private fun setSwitch(serverHost: String, authKey: String, door: GarageDoorConfig, on: Boolean) {
        val connection = (URL(buildSetSwitchUrl(serverHost, authKey)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            connection.outputStream.use {
                it.write(buildSetSwitchBody(door.deviceId, door.relayId, on).toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            when {
                code == 401 || code == 403 -> throw GarageException.NotConfigured(
                    "That Shelly auth key was rejected - re-copy it from the Shelly app in Settings."
                )
                code >= 400 -> {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.e(TAG, "Shelly cloud error $code: $err")
                    throw GarageException.DeviceError(
                        err.ifBlank { "The Shelly cloud API returned an error (code $code)." }
                    )
                }
                else -> checkBody(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: GarageException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Shelly cloud request failed: ${e.message}", e)
            throw GarageException.Offline()
        } finally {
            connection.disconnect()
        }
    }

    /** A 200 response can still carry a device-side error (relay offline, bad channel, etc). */
    private fun checkBody(body: String) {
        if (body.isBlank()) return // Some accounts return an empty 200; treat as success.
        // A non-blank body that isn't JSON is a masked failure, not a success:
        // a captive portal / proxy HTML page on the hotspot returns 200 with an
        // HTML body, and silently reporting "Triggered" there would be a lie.
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw GarageException.DeviceError(
                "Got an unreadable response from the garage - check your hotspot connection and try again."
            )
        if (!json.optBoolean("isok", true)) {
            val detail = json.optJSONObject("errors")?.toString()
                ?: json.optString("error_message").ifBlank { null }
                ?: "The Shelly relay reported an error."
            throw GarageException.DeviceError(detail)
        }
    }

    companion object {
        private const val TAG = "ShellyCloudOpener"

        // Matches the momentary press a handheld remote gives - long enough for
        // the opener to register a clean edge, short enough to feel instant.
        private const val PULSE_MS = 1_000L
    }
}
