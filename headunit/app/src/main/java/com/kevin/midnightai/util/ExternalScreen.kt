package com.kevin.midnightai.util

/**
 * A system screen or another app this launcher asks Android to open. The head unit is a gutted AOSP
 * build, so any of them may be absent; each carries the words said when it is, because "nothing
 * happened" on a tap is the one thing a launcher may never do.
 *
 * [ExternalDoor] is how one is opened. Nothing here knows where its words are shown: the caller
 * owns that, because the screen holding the tap is the only thing that knows where a sentence fits.
 *
 * Three failure kinds, three different claims, and [instead] belongs to exactly one of them. It
 * explains an ABSENT screen, so it is said only by [missingWords]: a unit that has the screen and
 * refused us has not told us the screen is missing, and saying so would be a diagnosis we did not
 * make. Being wrong in that direction is how the driver ends up told "nothing on this unit can take
 * a picture" because a FileProvider was misconfigured.
 */
enum class ExternalScreen(private val thing: String, private val instead: String) {
    PAIR_NEW_PHONE(
        "Bluetooth pairing screen",
        "Pair the phone from this head unit's own Bluetooth app or its system settings instead.",
    ),
    BLUETOOTH_SETTINGS(
        "Bluetooth settings screen",
        "Open them from this head unit's own menu instead.",
    ),
    VENDOR_APP(
        "Bluetooth app",
        "It may have been uninstalled.",
    ),
    INSTALLED_APP(
        "app",
        "It may have been uninstalled since this list was read.",
    ),
    OTHER_HOME_APP(
        // Reached only when the other launcher was in the package list a moment ago AND the
        // settings fallback also failed, so "missing" here means that one app is gone, never that
        // no other home screen exists.
        "other home screen",
        "It is no longer installed on this unit.",
    ),
    HOME_SETTINGS(
        "home-app setting",
        "Android only lets the home app be changed from the system's own settings, and this unit " +
            "does not show that screen.",
    ),
    NOTIFICATION_ACCESS(
        "notification-access screen",
        "Without that grant the unit cannot read what your phone is playing.",
    ),
    PHOTO_PICKER(
        "photo picker",
        "Nothing on this unit can pick a photo, so one cannot be chosen by hand.",
    ),
    FILE_PICKER(
        "file picker",
        "Nothing on this unit can pick a file, so one cannot be chosen by hand.",
    ),
    CAMERA(
        "camera",
        "Nothing on this unit can take a picture.",
    ),
    SHARE_SHEET(
        "sharing screen",
        "Nothing on this unit offers to share it.",
    ),
    PERMISSION_PROMPT(
        "permission screen",
        "This ROM cannot ask for the permission, so it has to be granted from its own settings.",
    ),
    ;

    /**
     * Said when no activity on this unit handles it, which is the one case [instead] explains.
     * [named] replaces the generic noun with a whole noun phrase the caller composes, for a door
     * whose subject has a name the driver just read on screen (an app in the tray). It is the
     * caller's grammar, not this enum's.
     */
    fun missingWords(named: String? = null): String =
        "This head unit has no ${named ?: thing} to open. $instead"

    /** Said when the unit has it but refused us. Why it refused is not known here, so it is not
     * claimed: [instead]'s diagnosis stays out of this sentence. */
    fun refusedWords(named: String? = null): String =
        "This head unit refused to open the ${named ?: thing}."

    /** Said when it failed for some other reason this ROM produced, which is likewise undiagnosed
     * until the drive log is read. */
    fun failedWords(named: String? = null): String =
        "This head unit could not open the ${named ?: thing}."
}
