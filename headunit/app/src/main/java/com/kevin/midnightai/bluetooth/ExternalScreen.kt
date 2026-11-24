package com.kevin.midnightai.bluetooth

/**
 * A system screen this app asks Android to open. The head unit is a gutted AOSP build, so any of
 * them may be absent; each carries the words said when it is, because "nothing happened" on a tap
 * is the one thing a launcher may never do.
 */
enum class ExternalScreen(private val label: String, private val instead: String) {
    PAIR_NEW_PHONE(
        "Bluetooth pairing",
        "Pair the phone from this head unit's own Bluetooth app or its system settings instead.",
    ),
    BLUETOOTH_SETTINGS(
        "Bluetooth settings",
        "Open them from this head unit's own menu instead.",
    ),
    VENDOR_APP(
        "Bluetooth app",
        "It may have been uninstalled.",
    ),
    ;

    /** Said when no activity on this unit handles the screen. */
    val missingWords: String get() = "This head unit has no $label screen to open. $instead"

    /** Said when the unit has the screen but refused us. */
    val refusedWords: String get() = "This head unit refused to open $label. $instead"

    /** Said when it failed for some other reason this ROM produced. */
    val failedWords: String get() = "This head unit could not open $label. $instead"
}
