package com.kevin.legion.vehicle

import android.content.Context

/**
 * Stores the user-selected active dongle MAC in SharedPreferences so the
 * connection loop knows which paired device to prefer.
 */
object ObdDeviceRegistry {
    private const val PREFS_NAME = "obd_device_registry"
    private const val KEY_ACTIVE_MAC = "active_mac"
    private const val KEY_INFO_PREFIX = "info_"
    private const val KEY_BLE_PREFIX = "ble_"

    fun getActiveMac(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_MAC, null)
    }

    fun setActiveMac(context: Context, mac: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_MAC, mac).apply()
    }

    fun clearActive(context: Context) {
        setActiveMac(context, null)
    }

    /**
     * Last-known adapter identity + protocol for [mac], captured by
     * [ObdBluetoothManager] on connect (ATI + ATDPN). Deliberately a single
     * display string, not a structured tier record yet - this is raw
     * capture-for-testing, not the adapter-tier catalog. Survives a
     * disconnect so the device list can show "last seen: ..." even when
     * nothing is currently connected.
     */
    fun getLastKnownInfo(context: Context, mac: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_INFO_PREFIX$mac", null)
    }

    fun setLastKnownInfo(context: Context, mac: String, info: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_INFO_PREFIX$mac", info).apply()
    }

    /**
     * Whether [mac] was selected as a BLE (GATT) dongle rather than classic
     * Bluetooth (RFCOMM). Persisted at selection time because after a reboot
     * the connection loop obtains the device via `getRemoteDevice(mac)`,
     * whose `device.type` is often DEVICE_TYPE_UNKNOWN for a never-bonded BLE
     * dongle - so [android.bluetooth.BluetoothDevice.getType] alone can't be
     * trusted. The device picker knows the true kind at scan/selection time
     * (which scanner found it) and persists it here; see
     * [ObdBluetoothManager.connect] for the read side.
     */
    fun isBle(context: Context, mac: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("$KEY_BLE_PREFIX$mac", false)
    }

    fun setBle(context: Context, mac: String, isBle: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$KEY_BLE_PREFIX$mac", isBle).apply()
    }
}
