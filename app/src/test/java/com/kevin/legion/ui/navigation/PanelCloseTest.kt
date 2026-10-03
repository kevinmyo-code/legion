package com.kevin.legion.ui.navigation

import com.kevin.legion.navigation.NavPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCloseTest {
    @Test fun startAndEndCloseThePanel() {
        assertTrue(panelsCloseOn(NavPhase.PREVIEW, NavPhase.GUIDING))
        assertTrue(panelsCloseOn(NavPhase.GUIDING, NavPhase.ENDED))
        assertTrue(panelsCloseOn(NavPhase.GUIDING, NavPhase.ARRIVED))
    }

    @Test fun aPreviewRebuildingItselfKeepsItsPanel() {
        assertFalse(panelsCloseOn(NavPhase.PREVIEW, NavPhase.REQUESTING))
        assertFalse(panelsCloseOn(NavPhase.REQUESTING, NavPhase.PREVIEW))
        assertFalse(panelsCloseOn(NavPhase.GUIDING, NavPhase.GUIDING))
    }
}
