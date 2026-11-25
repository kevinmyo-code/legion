package com.kevin.midnightai.backend.engine

/**
 * What the car's own sign-in (a token handed over the car link, T3b) may reach. The engine accepts
 * it only on the head unit's voice turn and its cancel (not called by this unit yet), its own
 * drive-log upload, the OBD batch for one vehicle, and (2026-11-24) the two release READS its own
 * updater makes; everything else is refused there, in words, before any socket opens.
 *
 * **A 401 on a release read is NOT proof the hand-over is dead** ([aRefusalEndsTheHandover]). An
 * engine older than this build - a rollback, or a unit published ahead of its engine - authenticates
 * the release routes with `DeviceTokenAuthentication` alone and answers 401 to any `hu1_` key, and
 * the updater asks 20 seconds after each start and every 6 hours. Treating that as a dead
 * hand-over would stop the car's drive-log and OBD uploads until the phone paired again, for no
 * better reason than an engine that has not caught up.
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

    /** The car's own uploads: the two routes the engine has taken a unit token on since T3b. */
    private fun isOwnUpload(method: String, path: String): Boolean =
        method == "POST" && (path == DEVICE_LOGS || path == OBD_BATCH)

    /** The two reads `update/DjangoUpdateBackend` makes, and nothing else under `/api/releases`. */
    private fun isReleaseRead(method: String, path: String): Boolean =
        method == "GET" && (path == RELEASES_LATEST || RELEASE_DOWNLOAD.matches(path))

    fun allows(method: String, path: String): Boolean =
        isOwnUpload(method, path) || isReleaseRead(method, path)

    /**
     * Whether a 401 here proves the hand-over itself is gone, which is what
     * `EngineConfig.markCarLinkRefused` records and what then stops everything until the phone
     * pairs again. Only the car's own uploads qualify; see this object's own doc comment for why a
     * release read does not.
     */
    fun aRefusalEndsTheHandover(method: String, path: String): Boolean = isOwnUpload(method, path)

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
