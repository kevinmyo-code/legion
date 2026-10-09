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

/** Camera padding in pixels, kept free of Mapbox types so the arithmetic is unit-testable. */
data class FramePadding(val top: Double, val left: Double, val bottom: Double, val right: Double)

/**
 * The follow camera's numbers and the pure rules around it (2026-10-09, "it doesn't become the third-person
 * follow view and the panel hides half the map").
 *
 * What was wrong, read from the code: [NavCameraPlan.decide] DID hand the camera to the SDK's FOLLOWING
 * state, so the plan never failed to follow. The look failed three other ways. (1) The viewport data
 * source ran on its defaults, whose `pitchNearManeuvers` flattens the camera to a top-down view whenever a
 * turn is near, so the tilt came and went. (2) Its padding was the measured bottom sheet, which was up to
 * half the screen, plus 64dp each side: the SDK centres the puck in whatever is left, so the frame was a
 * small box in the upper middle and the puck sat mid-screen behind or just above the sheet. (3) The
 * preview fit's asynchronous `cameraForCoordinates` callback could land after follow had started and
 * snap the camera back to a flat route fit.
 */
object NavFollowFrame {
    /** Third-person tilt, in degrees. Google Maps' driving view sits in this range. */
    const val PITCH = 55.0

    /** Street level at the nearest, and never so far out that the road ahead is a smudge. */
    const val MIN_ZOOM = 14.0
    const val MAX_ZOOM = 17.5

    /** Where the puck sits, as a share of the map's height from the top: the lower third. */
    const val PUCK_SHARE = 0.7

    /** The padded frame never shrinks below this share of the map's height, however tall the chrome. */
    private const val MIN_FRAME_SHARE = 0.2

    /**
     * Follow padding. The SDK puts the puck at the centre of the padded rectangle, so the top padding is
     * what drags it down to [PUCK_SHARE]; the bottom padding is the sheet at its COLLAPSED height, so the
     * puck and the road ahead are never behind it.
     */
    fun followPadding(
        mapHeightPx: Double,
        topChromePx: Double,
        bottomChromePx: Double,
        marginPx: Double,
        sidePx: Double,
    ): FramePadding {
        val bottom = bottomChromePx + marginPx
        val minTop = topChromePx + marginPx
        if (mapHeightPx <= 0.0) return FramePadding(minTop, sidePx, bottom, sidePx)
        val wanted = (2 * PUCK_SHARE - 1.0) * mapHeightPx + bottom
        val room = mapHeightPx - bottom - MIN_FRAME_SHARE * mapHeightPx
        return FramePadding(wanted.coerceAtMost(room).coerceAtLeast(minTop), sidePx, bottom, sidePx)
    }

    /** Padding for the whole-route frame: the chrome as measured, never more than [maxBottomShare] of the map. */
    fun overviewPadding(
        mapHeightPx: Double,
        topChromePx: Double,
        bottomChromePx: Double,
        marginPx: Double,
        sidePx: Double,
        maxBottomShare: Double,
    ): FramePadding {
        val maxBottom = if (mapHeightPx > 0.0) mapHeightPx * maxBottomShare else Double.MAX_VALUE
        return FramePadding(topChromePx + marginPx, sidePx, minOf(bottomChromePx, maxBottom) + marginPx, sidePx)
    }

    /** The overview button: whole route unless it is already showing, then back to following. */
    fun afterOverviewTap(mode: NavCameraMode): NavCameraMode =
        if (mode == NavCameraMode.OVERVIEW) NavCameraMode.FOLLOWING else NavCameraMode.OVERVIEW

    /** The Re-centre button shows whenever guiding is not following the user. */
    fun showRecentre(phase: NavPhase, mode: NavCameraMode): Boolean =
        phase == NavPhase.GUIDING && mode != NavCameraMode.FOLLOWING
}
