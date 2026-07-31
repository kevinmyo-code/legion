package com.kevin.legion.vehicle

import android.content.Context

/**
 * A single garage/gate door controlled through a Shelly Gen2+ relay. A
 * handheld garage remote is a single-button toggle - without a door sensor
 * there is no way to know or promise direction, so [enabled] doors only ever
 * get pulsed (see [GarageOpener.activate]), never asked for status.
 * [deviceId] is the Shelly Cloud device id (Shelly app: Device -> Settings ->
 * Device Info); [relayId] is that device's switch channel (0 on single-relay
 * devices like the Shelly Plus 1 / 1 Gen4 this feature targets).
 */
data class GarageDoorConfig(
    val id: String,
    val deviceId: String,
    val relayId: Int = 0,
    val friendlyName: String,
    val enabled: Boolean,
)

/**
 * Failure modes [GarageOpener.activate] can throw, distinct enough for
 * [com.kevin.legion.service.LiveToolbox] and the UI to phrase differently
 * rather than a single generic "couldn't reach it".
 */
sealed class GarageException(message: String) : Exception(message) {
    /** No account credentials saved, or the API rejected them (bad/revoked auth_key). */
    class NotConfigured(
        message: String = "That garage relay isn't set up yet - configure it in Settings first.",
    ) : GarageException(message)

    /** Transport failure - no network, request timeout, or the cloud API unreachable. */
    class Offline(
        message: String = "I couldn't reach the garage relay - check your hotspot connection.",
    ) : GarageException(message)

    /** The API answered but reported a problem (device offline, bad deviceId/channel, error body). */
    class DeviceError(message: String) : GarageException(message)
}

/**
 * The one-brand seam for garage/gate control - action-only, BYO-hardware
 * credentials, no Kevin-hosted server in the loop (see CLAUDE.md sec 3/9).
 * v1 intentionally has no status read: a handheld remote is a single-button
 * toggle, so [activate] is a momentary trigger pulse and nothing else. A
 * later reed-switch build on the same Shelly would add a `status()` method
 * here rather than replacing this one.
 *
 * [ShellyCloudOpener] is the wired implementation today (Shelly Gen2+ Cloud
 * Control API v2): the relay is mounted AT THE GARAGE on home WiFi, wired
 * across the opener's wall-button terminals, and reached over the phone
 * hotspot through Shelly's cloud rather than a car-local radio. This is a
 * deliberate change from the feature's original BLE design (car-local, no
 * cloud) - the relay isn't in the car, so a car-local radio can never reach
 * it. [ShellyBleOpener] remains in the tree, kept behind this seam but
 * unwired, as a possible future no-cloud transport for a relay mounted
 * in-car instead. Implementations own their own connection lifecycle
 * internally - connect/auth, pulse, disconnect - rather than exposing
 * connect()/close() to the caller, since a garage trigger is a rare one-shot
 * action rather than a continuous telemetry feed like
 * [ObdBluetoothManager]'s persistent link.
 */
interface GarageOpener {
    /**
     * Sends the momentary trigger pulse to [door]'s relay. Throws
     * [GarageException] on failure. Has no notion of confirmation - the
     * confirm gate belongs to each call site (see
     * [com.kevin.legion.service.LiveToolbox]'s activate_garage tool and
     * [com.kevin.legion.ui.GarageSheet]'s confirm dialog), not here.
     */
    suspend fun activate(context: Context, door: GarageDoorConfig)
}
