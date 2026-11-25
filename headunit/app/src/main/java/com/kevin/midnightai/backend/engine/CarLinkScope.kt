package com.kevin.midnightai.backend.engine

/**
 * What the car's own sign-in (a token handed over the car link, T3b) may reach. The engine accepts
 * it only on the head unit's voice turn and its cancel (not called by this unit yet), its own
 * drive-log upload, the OBD batch for one vehicle, and (2026-11-24) the two release READS its own
 * updater makes; everything else is refused there. This unit never sends anything else on it, so a
 * refusal cannot happen by accident, and says so in words.
 *
 * **The release reads were added because the unit could otherwise never take an OTA over the car
 * link.** `update/DjangoUpdateBackend` asks `GET /api/releases/latest?app=headunit` and then
 * `GET /api/releases/<id>/download`; with only the two uploads allowed, [allows] refused both here
 * before any socket opened, so a paired unit's only way to update was his engine password typed on
 * a car touchscreen. The engine's own copy of this list
 * (`server/headunit_signin/scope.py`) must agree, and it narrows the answer to this app's
 * releases; the two files are changed together. These are reads of engine-wide rows that carry no
 * household data, and the APK is still checked (checksum, package, signer, version, ABI) by
 * [com.kevin.midnightai.update.UpdateRules.verifyRefusal] before anything installs.
 */
object CarLinkScope {
    const val DEVICE_LOGS = "/api/device-logs/"
    const val OBD_BATCH = "/api/fleet/obd_samples/batch/"
    const val RELEASES_LATEST = "/api/releases/latest"

    /** `/api/releases/<uuid>/download` exactly: the one release path the updater builds. */
    private val RELEASE_DOWNLOAD =
        Regex("^/api/releases/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/download$")

    fun allows(method: String, path: String): Boolean = when (method) {
        "POST" -> path == DEVICE_LOGS || path == OBD_BATCH
        "GET" -> path == RELEASES_LATEST || RELEASE_DOWNLOAD.matches(path)
        else -> false
    }

    const val NOT_ON_CAR_SIGN_IN =
        "Not available on the car's own sign-in: it reaches only the car's own uploads. A member sign-in " +
            "on this unit (Settings, Engine) is needed for this. Nothing was sent."

    const val REFUSED =
        "The engine no longer accepts the car's own sign-in. Pair your phone again to renew it. Nothing more " +
            "is sent until then."

    const val SETTINGS_LINE =
        "Signed in with the car's own sign-in, sent by your phone over the car link. It covers the car's own " +
            "drive-log and OBD uploads and its own app updates. Engine questions, and code-event and drive " +
            "uploads, need a member sign-in here."

    const val ENGINE_QUESTIONS =
        "Engine questions need a member sign-in on this unit: the car's own sign-in reaches only the car's own " +
            "uploads. Nothing was read or written."
}
