package com.kevin.legion.car

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Wave 2 of the Android Auto probe harness (`.scratch/android-auto/issues/14-*`) - the Telecom
 * registration and call-placement half of the self-managed call probe. [LegionConnectionService]
 * is the other half; this object is what [com.kevin.legion.ui.CarProbeScreen]'s three buttons
 * ("Register account", "Place call", "End call") actually call.
 *
 * **Every path here degrades to a [CarProbeLog] entry, never a crash.** "A probe that crashes in
 * the car teaches nothing" (ticket 14) - registration and call placement both touch a system
 * service Kevin does not control on a phone he is driving with, so both are wrapped.
 */
object CarCallProbe {

    /**
     * Probe-only destination. `TelecomManager.placeCall` requires a `tel:`/`sip:`-shaped `Uri`
     * even for a self-managed call with no real remote party - this is never dialled for real,
     * [LegionConnectionService] answers it locally. Logged verbatim on every call, per ticket 14.
     */
    private const val PROBE_ADDRESS = "tel:5550100"

    private const val ACCOUNT_ID = "legion-car-probe-account"

    /** Built fresh from [context] every call rather than cached - cheap, and avoids holding an Application-scoped reference to whatever Context first called in. */
    private fun accountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, LegionConnectionService::class.java), ACCOUNT_ID)

    private fun telecomManager(context: Context): TelecomManager =
        context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    /** Registers the probe's `PhoneAccount` with `CAPABILITY_SELF_MANAGED`. Must run before [placeCall]. */
    fun registerAccount(context: Context) {
        try {
            val handle = accountHandle(context)
            val account = PhoneAccount.builder(handle, "LEGION car probe")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager(context).registerPhoneAccount(account)
            CarProbeLog.log("CarCallProbe", "registerPhoneAccount OK handle=$handle")
        } catch (t: Throwable) {
            CarProbeLog.log("CarCallProbe", "registerPhoneAccount FAILED ${t::class.simpleName}: ${t.message}")
        }
    }

    /**
     * Logs `isOutgoingCallPermitted` BEFORE placing the call, per ticket 14 - research 01 flags
     * that Telecom is documented to refuse a self-managed call while another `ConnectionService`
     * has an ongoing call, or during an emergency call, but flags that wording as needing
     * re-verification against platform source. Surfacing the real return value on device is worth
     * more than trusting the doc mirror.
     */
    fun placeCall(context: Context) {
        try {
            val manager = telecomManager(context)
            val handle = accountHandle(context)
            val permitted = manager.isOutgoingCallPermitted(handle)
            CarProbeLog.log("CarCallProbe", "isOutgoingCallPermitted=$permitted handle=$handle")

            val address = Uri.parse(PROBE_ADDRESS)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            CarProbeLog.log("CarCallProbe", "placeCall address=$address extras.accountHandle=$handle")
            manager.placeCall(address, extras)
        } catch (t: Throwable) {
            CarProbeLog.log("CarCallProbe", "placeCall FAILED ${t::class.simpleName}: ${t.message}")
        }
    }

    /**
     * Ends whatever [LegionConnectionService.activeConnection] is up. There is deliberately no
     * Telecom-level "end this account's calls" call here - the connection itself is the only
     * handle a self-managed `ConnectionService` has to disconnect its own call.
     */
    fun endCall() {
        try {
            val connection = LegionConnectionService.activeConnection
            if (connection == null) {
                CarProbeLog.log("CarCallProbe", "endCall - no active connection")
                return
            }
            connection.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            connection.destroy()
            if (LegionConnectionService.activeConnection === connection) {
                LegionConnectionService.activeConnection = null
            }
            CarProbeLog.log("CarCallProbe", "endCall - disconnected local connection")
        } catch (t: Throwable) {
            CarProbeLog.log("CarCallProbe", "endCall FAILED ${t::class.simpleName}: ${t.message}")
        }
    }
}
