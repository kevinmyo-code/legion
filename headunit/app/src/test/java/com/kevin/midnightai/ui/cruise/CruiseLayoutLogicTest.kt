package com.kevin.midnightai.ui.cruise

import com.kevin.midnightai.media.MusicDeckLogic
import com.kevin.midnightai.ui.GaugeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU-1 T1 geometry at both head-unit frames. This stands in for the screenshot tests the plan
 * asked for: the head unit build has no Roborazzi, and T1 may add no dependency.
 */
class CruiseLayoutLogicTest {

    private val frames = listOf(800f to 480f, 1024f to 600f)

    @Test fun at800x480TheColumnsMatchThePrototype() {
        val c = CruiseLayoutLogic.columns(800f, 480f)
        assertEquals(8f, c.pad, 0.01f)
        assertEquals(8f, c.gap, 0.01f)
        assertEquals(124f, c.rail, 0.01f)
        assertEquals(280f, c.music, 0.01f)
        assertEquals(364f, c.middle, 0.01f)
    }

    @Test fun columnsFillTheWidthAndEveryRailTileIsDriveSize() {
        frames.forEach { (w, h) ->
            val c = CruiseLayoutLogic.columns(w, h)
            assertEquals(w, 2 * c.pad + 2 * c.gap + c.rail + c.middle + c.music, 0.01f)
            assertTrue("$w tile ${c.tileWidth}", c.tileWidth >= MusicDeckLogic.TOUCH_MIN)
            // Room for the CAR / NAV pair (2 x 84) and the GPS readout beside it.
            assertTrue("$w middle ${c.middle}", c.middle >= 300f)
        }
    }

    @Test fun heroKeysMatchThePrototypeAt800() {
        val k = MusicDeckLogic.heroKeys(contentWidth = 264f, u = 8f)
        assertEquals(92f, k.height, 0.01f)
        assertEquals(76f, k.volumeWidth, 0.01f)
        assertEquals(64f, k.volumeHeight, 0.01f)
        assertEquals(75.2f, k.mid, 0.2f)
        assertEquals(97.6f, k.big, 0.2f)
        assertTrue(k.meterFits)
    }

    @Test fun heroKeysFitTheCardAndAreAboutThreeTimesTodayAtBothFrames() {
        frames.forEach { (w, h) ->
            val u = CruiseLayoutLogic.unit(w, h)
            val content = CruiseLayoutLogic.columns(w, h).music - 2 * u
            val k = MusicDeckLogic.heroKeys(content, u)
            assertTrue("$w row ${2 * k.mid + k.big + 2 * u} > $content", 2 * k.mid + k.big + 2 * u <= content + 0.01f)
            listOf(k.mid, k.big, k.height, k.volumeWidth, k.volumeHeight).forEach {
                assertTrue("$w key $it", it >= MusicDeckLogic.TOUCH_MIN)
            }
            assertTrue(k.meterFits)
            // The old previous/next key is a max(6u, 48) circle; the new one has about 3x its area.
            val old = maxOf(6f * u, 48f)
            assertTrue("$w area ratio", (k.mid * k.height) / (old * old) >= 2.5f)
        }
    }

    @Test fun thePlaylistsFaceShowsThreeRowsOfTilesWithoutScrollingAtBothFrames() {
        frames.forEach { (w, h) ->
            val u = CruiseLayoutLogic.unit(w, h)
            val card = h - 2 * u
            assertTrue(
                "$w face ${CruiseLayoutLogic.musicFaceHeight(card, u)}",
                CruiseLayoutLogic.musicFaceHeight(card, u) >= CruiseLayoutLogic.playlistFaceMin(u),
            )
        }
    }

    // --- Middle panel words --------------------------------------------------------------

    private val beacon = "midnight-beacon"

    @Test fun gpsFromThePhoneWithAFreshFix() {
        assertEquals(
            MiddlePanelText.Gps("GPS from phone", "fix 1 s ago", stale = false),
            MiddlePanelText.gps(beacon, 1_400L, beacon),
        )
    }

    @Test fun gpsOnTheUnitItself() {
        assertEquals("GPS on this unit", MiddlePanelText.gps("gps", 500L, beacon).source)
    }

    @Test fun aNetworkFixIsNeverCalledGps() {
        assertEquals("Network location", MiddlePanelText.gps("network", 500L, beacon).source)
        assertEquals("Network location", MiddlePanelText.gps("fused", 500L, beacon).source)
    }

    @Test fun aNegativeAgeIsNoFixAndShowsNoSpeed() {
        // A cached fix stamped before this boot, or a clock jump.
        assertEquals(MiddlePanelText.Gps("No GPS", "no fix yet", stale = true), MiddlePanelText.gps(beacon, -2_000L, beacon))
        assertEquals(null, MiddlePanelText.speedMph(28.6f, -2_000L))
    }

    @Test fun anOldFixReadsAsLost() {
        assertEquals(
            MiddlePanelText.Gps("GPS from phone", "lost 12 s", stale = true),
            MiddlePanelText.gps(beacon, 12_000L, beacon),
        )
        assertEquals("lost 5 min", MiddlePanelText.gps(beacon, 300_000L, beacon).age)
        assertEquals("lost over an hour", MiddlePanelText.gps(beacon, 7_200_000L, beacon).age)
    }

    @Test fun speedBlanksAtTheSameMomentTheHeaderSaysLost() {
        assertEquals(64, MiddlePanelText.speedMph(28.6f, 900L))
        assertEquals(64, MiddlePanelText.speedMph(28.6f, 3_000L))
        assertEquals(false, MiddlePanelText.gps(beacon, 3_000L, beacon).stale)
        assertEquals(null, MiddlePanelText.speedMph(28.6f, 3_001L))
        assertEquals(true, MiddlePanelText.gps(beacon, 3_001L, beacon).stale)
        assertEquals(null, MiddlePanelText.speedMph(null, 900L))
        assertEquals(null, MiddlePanelText.speedMph(28.6f, null))
    }

    @Test fun theNavPlaceholderInvitesNoVoiceCommandThatDoesNotExist() {
        val text = (MiddlePanelText.NAV_PLACEHOLDER_TITLE + " " + MiddlePanelText.NAV_PLACEHOLDER_LINE).lowercase()
        assertTrue(text, "say" !in text && "navigate to" !in text)
    }

    @Test fun noFixSaysSo() {
        assertEquals(MiddlePanelText.Gps("No GPS", "no fix yet", stale = true), MiddlePanelText.gps(null, null, beacon))
    }

    @Test fun carGaugesAreTheCruisePicksAtMostFour() {
        val all = listOf("coolant", "volts", "iat", "load", "fuel", "baro").map { GaugeReading(it, it.uppercase(), "1") }
        val picked = MiddlePanelText.carGauges(all, setOf("volts", "load", "coolant", "fuel", "baro"))
        assertEquals(listOf("coolant", "volts", "load", "fuel"), picked.map { it.key })
    }

    @Test fun storedCodeLine() {
        assertNull(MiddlePanelText.storedCodes(emptyList(), emptyMap()))
        assertEquals(
            "1 stored code · P0128 Coolant thermostat",
            MiddlePanelText.storedCodes(listOf("P0128"), mapOf("P0128" to "Coolant thermostat")),
        )
        assertEquals("2 stored codes · P1234", MiddlePanelText.storedCodes(listOf("P1234", "P0420"), emptyMap()))
    }
}
