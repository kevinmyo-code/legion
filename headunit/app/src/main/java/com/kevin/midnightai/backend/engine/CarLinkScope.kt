package com.kevin.midnightai.backend.engine

/**
 * What the car's own sign-in (a token handed over the car link, T3b) may reach. The engine accepts
 * it only on the head unit's voice turn and its cancel (not called by this unit yet), its own
 * drive-log upload and the OBD batch for one vehicle; everything else is refused there. This unit
 * never sends anything else on it, so a refusal cannot happen by accident, and says so in words.
 */
object CarLinkScope {
    const val DEVICE_LOGS = "/api/device-logs/"
    const val OBD_BATCH = "/api/fleet/obd_samples/batch/"

    fun allows(method: String, path: String): Boolean =
        method == "POST" && (path == DEVICE_LOGS || path == OBD_BATCH)

    const val NOT_ON_CAR_SIGN_IN =
        "Not available on the car's own sign-in: it reaches only the car's own uploads. A member sign-in " +
            "on this unit (Settings, Engine) is needed for this. Nothing was sent."

    const val REFUSED =
        "The engine no longer accepts the car's own sign-in. Pair your phone again to renew it. Nothing more " +
            "is sent until then."

    const val SETTINGS_LINE =
        "Signed in with the car's own sign-in, sent by your phone over the car link. It covers the car's own " +
            "drive-log and OBD uploads. Engine questions, updates, and code-event and drive uploads need a member " +
            "sign-in here."

    const val ENGINE_QUESTIONS =
        "Engine questions need a member sign-in on this unit: the car's own sign-in reaches only the car's own " +
            "uploads. Nothing was read or written."
}
