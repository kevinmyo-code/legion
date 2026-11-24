package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a volume press travels, and when it may try a second way (Kevin in the car,
 * 2026-11-23: the bar moves and the sound does not). The whole selection is pure so every rule is
 * tested here; whether a path reaches the amp is car-only.
 */
class VolumePathLogicTest {

    @Test fun theDefaultIsTheMostSystemLikeCallAndNotTheOneThatFailed() {
        assertSame(VolumePath.SUGGESTED_STREAM, VolumePathLogic.DEFAULT)
        assertSame(VolumePathLogic.DEFAULT, VolumePathLogic.ORDER.first())
        // STREAM is what every press used until today, and what Kevin watched move the bar only.
        assertTrue(VolumePath.STREAM in VolumePathLogic.ORDER)
        assertEquals(VolumePath.entries.size, VolumePathLogic.ORDER.size)
    }

    @Test fun everyPathIsOfferedOnceWithItsOwnLabelAndItsCallNamed() {
        val labels = VolumePathLogic.ORDER.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        VolumePathLogic.ORDER.forEach {
            assertTrue(it.name, it.label.isNotBlank() && it.label == it.label.uppercase())
            assertTrue(it.name, it.what.contains("(") && it.what.length > 40)
        }
    }

    @Test fun nothingStoredOrAStaleNameFallsBackToTheDefault() {
        assertSame(VolumePathLogic.DEFAULT, VolumePathLogic.resolve(null))
        assertSame(VolumePathLogic.DEFAULT, VolumePathLogic.resolve(""))
        assertSame(VolumePathLogic.DEFAULT, VolumePathLogic.resolve("VENDOR_BROADCAST"))
    }

    @Test fun theDriversOwnPickIsWhatPressesUse() {
        VolumePathLogic.ORDER.forEach { assertSame(it, VolumePathLogic.resolve(it.name)) }
    }

    @Test fun onlyTheDefaultHasSomewhereToFallBackTo() {
        assertSame(VolumePath.STREAM, VolumePathLogic.fallbackFor(VolumePath.SUGGESTED_STREAM))
        VolumePathLogic.ORDER.drop(1).forEach { assertNull(it.name, VolumePathLogic.fallbackFor(it)) }
    }

    @Test fun aRefusedOrDeadDefaultPressTriesTheOlderCall() {
        assertTrue(fellBack(threw = true, moved = false))
        assertTrue(fellBack(threw = false, moved = false))
        assertFalse("a press that moved needs no second call", fellBack(threw = false, moved = true))
    }

    @Test fun thePickTheDriverMadeIsNeverSecondGuessed() {
        assertFalse(
            VolumePathLogic.shouldFallBack(
                path = VolumePathLogic.DEFAULT,
                chosenByDriver = true,
                threw = true,
                moved = false,
                atEnd = false,
                volumeFixed = false,
            ),
        )
    }

    @Test fun aFixedOutputOrAnEndOfRangePressNeverFallsBack() {
        // Every write is a documented no-op there, and VolumeFeedback already says so in words.
        assertFalse("fixed", fellBack(threw = false, moved = false, volumeFixed = true))
        assertFalse("at the end", fellBack(threw = false, moved = false, atEnd = true))
        // A call Android refused is still a refusal, fixed output or not: nothing was delivered.
        assertTrue(fellBack(threw = true, moved = false, volumeFixed = true))
    }

    @Test fun aPressHandedToAnotherProcessIsJudgedOnlyOnARefusal() {
        // MEDIA_SESSION and MEDIA_KEY read nothing back, so "it did not move" says nothing at all.
        VolumePathLogic.ORDER.filterNot { it.readsBackAtOnce }.forEach { path ->
            assertFalse(
                path.name,
                VolumePathLogic.shouldFallBack(path, false, threw = false, moved = false, atEnd = false, volumeFixed = false),
            )
        }
        assertTrue(VolumePath.MEDIA_SESSION.readsBackAtOnce.not() && VolumePath.MEDIA_KEY.readsBackAtOnce.not())
    }

    private fun fellBack(
        threw: Boolean,
        moved: Boolean,
        atEnd: Boolean = false,
        volumeFixed: Boolean = false,
    ): Boolean = VolumePathLogic.shouldFallBack(
        path = VolumePathLogic.DEFAULT,
        chosenByDriver = false,
        threw = threw,
        moved = moved,
        atEnd = atEnd,
        volumeFixed = volumeFixed,
    )
}
