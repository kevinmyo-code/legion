package com.kevin.midnightai.util

/**
 * A system screen or another app this launcher asks Android to open. The head unit is a gutted AOSP
 * build, so any of them may be absent; each carries the words said when it is, because "nothing
 * happened" on a tap is the one thing a launcher may never do.
 *
 * [ExternalDoor] is how one is opened. Nothing here knows where its words are shown: the caller
 * owns that, because the screen holding the tap is the only thing that knows where a sentence fits.
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
        "other home screen to switch to",
        "Change the home app from this head unit's own settings instead.",
    ),
    HOME_SETTINGS(
        "home-app setting",
        "This unit's own settings app is the only way to change it.",
    ),
    NOTIFICATION_ACCESS(
        "notification-access screen",
        "Without that grant the unit cannot read what your phone is playing.",
    ),
    PHOTO_PICKER(
        "photo picker",
        "This ROM has no gallery or document picker, so a photo cannot be chosen by hand.",
    ),
    FILE_PICKER(
        "file picker",
        "This ROM has no document picker, so files cannot be chosen by hand.",
    ),
    CAMERA(
        "camera app",
        "Nothing on this unit can take a picture.",
    ),
    SHARE_SHEET(
        "sharing screen",
        "This ROM has nothing to share to.",
    ),
    PERMISSION_PROMPT(
        "permission screen",
        "This ROM cannot ask for the permission, so it has to be granted from its own settings.",
    ),
    ;

    /**
     * Said when no activity on this unit handles it. [named] replaces the generic noun with a
     * whole noun phrase the caller composes, for a door whose subject has a name the driver just
     * read on screen (an app in the tray). It is the caller's grammar, not this enum's.
     */
    fun missingWords(named: String? = null): String =
        "This head unit has no ${named ?: thing} to open. $instead"

    /** Said when the unit has it but refused us. */
    fun refusedWords(named: String? = null): String =
        "This head unit refused to open the ${named ?: thing}. $instead"

    /** Said when it failed for some other reason this ROM produced. */
    fun failedWords(named: String? = null): String =
        "This head unit could not open the ${named ?: thing}. $instead"
}
