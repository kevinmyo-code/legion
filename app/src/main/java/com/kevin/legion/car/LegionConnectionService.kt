package com.kevin.legion.car

import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

/**
 * Wave 2 of the Android Auto probe harness (`.scratch/android-auto/issues/14-*`) - the bare
 * `ConnectionService` half of "does gearhead draw a self-managed call". Everything this class
 * does is diagnostic: it stands the call up (`setAudioModeIsVoip(true)`, `setDialing()`,
 * `setActive()`) and then logs every lifecycle callback Telecom fires, so [CarProbeLog] captures
 * whatever Kevin cannot see with his own eyes on the head unit tonight.
 *
 * **This is not the real design.** No Gemini, no audio capture, no playback, no Car App Library -
 * ticket 07 (which real design this becomes, if any) has not been decided, and building ahead of
 * it would pre-empt that call. See the ticket and research 01/04 for why the shape is this small.
 *
 * **Never block a callback.** Research 01 found two hard 5-second Telecom budgets: the
 * notification after `addCall`, and every remote-surface callback. Every override below only logs
 * and flips state - no network call, no disk I/O, nothing that can stall past that budget.
 *
 * **Never touch audio routing directly.** Research 04 is explicit: `setCommunicationDevice` and
 * `startBluetoothSco` are forbidden once Telecom owns the call - "doing so will cause audio issues
 * in your call." [onCallAudioStateChanged] only reads and logs the state Telecom already picked;
 * it never calls `requestBluetoothAudio` or `setAudioRoute`. If E2 shows the wrong route, that is
 * a finding for ticket 07, not something to work around here.
 */
class LegionConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        CarProbeLog.log(
            "LegionConnectionService",
            "onCreateOutgoingConnection address=${request?.address} account=$connectionManagerPhoneAccount",
        )
        val connection = ProbeConnection()
        // Order matches the platform's own VoIPConnection example (research 01, Q5): declare the
        // VoIP audio mode before moving through DIALING to ACTIVE. This is a probe call with no
        // real remote party, so there is no ringing step - it goes straight to "connected".
        connection.setAudioModeIsVoip(true)
        connection.setDialing()
        connection.setActive()
        activeConnection = connection
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        CarProbeLog.log(
            "LegionConnectionService",
            "onCreateOutgoingConnectionFailed address=${request?.address} account=$connectionManagerPhoneAccount",
        )
    }

    companion object {
        /**
         * The one live [ProbeConnection], if any - [CarCallProbe.endCall] needs a handle to
         * disconnect, and this probe never has more than one call up at a time. `null` once the
         * connection is destroyed.
         */
        @Volatile
        var activeConnection: ProbeConnection? = null
    }
}

/**
 * The [Connection] half of the probe. Every lifecycle callback Telecom can fire against a
 * self-managed connection is overridden here and logged with a distinct tag, per ticket 14 - this
 * is the only reporting channel this probe has, since the A17k filters app logcat.
 */
class ProbeConnection : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
    }

    override fun onShowIncomingCallUi() {
        CarProbeLog.log("onShowIncomingCallUi", "fired")
    }

    override fun onAnswer(videoState: Int) {
        CarProbeLog.log("onAnswer", "videoState=$videoState")
        setActive()
    }

    override fun onAnswer() {
        CarProbeLog.log("onAnswer", "no-arg")
        setActive()
    }

    override fun onReject() {
        CarProbeLog.log("onReject", "no-arg")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        clearIfActive()
    }

    override fun onHold() {
        CarProbeLog.log("onHold", "fired")
        setOnHold()
    }

    override fun onUnhold() {
        CarProbeLog.log("onUnhold", "fired")
        setActive()
    }

    override fun onDisconnect() {
        CarProbeLog.log("onDisconnect", "fired")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        clearIfActive()
    }

    override fun onAbort() {
        CarProbeLog.log("onAbort", "fired")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        clearIfActive()
    }

    override fun onStateChanged(state: Int) {
        CarProbeLog.log("onStateChanged", "state=${probeStateToString(state)}")
    }

    /**
     * Experiment E2 (research 01 Q4/`android-auto/research/04-*`) - the whole reason this probe
     * exists is to learn whether the uplink is the car's Bluetooth mic or the phone's own. Every
     * field is decoded into a readable name; nothing here prints a bare int, per ticket 14.
     */
    // CallAudioState/onCallAudioStateChanged is deprecated in favour of the API 34 CallEndpoint
    // surface, but it is the API ticket 14 asks for and the one every research doc above cites -
    // minSdk is 24, CallEndpoint is not an option here. Suppressed, not replaced.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        if (state == null) {
            CarProbeLog.log("onCallAudioStateChanged", "null state")
            return
        }
        val activeRoute = routeToString(state.route)
        val supportedRoutes = supportedRoutesToString(state.supportedRouteMask)
        val bluetoothDevice = describeActiveBluetoothDevice(state)
        val supportedBluetoothDevices = describeSupportedBluetoothDevices(state)
        CarProbeLog.log(
            "onCallAudioStateChanged",
            "muted=${state.isMuted} activeRoute=$activeRoute supportedRoutes=$supportedRoutes " +
                "activeBluetoothDevice=$bluetoothDevice supportedBluetoothDevices=$supportedBluetoothDevices",
        )
    }

    override fun onCallEvent(event: String?, extras: Bundle?) {
        val extrasDump = extras?.keySet()?.joinToString(prefix = "{", postfix = "}") { key ->
            @Suppress("DEPRECATION")
            "$key=${extras.get(key)}"
        } ?: "null"
        CarProbeLog.log("onCallEvent", "event=$event extras=$extrasDump")
    }

    /**
     * `Connection` has no `onDestroyed()` lifecycle callback to hook (unlike `Conference`), so
     * every path that calls `destroy()` above calls this immediately after, rather than relying
     * on one central cleanup hook that does not exist on this class.
     */
    private fun clearIfActive() {
        if (LegionConnectionService.activeConnection === this) {
            LegionConnectionService.activeConnection = null
        }
    }

    /** Named `probe*` rather than `stateToString` - `Connection` already declares a static `stateToString(int)`, and a same-name instance method here would collide on JVM signature. */
    private fun probeStateToString(state: Int): String = when (state) {
        STATE_INITIALIZING -> "INITIALIZING"
        STATE_NEW -> "NEW"
        STATE_RINGING -> "RINGING"
        STATE_DIALING -> "DIALING"
        STATE_ACTIVE -> "ACTIVE"
        STATE_HOLDING -> "HOLDING"
        STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
    }

    private fun routeToString(route: Int): String = when (route) {
        CallAudioState.ROUTE_EARPIECE -> "EARPIECE"
        CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH"
        CallAudioState.ROUTE_WIRED_HEADSET -> "WIRED_HEADSET"
        CallAudioState.ROUTE_SPEAKER -> "SPEAKER"
        CallAudioState.ROUTE_WIRED_OR_EARPIECE -> "WIRED_OR_EARPIECE"
        else -> "UNKNOWN($route)"
    }

    /** [mask] is a bitwise-OR of the same `ROUTE_*` constants [routeToString] decodes singly. */
    private fun supportedRoutesToString(mask: Int): String {
        val routes = listOf(
            CallAudioState.ROUTE_EARPIECE to "EARPIECE",
            CallAudioState.ROUTE_BLUETOOTH to "BLUETOOTH",
            CallAudioState.ROUTE_WIRED_HEADSET to "WIRED_HEADSET",
            CallAudioState.ROUTE_SPEAKER to "SPEAKER",
        ).filter { (bit, _) -> mask and bit != 0 }.map { it.second }
        return if (routes.isEmpty()) "NONE" else routes.joinToString("|")
    }

    /**
     * `getActiveBluetoothDevice()` is API 28+; minSdk is 24, so this is guarded. Reading
     * `BluetoothDevice.getName()` needs `BLUETOOTH_CONNECT` (already declared, wave 1) but can
     * still throw `SecurityException` on a stale grant - caught here rather than crashing the
     * callback, per ticket 14's "never block, never crash" rule.
     */
    private fun describeActiveBluetoothDevice(state: CallAudioState): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "unavailable(SDK<28)"
        return try {
            val device = state.activeBluetoothDevice ?: return "none"
            "${device.name} (${device.address})"
        } catch (t: SecurityException) {
            "SecurityException:${t.message}"
        }
    }

    private fun describeSupportedBluetoothDevices(state: CallAudioState): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "unavailable(SDK<28)"
        return try {
            val devices = state.supportedBluetoothDevices
            if (devices.isNullOrEmpty()) {
                "none"
            } else {
                devices.joinToString(prefix = "[", postfix = "]") { "${it.name} (${it.address})" }
            }
        } catch (t: SecurityException) {
            "SecurityException:${t.message}"
        }
    }
}
