package com.kevin.midnightai.ui.cruise

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T1: the middle panel's CAR / NAV state machine, the seam T5 plugs into. */
class MiddlePanelStateTest {

    private fun run(vararg events: MiddleEvent): MiddlePanelState =
        events.fold(MiddlePanelState()) { s, e -> MiddlePanelLogic.reduce(s, e) }

    @After fun reset() = MiddlePanel.resetForTest()

    @Test fun startsOnCarWithNoTrip() {
        assertEquals(MiddlePanelState(MiddleFace.CAR, tripActive = false, manual = false), MiddlePanelState())
    }

    @Test fun aTripStartingSwitchesToNav() {
        assertEquals(MiddleFace.NAV, run(MiddleEvent.TripStarted).face)
    }

    @Test fun aTripEndingSwitchesBackToCar() {
        val s = run(MiddleEvent.TripStarted, MiddleEvent.TripEnded)
        assertEquals(MiddleFace.CAR, s.face)
        assertFalse(s.tripActive)
    }

    @Test fun aManualPickWinsDuringTheTrip() {
        val s = run(MiddleEvent.TripStarted, MiddleEvent.Selected(MiddleFace.CAR))
        assertEquals(MiddleFace.CAR, s.face)
        assertTrue(s.tripActive)
        assertTrue(s.manual)
    }

    @Test fun aRepeatedTripStartIsNotANewTripEvent() {
        val s = run(MiddleEvent.TripStarted, MiddleEvent.Selected(MiddleFace.CAR), MiddleEvent.TripStarted)
        assertEquals(MiddleFace.CAR, s.face)
    }

    @Test fun theNextTripEventClearsTheManualPick() {
        val ended = run(MiddleEvent.TripStarted, MiddleEvent.Selected(MiddleFace.CAR), MiddleEvent.TripEnded)
        assertEquals(MiddleFace.CAR, ended.face)
        assertFalse(ended.manual)
        val again = MiddlePanelLogic.reduce(
            MiddlePanelLogic.reduce(ended, MiddleEvent.Selected(MiddleFace.NAV)),
            MiddleEvent.TripStarted,
        )
        assertEquals(MiddleFace.NAV, again.face)
        assertFalse(again.manual)
    }

    @Test fun aManualNavPickWithNoTripIsKeptAndAnEndWithNoTripIsIgnored() {
        val s = run(MiddleEvent.Selected(MiddleFace.NAV), MiddleEvent.TripEnded)
        assertEquals(MiddleFace.NAV, s.face)
        assertTrue(s.manual)
    }

    @Test fun aManualPickAfterTheTripEndedStaysUntilTheNextTrip() {
        val s = run(MiddleEvent.TripStarted, MiddleEvent.TripEnded, MiddleEvent.Selected(MiddleFace.NAV))
        assertEquals(MiddleFace.NAV, s.face)
        assertEquals(MiddleFace.NAV, MiddlePanelLogic.reduce(s, MiddleEvent.TripEnded).face)
    }

    @Test fun theProcessHolderAppliesTheSameRules() {
        MiddlePanel.tripStarted()
        assertEquals(MiddleFace.NAV, MiddlePanel.state.value.face)
        MiddlePanel.select(MiddleFace.CAR)
        assertEquals(MiddleFace.CAR, MiddlePanel.state.value.face)
        MiddlePanel.tripEnded()
        assertEquals(MiddlePanelState(), MiddlePanel.state.value)
    }

    @Test fun t1ShipsThePlaceholderNavHost() {
        assertEquals(NoTripNavHost, MiddlePanel.host)
    }
}
