package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.CompanionProfile

/**
 * App-facing orchestrator for garage/gate control - the seam between
 * [GaragePreferences] (what's configured), [GarageOpener] (how a pulse is
 * actually sent), and the two call sites that trigger one:
 * [com.kevin.legion.service.LiveToolbox]'s activate_garage voice tool and
 * [com.kevin.legion.ui.GarageSheet]'s tap-to-activate UI.
 *
 * [activate] itself has no confirm gate - it just pulses. Confirmation is
 * each call site's own responsibility (the voice tool's confirmed=false/true
 * round trip; the sheet's tap-to-open AlertDialog), so a UI path that already
 * confirmed via its own dialog isn't forced through a second, redundant gate.
 */
object GarageController {
    // Lazy: no network call until the first activation.
    private val opener: GarageOpener by lazy { ShellyCloudOpener() }

    /**
     * Fully set up = the Shelly account creds are saved AND at least one
     * enabled door exists. Checking doors alone isn't enough: a driver can add
     * a door before saving the auth key/server, and without this the Cruise tab
     * would open the sheet (which then fails on the first ACTIVATE) instead of
     * routing to Setup to finish account setup.
     */
    fun isConfigured(context: Context): Boolean =
        CompanionProfile.hasShellyAuthKey(context) &&
            !GaragePreferences.serverHost(context).isNullOrBlank() &&
            configuredDoors(context).isNotEmpty()

    /** Enabled doors only - what the voice tool and [com.kevin.legion.ui.GarageSheet] offer. */
    fun configuredDoors(context: Context): List<GarageDoorConfig> =
        GaragePreferences.doors(context).filter { it.enabled }

    /**
     * Pure door-name resolution, no Context/Android - unit-tested directly in
     * GarageLogicTest.
     *
     * - Blank name: the default door if one's set and still enabled, else the
     *   lone door if there's exactly one, else null ("ask which").
     * - Non-blank name: fuzzy match on friendlyName, checked in tiers (exact,
     *   then prefix, then substring) - the first tier with exactly ONE hit
     *   wins. A tier with more than one hit is ambiguous and resolves to null
     *   rather than falling through to a looser tier that might paper over it.
     *   No hit in any tier is also null. Null always means "ask the driver
     *   which door".
     */
    fun resolveDoor(doors: List<GarageDoorConfig>, defaultDoorId: String?, nameOrNull: String?): GarageDoorConfig? {
        if (doors.isEmpty()) return null
        val name = nameOrNull?.trim().orEmpty()
        if (name.isBlank()) {
            return doors.firstOrNull { it.id == defaultDoorId } ?: doors.singleOrNull()
        }
        val exact = doors.filter { it.friendlyName.equals(name, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val prefix = doors.filter { it.friendlyName.startsWith(name, ignoreCase = true) }
        if (prefix.isNotEmpty()) return prefix.singleOrNull()
        val contains = doors.filter { it.friendlyName.contains(name, ignoreCase = true) }
        return contains.singleOrNull()
    }

    /** The raw single-door pulse - see the class KDoc on why there's no confirm check here. */
    suspend fun activate(context: Context, door: GarageDoorConfig) = opener.activate(context, door)

    /** Result of [dispatchVoiceActivate]: what to tell the driver, and whether the pulse actually fired. */
    data class VoiceActivateResult(val success: Boolean, val message: String)

    /**
     * The activate_garage voice tool's full confirm-gate + resolution logic,
     * factored out of [com.kevin.legion.service.LiveToolbox] so it's
     * unit-testable without Context/Android (see GarageLogicTest). [activate]
     * is the actual pulse action, injected so tests can substitute a fake and
     * assert it's called only when [confirmed] is true.
     *
     * Never says "opening"/"closing" - a handheld remote is a single-button
     * toggle, so the driver-facing language is always "trigger"/"hit".
     */
    suspend fun dispatchVoiceActivate(
        doors: List<GarageDoorConfig>,
        defaultDoorId: String?,
        doorName: String?,
        confirmed: Boolean,
        activate: suspend (GarageDoorConfig) -> Unit,
    ): VoiceActivateResult {
        if (doors.isEmpty()) {
            return VoiceActivateResult(
                success = false,
                message = "You haven't set up a garage door yet - add one in Settings first.",
            )
        }
        val door = resolveDoor(doors, defaultDoorId, doorName)
            ?: return VoiceActivateResult(
                success = false,
                message = "Which door - ${doors.joinToString(", ") { it.friendlyName }}?",
            )
        if (!confirmed) {
            return VoiceActivateResult(
                success = false,
                message = "Ask the driver to confirm before I trigger the ${door.friendlyName} door.",
            )
        }
        return try {
            activate(door)
            VoiceActivateResult(success = true, message = "Triggered the ${door.friendlyName} door.")
        } catch (e: GarageException) {
            VoiceActivateResult(success = false, message = exceptionMessage(e))
        }
    }

    private fun exceptionMessage(e: GarageException): String = when (e) {
        is GarageException.NotConfigured -> e.message ?: "You haven't set up a garage relay yet - set it up in Settings first."
        is GarageException.Offline -> e.message ?: "I couldn't reach the garage relay."
        is GarageException.DeviceError -> e.message ?: "The garage relay reported an error."
    }
}
