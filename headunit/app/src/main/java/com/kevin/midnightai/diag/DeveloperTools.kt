package com.kevin.midnightai.diag

import android.provider.Settings

/*
 * Settings > System > Developer tools (Kevin 2026-10-28: "open devoptions directly as well as the
 * wireless debugging row from our ui if possible. all of it"). The unit's skin hides Android
 * Settings and the unit has no ADB, so this screen is the way in.
 *
 * This object is PURE: no Context, no Intent, no SDK_INT read. It builds the intent specs, the
 * shell commands, the status text and the root-attempt verdict from plain inputs, so all of it is
 * unit-tested on the JVM. `Settings.ACTION_*` and `Settings.Global.ADB_ENABLED` are compile-time
 * String constants (inlined), so referencing them here needs no Android runtime. The Android glue
 * (starting activities, reading Settings.Global, the port probe, running su) is
 * `ui/DeveloperToolsScreen.kt` and stays thin.
 */
object DeveloperTools {

    /** Android 11. Wireless debugging (and `adb_wifi_enabled`) do not exist below it. */
    const val API_WIRELESS_DEBUGGING = 30

    const val ADB_TCP_PORT = 5555
    const val PORT_PROBE_TIMEOUT_MS = 300
    const val ROOT_TIMEOUT_MS = 5_000L

    /** By name: `Settings.Global.ADB_WIFI_ENABLED` is @hide (frameworks/base android11-release). */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val ADB_ENABLED = Settings.Global.ADB_ENABLED

    // --- Intents -------------------------------------------------------------------------------

    /**
     * What the glue turns into an `Intent`. [componentPackage]/[componentClass] set = explicit
     * component; [fragmentArgKey] set = the Settings app's highlight extras (see
     * [wirelessDebuggingChain]).
     */
    data class IntentSpec(
        val action: String?,
        val componentPackage: String? = null,
        val componentClass: String? = null,
        val fragmentArgKey: String? = null,
    )

    const val SETTINGS_PACKAGE = "com.android.settings"
    const val WIRELESS_DEBUGGING_ACTIVITY = "com.android.settings.Settings\$WirelessDebuggingActivity"
    const val WIRELESS_DEBUGGING_ACTION = "android.settings.WIRELESS_DEBUGGING_SETTINGS"

    /** `SettingsActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS` (packages/apps/Settings, android11-release). */
    const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    /** `SettingsActivity.EXTRA_FRAGMENT_ARG_KEY` (same file): the preference key to highlight. */
    const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    /** The Wireless debugging preference key in `res/xml/development_settings.xml`. */
    const val WIRELESS_DEBUGGING_PREF_KEY = "toggle_adb_wireless"

    val androidSettings = IntentSpec(Settings.ACTION_SETTINGS)
    val developerOptions = IntentSpec(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    val aboutDevice = IntentSpec(Settings.ACTION_DEVICE_INFO_SETTINGS)

    fun showWirelessDebuggingRow(apiLevel: Int): Boolean = apiLevel >= API_WIRELESS_DEBUGGING

    /**
     * Tried in order; the first that starts wins. Checked against AOSP packages/apps/Settings
     * AndroidManifest.xml and src/.../Settings.java on android11-release, android12-release,
     * android13-release, android14-release and main (android.googlesource.com, fetched 2026-10-28),
     * and frameworks/base core/java/android/provider/Settings.java android11-release:
     *
     * 1. `com.android.settings/.Settings$WirelessDebuggingActivity`: NOT declared in any of those
     *    manifests, and Settings.java has no such class. Kept only in case a vendor build adds it;
     *    on AOSP it throws ActivityNotFoundException and the chain moves on.
     * 2. `android.settings.WIRELESS_DEBUGGING_SETTINGS`: no such action in the Settings manifests
     *    or in the framework's Settings.java. Same reason for keeping it.
     * 3. What AOSP does support: Developer options (`Settings$DevelopmentSettingsDashboardActivity`,
     *    filter `android.settings.APPLICATION_DEVELOPMENT_SETTINGS`) with
     *    `:settings:show_fragment_args` = { `:settings:fragment_args_key` = "toggle_adb_wireless" }.
     *    SettingsActivity passes that bundle to the fragment as its arguments and
     *    SettingsPreferenceFragment.highlightPreferenceIfNeeded() reads the key from them; the key is
     *    the Wireless debugging switch in development_settings.xml (android11-release line 148).
     *    The Wireless debugging page itself is WirelessDebuggingFragment inside `.SubSettings`,
     *    which is not exported, so it cannot be opened directly from another app.
     */
    fun wirelessDebuggingChain(): List<IntentSpec> = listOf(
        IntentSpec(action = null, componentPackage = SETTINGS_PACKAGE, componentClass = WIRELESS_DEBUGGING_ACTIVITY),
        IntentSpec(WIRELESS_DEBUGGING_ACTION),
        IntentSpec(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, fragmentArgKey = WIRELESS_DEBUGGING_PREF_KEY),
    )

    /** What the row says after a tap. [index] is the chain entry that started, or null for none. */
    fun wirelessDebuggingNote(index: Int?): String = when (index) {
        0, 1 -> "Android accepted the request for Wireless debugging."
        2 -> "This Android has no direct link to Wireless debugging, so Developer options was " +
            "opened instead. Find Wireless debugging under Debugging."
        else -> NO_SCREEN
    }

    const val NO_SCREEN = "This unit has no screen for that. Android had nothing to open."

    // --- Status --------------------------------------------------------------------------------

    /** Plain inputs read by the glue. A null means "not read" and is said in words. */
    data class StatusInputs(
        val release: String,
        val apiLevel: Int,
        val manufacturer: String,
        val model: String,
        val fingerprint: String,
        /** Settings.Global adb_enabled, -1 when absent. */
        val adbEnabled: Int,
        /** Settings.Global adb_wifi_enabled, -1 when absent; ignored below API 30. */
        val adbWifiEnabled: Int,
        val ipv4: String?,
        /** null = not probed yet. */
        val port5555Open: Boolean?,
    )

    /**
     * Build fingerprints are `brand/product/device:release/id/incremental:type/tags`; the short
     * form keeps `brand/product/device release/id`. Anything else is returned cut to 60 chars.
     */
    fun shortFingerprint(fingerprint: String): String {
        val parts = fingerprint.split(':')
        if (parts.size < 2) return fingerprint.take(60)
        val rel = parts[1].split('/')
        val tail = listOfNotNull(rel.getOrNull(0), rel.getOrNull(1)).joinToString("/")
        return if (tail.isEmpty()) parts[0] else "${parts[0]} $tail"
    }

    fun settingValue(value: Int): String = when (value) {
        -1 -> "not present"
        0 -> "0 (off)"
        1 -> "1 (on)"
        else -> value.toString()
    }

    fun statusLines(s: StatusInputs): List<String> = buildList {
        add("Android ${s.release} (API ${s.apiLevel})")
        add("Device: ${s.manufacturer} ${s.model}")
        add("Build: ${shortFingerprint(s.fingerprint)}")
        add("adb_enabled: ${settingValue(s.adbEnabled)}")
        if (s.apiLevel >= API_WIRELESS_DEBUGGING) add("adb_wifi_enabled: ${settingValue(s.adbWifiEnabled)}")
        add("IPv4: ${s.ipv4 ?: "no Wi-Fi or Ethernet address"}")
        add(
            "Port $ADB_TCP_PORT on 127.0.0.1: " + when (s.port5555Open) {
                null -> "checking"
                true -> "accepting connections"
                false -> "closed"
            },
        )
    }

    fun connectCommand(ipv4: String?): String = "adb connect ${ipv4 ?: "<unit IP>"}:$ADB_TCP_PORT"

    // --- Root attempt --------------------------------------------------------------------------

    /** Each entry runs as `su -c "<entry>"`. */
    fun rootCommands(apiLevel: Int): List<String> = buildList {
        add("setprop service.adb.tcp.port $ADB_TCP_PORT && stop adbd && start adbd")
        if (apiLevel >= API_WIRELESS_DEBUGGING) add("settings put global $ADB_WIFI_ENABLED 1")
    }

    enum class RootOutcome { PORT_OPEN, NO_ROOT, STILL_CLOSED }

    /**
     * The port probe decides success, never the exit code. [exitCode] null = su timed out (or was
     * killed), which is treated as refused: a pending root-grant prompt nobody answered.
     */
    fun rootOutcome(suExists: Boolean, exitCode: Int?, portOpenAfter: Boolean): RootOutcome = when {
        !suExists -> RootOutcome.NO_ROOT
        portOpenAfter -> RootOutcome.PORT_OPEN
        exitCode != 0 -> RootOutcome.NO_ROOT
        else -> RootOutcome.STILL_CLOSED
    }

    fun rootMessage(outcome: RootOutcome, ipv4: String?): String = when (outcome) {
        RootOutcome.PORT_OPEN -> "Port $ADB_TCP_PORT is now open, connect with ${connectCommand(ipv4)}"
        RootOutcome.NO_ROOT -> "No root on this unit (su not found or refused)"
        RootOutcome.STILL_CLOSED -> "Ran, but port $ADB_TCP_PORT is still closed"
    }

    /** The dim line under the verdict: what su actually did. */
    fun rootDetail(suExists: Boolean, exitCode: Int?): String = when {
        !suExists -> "su could not be started."
        exitCode == null -> "su did not finish within ${ROOT_TIMEOUT_MS / 1000} s and was stopped."
        else -> "su exit code: $exitCode"
    }

    const val EXPOSURE_NOTE =
        "ADB over Wi-Fi is open to anyone on the same network while it is on."
}
