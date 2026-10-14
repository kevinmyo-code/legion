package com.kevin.midnightai.bluetooth

/**
 * Hidden AOSP sink-side profile ids (see [BluetoothProbe]'s kdoc for why these are literals:
 * they are `@hide` constants on [android.bluetooth.BluetoothProfile], unreachable at compile
 * time). A2DP_SINK/AVRCP_CONTROLLER/HEADSET_CLIENT are what a HEAD UNIT uses to receive a
 * phone (the phone is the source, we're the sink) - the opposite direction from a phone's own
 * A2DP/HEADSET role.
 */
object PhoneLinkProfiles {
    const val A2DP_SINK = 11
    const val AVRCP_CONTROLLER = 12
    const val HEADSET_CLIENT = 16
}

/**
 * One step of a phone switch, expressed against a plain MAC address string - deliberately no
 * `android.bluetooth.*` types, so [planSwitch] stays a pure function runnable under
 * `gradlew testDebugUnitTest` (plain JVM, unmocked android.jar - see the coding playbook's
 * 2026-07-28 testing note). [PhoneLinkManager] is the executor that turns each step into a
 * real reflective call against a bound [android.bluetooth.BluetoothProfile] proxy.
 */
sealed interface PhoneLinkAction {
    val address: String

    /** Hidden `setPriority(device, 0)` / `setConnectionPolicy(device, 0)` - stop this phone
     *  auto-reconnecting and grabbing the link back mid-switch. */
    data class SetAutoConnectOff(override val address: String) : PhoneLinkAction

    data class Disconnect(override val address: String, val profile: Int) : PhoneLinkAction

    /** Hidden `setPriority(device, 100)` / `setConnectionPolicy(device, 100)`. */
    data class SetAutoConnectOn(override val address: String) : PhoneLinkAction

    data class Connect(override val address: String, val profile: Int) : PhoneLinkAction
}

/**
 * Pure planner for "make X the active phone": disconnect every OTHER bonded phone on every
 * sink profile and kill their auto-reconnect priority FIRST (so they can't grab the audio
 * link back mid-switch), then raise X's priority and connect it on audio (A2DP_SINK) + calls
 * (HEADSET_CLIENT). AVRCP_CONTROLLER is disconnected on the losing phones but never explicitly
 * reconnected on the winner - A2DP_SINK's own connect is what the stack uses to also stand up
 * AVRCP on most ROMs; there's no separate "AVRCP connect" the brief calls for on the winning side.
 *
 * The ORDER is the actual fix for the failure this feature exists for: two phones paired, one
 * playing, switching to the other silently failing. That bug was exactly this race - the new
 * phone tried to connect while the old one's auto-reconnect priority was still ON, so the old
 * one kept winning the link. Fully disconnecting + de-prioritizing every loser BEFORE touching
 * the winner removes the race.
 */
fun planSwitch(bondedAddresses: List<String>, target: String): List<PhoneLinkAction> {
    if (target !in bondedAddresses) return emptyList()
    val actions = mutableListOf<PhoneLinkAction>()
    bondedAddresses.filter { it != target }.forEach { other ->
        actions += PhoneLinkAction.SetAutoConnectOff(other)
        actions += PhoneLinkAction.Disconnect(other, PhoneLinkProfiles.A2DP_SINK)
        actions += PhoneLinkAction.Disconnect(other, PhoneLinkProfiles.AVRCP_CONTROLLER)
        actions += PhoneLinkAction.Disconnect(other, PhoneLinkProfiles.HEADSET_CLIENT)
    }
    actions += PhoneLinkAction.SetAutoConnectOn(target)
    actions += PhoneLinkAction.Connect(target, PhoneLinkProfiles.A2DP_SINK)
    actions += PhoneLinkAction.Connect(target, PhoneLinkProfiles.HEADSET_CLIENT)
    return actions
}
