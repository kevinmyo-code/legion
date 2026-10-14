package com.kevin.midnightai.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Read-only probe of this head unit's Bluetooth stack, for the Bluetooth
 * diagnostics screen. Exists to answer one question before a two-phone
 * switcher is designed: does the phone link run through Android's own
 * Bluetooth stack (so this app can see and steer it via the A2DP-sink /
 * HFP-client profiles), or through a separate vendor BT module that Android's
 * [BluetoothAdapter] never sees (so only the vendor's own app can)?
 *
 * Nothing here connects, disconnects, pairs or unpairs anything. Hidden
 * connect/disconnect methods are only LOOKED UP by reflection, never invoked,
 * so opening this screen cannot change which phone is playing.
 */
class BluetoothProbe(context: Context) {

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Profile id -> proxy, filled asynchronously by [open]. */
    private val proxies = mutableMapOf<Int, BluetoothProfile>()

    /** Profile id -> whether getProfileProxy() even accepted the request. */
    private val requested = mutableMapOf<Int, Boolean>()

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            synchronized(proxies) { proxies[profile] = proxy }
        }

        override fun onServiceDisconnected(profile: Int) {
            synchronized(proxies) { proxies.remove(profile) }
        }
    }

    fun open() {
        val a = adapter ?: return
        if (!hasConnectPermission()) return
        PROFILES.forEach { (id, _) ->
            requested[id] = runCatching { a.getProfileProxy(app, listener, id) }.getOrDefault(false)
        }
    }

    fun close() {
        val a = adapter ?: return
        synchronized(proxies) {
            proxies.forEach { (id, proxy) -> runCatching { a.closeProfileProxy(id, proxy) } }
            proxies.clear()
        }
    }

    /** A fresh plain-text report, one line per fact, meant to be photographed off the screen. */
    @SuppressLint("MissingPermission")
    fun report(): List<String> = buildList {
        add("== DEVICE ==")
        add("maker ${Build.MANUFACTURER} / model ${Build.MODEL} / brand ${Build.BRAND}")
        add("hardware ${Build.HARDWARE} / board ${Build.BOARD} / product ${Build.PRODUCT}")
        add("android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        add("rom ${Build.DISPLAY}")

        add("")
        add("== ADAPTER ==")
        val a = adapter
        if (a == null) {
            add("NO BluetoothAdapter - Android's BT stack is absent; BT is a vendor module")
        } else if (!hasConnectPermission()) {
            add("BLUETOOTH_CONNECT not granted - cannot read anything below")
        } else {
            add("enabled ${a.isEnabled} / name \"${runCatching { a.name }.getOrNull()}\"")

            add("")
            add("== BONDED (paired) ==")
            val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
            if (bonded.isEmpty()) add("none - if your phones ARE paired, the vendor module owns them")
            bonded.forEach { add("${label(it)} class=${deviceClass(it)} type=${deviceType(it.type)}") }

            add("")
            add("== PROFILES ==")
            val snapshot = synchronized(proxies) { proxies.toMap() }
            PROFILES.forEach { (id, name) ->
                val proxy = snapshot[id]
                when {
                    requested[id] == false -> add("$name: not offered by this stack")
                    proxy == null -> add("$name: requested, no service bound (yet)")
                    else -> {
                        val connected = runCatching { proxy.connectedDevices }.getOrElse { e ->
                            add("$name: bound, read failed ${e.javaClass.simpleName}")
                            null
                        } ?: return@forEach
                        add("$name: bound (${proxy.javaClass.name})")
                        add("   connected: " + if (connected.isEmpty()) "none" else connected.joinToString { label(it) })
                        add("   methods: " + CONTROL_METHODS.joinToString { m -> "$m=${if (hasMethod(proxy, m)) "yes" else "no"}" })
                    }
                }
            }
        }

        add("")
        add("== BT-LOOKING PACKAGES ==")
        val pkgs = runCatching {
            app.packageManager.getInstalledPackages(0).map { it.packageName }
        }.getOrDefault(emptyList()).filter { looksBluetooth(it) }.sorted()
        if (pkgs.isEmpty()) add("none found")
        pkgs.forEach { add(it) }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun label(d: BluetoothDevice): String =
        "\"${runCatching { d.name }.getOrNull() ?: "?"}\" ${d.address}"

    @SuppressLint("MissingPermission")
    private fun deviceClass(d: BluetoothDevice): String =
        when (runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()) {
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            null -> "?"
            else -> "other"
        }

    private fun deviceType(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    /**
     * Whether a hidden control method is reachable by reflection. "no" means
     * either the ROM lacks it or Android's hidden-API policy blocks it for this
     * app, and both mean the same thing for the switcher: we cannot call it.
     */
    private fun hasMethod(proxy: BluetoothProfile, name: String): Boolean {
        val argTypes: Array<Class<*>> = when (name) {
            "setPriority", "setConnectionPolicy" -> arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            else -> arrayOf(BluetoothDevice::class.java)
        }
        return runCatching { proxy.javaClass.getMethod(name, *argTypes) }.isSuccess
    }

    private fun looksBluetooth(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "bluetooth" in p || BT_TOKEN.containsMatchIn(p)
    }

    companion object {
        /**
         * Profile ids. The sink-side ones (A2DP_SINK 11, AVRCP_CONTROLLER 12,
         * HEADSET_CLIENT 16, PBAP_CLIENT 17, MAP_CLIENT 18) are @hide constants,
         * hence the literals; they are what a head unit uses to RECEIVE a phone.
         * HEADSET 1 / A2DP 2 are the phone-side roles, included because a
         * misconfigured ROM sometimes exposes those instead.
         */
        private val PROFILES = listOf(
            1 to "HEADSET",
            2 to "A2DP",
            11 to "A2DP_SINK",
            12 to "AVRCP_CONTROLLER",
            16 to "HEADSET_CLIENT",
            17 to "PBAP_CLIENT",
            18 to "MAP_CLIENT",
        )

        private val CONTROL_METHODS = listOf("connect", "disconnect", "setPriority", "setConnectionPolicy")

        private val BT_TOKEN = Regex("""(^|[._])bt([._]|$)""")
    }
}
