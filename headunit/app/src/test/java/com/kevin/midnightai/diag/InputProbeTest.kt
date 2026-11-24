package com.kevin.midnightai.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T2 (volume probe) and T9 (wheel key log): the pure half. */
class InputProbeTest {

    @Test fun ringKeepsTheNewestLinesUpToCapacity() {
        val r = ProbeRing(capacity = 3)
        (1..5).forEach { r.add(ProbeLine(it.toLong(), "l$it")) }
        assertEquals(listOf("l3", "l4", "l5"), r.snapshot().map { it.text })
    }

    @Test fun autoRepeatsAreDroppedButFirstDownAndUpAreKept() {
        assertTrue(ProbeFormat.shouldLogKey(action = ProbeFormat.ACTION_DOWN, repeatCount = 0))
        assertFalse(ProbeFormat.shouldLogKey(action = ProbeFormat.ACTION_DOWN, repeatCount = 1))
        assertFalse(ProbeFormat.shouldLogKey(action = ProbeFormat.ACTION_DOWN, repeatCount = 30))
        assertTrue(ProbeFormat.shouldLogKey(action = ProbeFormat.ACTION_UP, repeatCount = 0))
        // ACTION_MULTIPLE and anything else: one line, it is rare and worth seeing.
        assertTrue(ProbeFormat.shouldLogKey(action = 2, repeatCount = 0))
    }

    @Test fun keyLineCarriesEverythingNeededToMapAWheelKey() {
        val line = ProbeFormat.keyLine(
            name = "KEYCODE_MEDIA_NEXT", keyCode = 87, scanCode = 163, source = 0x101,
            action = ProbeFormat.ACTION_DOWN, repeatCount = 0, heldMs = null, device = "mcu-keys", handled = false,
        )
        assertEquals("KEYCODE_MEDIA_NEXT code=87 scan=163 src=0x101 down dev=mcu-keys passed-on", line)
    }

    @Test fun keyUpSaysHowLongItWasHeldAndAnUnnamedDeviceSaysSo() {
        val line = ProbeFormat.keyLine(
            name = "KEYCODE_VOLUME_UP", keyCode = 24, scanCode = 115, source = 0x101,
            action = ProbeFormat.ACTION_UP, repeatCount = 0, heldMs = 1_250L, device = null, handled = true,
        )
        assertEquals("KEYCODE_VOLUME_UP code=24 scan=115 src=0x101 up held=1250ms dev=? handled", line)
    }

    @Test fun observedLineNamesSourceIndexAndBranch() {
        assertEquals(
            "observed via=broadcast index=9/30 -> REASSERT (EXTERNAL_CHANGE)",
            ProbeFormat.observedLine("broadcast", index = 9, max = 30, decision = "REASSERT", reason = "EXTERNAL_CHANGE"),
        )
    }

    @Test fun unchangedObservationsAreNotLogged() {
        // The Settings.System observer fires on every settings write; an unchanged index is noise.
        assertFalse(ProbeFormat.shouldLogObservation("UNCHANGED"))
        // Our own write echoing back inside its quiet window: a held key would flood the log.
        assertFalse(ProbeFormat.shouldLogObservation("OWN_WRITE_WINDOW"))
        assertTrue(ProbeFormat.shouldLogObservation("EXTERNAL_CHANGE"))
        assertTrue(ProbeFormat.shouldLogObservation("VOICE_HOLD"))
        assertTrue(ProbeFormat.shouldLogObservation("FIRST_SIGHTING"))
    }

    @Test fun aHeldKeysRepeatWritesAreQuietButEveryOtherWriterIsLogged() {
        assertFalse(ProbeFormat.shouldLogWrite(ProbeFormat.QUIET_WRITER))
        listOf("key", "screen", "voice", "panel", "sync", "app").forEach { assertTrue(it, ProbeFormat.shouldLogWrite(it)) }
    }

    @Test fun mediaButtonLineNamesTheKeyAndTheSession() {
        assertEquals(
            "media-button KEYCODE_MEDIA_NEXT down session=mixtape",
            ProbeFormat.mediaButtonLine("KEYCODE_MEDIA_NEXT", ProbeFormat.ACTION_DOWN, "mixtape"),
        )
    }

    @Test fun keyUpLevelLine() {
        assertEquals("level after key up index=12/30", ProbeFormat.levelLine(12, 30))
    }

    @Test fun writeLineSaysWhoMovedItAndFromWhereToWhere() {
        assertEquals("write by=screen raise index 9->10/30", ProbeFormat.writeLine("screen", "raise", 9, 10, 30))
    }
}
