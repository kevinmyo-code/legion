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

class NavFollowFrameTest {
    private val h = 2000.0
    private val margin = 40.0
    private val side = 40.0

    @Test fun followPutsThePuckInTheLowerThirdAboveTheCollapsedSheet() {
        val p = NavFollowFrame.followPadding(h, 300.0, 260.0, margin, side)
        assertEquals(300.0, p.bottom, 0.001)
        // The SDK centres the puck in the padded rectangle.
        val puckY = (p.top + (h - p.bottom)) / 2
        assertEquals(NavFollowFrame.PUCK_SHARE * h, puckY, 0.001)
        assertEquals(side, p.left, 0.001)
    }

    @Test fun aTallSheetNeverSqueezesTheFrameBelowAFifthOfTheMap() {
        val p = NavFollowFrame.followPadding(h, 300.0, 900.0, margin, side)
        assertEquals(true, h - p.top - p.bottom >= 0.2 * h - 0.001)
    }

    @Test fun theTopPaddingNeverGoesUnderTheBanner() {
        val p = NavFollowFrame.followPadding(h, 700.0, 100.0, margin, side)
        assertEquals(true, p.top >= 740.0)
    }

    @Test fun beforeTheMapIsMeasuredPaddingIsJustTheChrome() {
        val p = NavFollowFrame.followPadding(0.0, 100.0, 200.0, margin, side)
        assertEquals(FramePadding(140.0, side, 240.0, side), p)
    }

    @Test fun overviewPaddingCapsTheBottomAtItsShare() {
        val p = NavFollowFrame.overviewPadding(h, 100.0, 1500.0, margin, side, 0.6)
        assertEquals(1200.0 + margin, p.bottom, 0.001)
    }

    @Test fun overviewTapTogglesOverviewAndFollowing() {
        assertEquals(NavCameraMode.OVERVIEW, NavFollowFrame.afterOverviewTap(NavCameraMode.FOLLOWING))
        assertEquals(NavCameraMode.OVERVIEW, NavFollowFrame.afterOverviewTap(NavCameraMode.FREE))
        assertEquals(NavCameraMode.FOLLOWING, NavFollowFrame.afterOverviewTap(NavCameraMode.OVERVIEW))
    }

    @Test fun recentreShowsOnlyWhileGuidingAndNotFollowing() {
        assertEquals(false, NavFollowFrame.showRecentre(NavPhase.GUIDING, NavCameraMode.FOLLOWING))
        assertEquals(true, NavFollowFrame.showRecentre(NavPhase.GUIDING, NavCameraMode.FREE))
        assertEquals(true, NavFollowFrame.showRecentre(NavPhase.GUIDING, NavCameraMode.OVERVIEW))
        assertEquals(false, NavFollowFrame.showRecentre(NavPhase.PREVIEW, NavCameraMode.FREE))
    }
}
