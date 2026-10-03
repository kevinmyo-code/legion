package com.kevin.legion.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavCameraPlanTest {
    private fun d(p: NavPhase, m: NavCameraMode = NavCameraMode.FOLLOWING, routes: Boolean = true) =
        NavCameraPlan.decide(p, m, routes)

    @Test fun guidingFollowsOrOverviewsThroughTheSdkCamera() {
        assertEquals(CameraAction.SDK_FOLLOW, d(NavPhase.GUIDING))
        assertEquals(CameraAction.SDK_OVERVIEW, d(NavPhase.GUIDING, NavCameraMode.OVERVIEW))
        assertEquals(CameraAction.LEAVE_ALONE, d(NavPhase.GUIDING, NavCameraMode.FREE))
    }

    @Test fun aPreviewFitsTheRouteWhateverTheModeSoOverviewWorksAndFollowZoomIsNeverKept() {
        for (m in NavCameraMode.values()) assertEquals(CameraAction.FIT_ROUTE, d(NavPhase.PREVIEW, m))
    }

    @Test fun everyNonGuidingPhaseReleasesTheSdkCameraSoAFinishedTripCannotFightTheNextPreview() {
        for (p in NavPhase.values().filter { it != NavPhase.GUIDING && it != NavPhase.PREVIEW }) {
            assertEquals(CameraAction.RELEASE, d(p))
        }
    }

    @Test fun theSequenceGuidePreviewGuideEndPreviewEndsOnAFit() {
        val seq = listOf(NavPhase.GUIDING, NavPhase.ENDED, NavPhase.REQUESTING, NavPhase.PREVIEW)
            .map { d(it) }
        assertEquals(
            listOf(CameraAction.SDK_FOLLOW, CameraAction.RELEASE, CameraAction.RELEASE, CameraAction.FIT_ROUTE),
            seq,
        )
    }

    @Test fun withNoRoutesEvenAGuidingOrPreviewPhaseReleases() {
        assertEquals(CameraAction.RELEASE, d(NavPhase.GUIDING, routes = false))
        assertEquals(CameraAction.RELEASE, d(NavPhase.PREVIEW, routes = false))
    }
}
