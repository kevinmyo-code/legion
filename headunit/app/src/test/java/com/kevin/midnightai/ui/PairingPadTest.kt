package com.kevin.midnightai.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digit pad (review M5) fits the unit: 800x456 below the status bar, a Settings pane title and
 * the 260 dp prompt column beside it leave about 400 x 300 dp, and every key stays drive-size.
 */
class PairingPadTest {
    @Test fun theNumberPadKeysAreDriveSizeAt800x456() {
        val edge = PairingPad.keyEdge(widthDp = 400f, heightDp = 300f)
        assertTrue("edge $edge", edge != null && edge >= 56f)
        // 4 rows and 3 gaps must fit the height they were given.
        assertTrue(4 * edge!! + 3 * PairingPad.GAP_DP <= 300f)
    }

    @Test fun tooLittleRoomIsReportedNotSqueezed() {
        assertNull(PairingPad.keyEdge(widthDp = 150f, heightDp = 200f))
    }
}
