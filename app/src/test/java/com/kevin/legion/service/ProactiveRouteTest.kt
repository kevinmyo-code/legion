package com.kevin.legion.service

import android.app.NotificationManager
import android.media.AudioManager
import com.kevin.legion.spokenbrief.HomeBrief
import com.kevin.legion.spokenbrief.WakeBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * ADR 0082's three delivery rulings, on their pure halves: the speech gate without the screen
 * (Ruling A), the one per-rule quiet-hours exemption (the wake-up brief), and the emergency alarm
 * (Ruling B). What a phone must prove - that a line plays with the screen off, that the ring starts
 * from the background - is owed on the device and is not claimed here.
 */
class ProactiveRouteTest {

    private val delivery = ProactiveDelivery

    private fun route(
        gateAllowsSpeech: Boolean = true,
        alarm: Boolean = false,
        quietHour: Boolean = false,
        silenced: Boolean = false,
        inMeeting: Boolean = false,
        listenerUp: Boolean = true,
    ) = delivery.routeFor(gateAllowsSpeech, alarm, quietHour, silenced, inMeeting, listenerUp)

    // ---------------------------------------------------------------- Ruling A: no screen check

    @Test
    fun `a line is spoken with nothing in the way - the screen is no longer an input`() {
        assertEquals(ProactiveDelivery.Route.SPEAK, route())
    }

    @Test
    fun `a silenced phone posts instead of speaking`() {
        assertEquals(ProactiveDelivery.Route.NOTIFY, route(silenced = true))
    }

    @Test
    fun `a live meeting still posts instead of speaking - Kevin kept that half`() {
        assertEquals(ProactiveDelivery.Route.NOTIFY, route(inMeeting = true))
    }

    @Test
    fun `nothing collecting the bus posts instead of emitting into nothing`() {
        assertEquals(ProactiveDelivery.Route.NOTIFY, route(listenerUp = false))
    }

    @Test
    fun `quiet hours still post a non-exempt raise`() {
        assertEquals(ProactiveDelivery.Route.NOTIFY, route(gateAllowsSpeech = false, quietHour = true))
    }

    @Test
    fun `silenced means ringer silent or vibrate, or any do-not-disturb filter`() {
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        assertFalse(delivery.isSilenced(AudioManager.RINGER_MODE_NORMAL, all))
        val normal = AudioManager.RINGER_MODE_NORMAL
        assertFalse(delivery.isSilenced(normal, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertTrue(delivery.isSilenced(AudioManager.RINGER_MODE_VIBRATE, all))
        assertTrue(delivery.isSilenced(AudioManager.RINGER_MODE_SILENT, all))
        assertTrue(delivery.isSilenced(normal, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(delivery.isSilenced(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_NONE))
        assertTrue(delivery.isSilenced(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    // ---------------------------------------------------------------- the per-rule quiet exemption

    private fun raise(category: ProactiveCategory, exempt: Boolean = false) = ProactiveRaise(
        ruleId = "test_rule",
        category = category,
        reason = "a test raise fired",
        facts = "test facts",
        prompt = "(System: say the thing.)",
        exemptFromQuietHours = exempt,
    )

    @Test
    fun `the wake-up brief speaks at 06-30 inside quiet hours, a digest without the flag does not`() {
        val sixThirty = LocalTime.of(6, 30)
        val wake = raise(ProactiveCategory.DIGEST, exempt = true)
        assertNull(ProactiveBus.decideOnHistory(wake, 1_000L, null, sixThirty))
        assertEquals(
            ProactiveBus.RaiseOutcome.QuietHours,
            ProactiveBus.decideOnHistory(raise(ProactiveCategory.DIGEST), 1_000L, null, sixThirty),
        )
    }

    @Test
    fun `only the wake-up brief sets the quiet-hours exemption - the home brief does not`() {
        assertTrue(WakeBrief.buildRaise("BRIEF facts", LocalTime.of(6, 30)).exemptFromQuietHours)
        assertFalse(HomeBrief.buildRaise("STILL TODAY nothing").exemptFromQuietHours)
        assertFalse("the default is no exemption", raise(ProactiveCategory.TIMING).exemptFromQuietHours)
    }

    @Test
    fun `the exemption is per rule - a declined wake-up brief is still suppressed`() {
        val now = 1_000_000_000L
        val declined = com.kevin.legion.data.local.ProactiveRaiseRow(
            ruleId = "test_rule", category = "digest", reason = "r", spokenAt = now - 1_000, declined = true,
        )
        assertEquals(
            ProactiveBus.RaiseOutcome.Suppressed,
            ProactiveBus.decideOnHistory(
                raise(ProactiveCategory.DIGEST, exempt = true), now, declined, LocalTime.of(6, 30),
            ),
        )
    }

    // ---------------------------------------------------------------- Ruling B: the emergency alarm

    @Test
    fun `an alarm rings at night even with the ringer on`() {
        assertEquals(ProactiveDelivery.Route.RING, route(alarm = true, quietHour = true))
    }

    @Test
    fun `an alarm rings on a silenced phone in the day`() {
        assertEquals(ProactiveDelivery.Route.RING, route(alarm = true, silenced = true))
    }

    @Test
    fun `an alarm in the day on a ringing phone is simply spoken`() {
        assertEquals(ProactiveDelivery.Route.SPEAK, route(alarm = true))
    }

    @Test
    fun `an alarm in a meeting with the ringer on is posted, like any safety line`() {
        assertEquals(ProactiveDelivery.Route.NOTIFY, route(alarm = true, inMeeting = true))
    }

    @Test
    fun `the ring needs no running assistant - only the line after dismiss does`() {
        assertEquals(ProactiveDelivery.Route.RING, route(alarm = true, quietHour = true, listenerUp = false))
    }

    @Test
    fun `one alarm per alert id, and again only when the severity rises`() {
        assertTrue(SafetyAlarms.isNewGiven(null, 2))
        assertFalse(SafetyAlarms.isNewGiven(2, 2))
        assertFalse(SafetyAlarms.isNewGiven(3, 2))
        assertTrue(SafetyAlarms.isNewGiven(2, 3))
    }

    @Test
    fun `a ring id is stable per alert and never negative`() {
        assertEquals(SafetyAlarms.ringId("alert-1"), SafetyAlarms.ringId("alert-1"))
        listOf("a", "alert-1", "zzzzzzzzzzzzzzzz", "").forEach { assertTrue(SafetyAlarms.ringId(it) >= 0) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an alarm without the feed's alert id cannot be built`() {
        ProactiveUrgency.Alarm(alertId = " ", severity = 1)
    }

    @Test
    fun `a raise is ordinary unless it says otherwise`() {
        assertEquals(ProactiveUrgency.Ordinary, raise(ProactiveCategory.SAFETY).urgency)
    }
}
