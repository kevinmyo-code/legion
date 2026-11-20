package com.kevin.legion.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5-2: leaving the scan pane before the camera is ready never binds the camera afterwards. */
class CarLinkScanBindGateTest {
    @Test fun `a gate disposed before the camera is ready runs nothing`() {
        val gate = ScanBindGate()
        gate.dispose()
        var bound = false
        gate.runIfLive { bound = true }
        assertFalse(bound)
    }

    @Test fun `a live gate binds`() {
        var bound = false
        ScanBindGate().runIfLive { bound = true }
        assertTrue(bound)
    }
}
