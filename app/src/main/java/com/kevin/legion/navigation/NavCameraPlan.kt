package com.kevin.legion.navigation

/** What the map should do with its camera, decided from state alone so a test can pin it. */
enum class CameraAction {
    /** Hand the SDK camera to the guided follow mode. */
    SDK_FOLLOW,

    /** Hand the SDK camera to the guided whole-route mode. */
    SDK_OVERVIEW,

    /** Release the SDK camera (it must not fight us) and fit the selected route plus the puck ourselves. */
    FIT_ROUTE,

    /** Release the SDK camera and leave the view where it is. */
    RELEASE,

    /** The user panned away mid-trip: do nothing until they ask to recenter. */
    LEAVE_ALONE,
}

/**
 * The camera decision (phone run 4, defect 1). The SDK's `NavigationCamera` keeps whatever mode it was
 * last given: after a guided trip ended it was still FOLLOWING, so a later preview's own fit lost to
 * the SDK's follow zoom, and the overview button (which only ever asked the SDK while GUIDING) did
 * nothing. The rule now: the SDK camera is engaged ONLY while guiding; every other phase releases it,
 * and a preview (or any phase that still shows routes) fits the route itself, whatever the mode.
 */
object NavCameraPlan {
    fun decide(phase: NavPhase, mode: NavCameraMode, routesShown: Boolean): CameraAction = when {
        phase == NavPhase.GUIDING && routesShown -> when (mode) {
            NavCameraMode.FOLLOWING -> CameraAction.SDK_FOLLOW
            NavCameraMode.OVERVIEW -> CameraAction.SDK_OVERVIEW
            NavCameraMode.FREE -> CameraAction.LEAVE_ALONE
        }
        phase == NavPhase.PREVIEW && routesShown -> CameraAction.FIT_ROUTE
        else -> CameraAction.RELEASE
    }
}
