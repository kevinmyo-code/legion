package com.kevin.legion.clock

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.kevin.legion.ui.clock.ringStopLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Phantom alarm fix (2026-11-21): one owner of stopping. Every way to stop a ring (the in-app
 * button, the notification's Stop, a swipe, the voice stop, the ring screen, the 10-minute
 * auto-silence) goes through [RingControl], stops the sound and ends the service, and leaves one
 * audit line with the kind and the reason, never the alert's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingStopTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sound = FakeSound()
    private var service: ServiceController<RingService>? = null

    private class FakeSound : RingSound {
        var playing = false
        override fun start() { playing = true }
        override fun stop() { playing = false }
    }

    @Before
    fun setUp() {
        RingService.soundFactory = { sound }
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        service?.destroy()
        RingService.soundFactory = ::AlarmSound
    }

    private fun ring(vararg rings: RingRequest): RingService {
        val first = Robolectric.buildService(RingService::class.java, RingService.intent(context, rings.first()))
            .create().startCommand(0, 1)
        rings.drop(1).forEachIndexed { i, r -> first.get().onStartCommand(RingService.intent(context, r), 0, i + 2) }
        service = first
        assertTrue(sound.playing)
        assertEquals(rings.size, RingService.ringing.value.size)
        return first.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertSilentAndGone(s: RingService) {
        idle()
        assertTrue(RingService.ringing.value.isEmpty())
        assertFalse(sound.playing)
        assertTrue(shadowOf(s).isStoppedBySelf)
    }

    private val alert = RingRequest(RingKind.ALERT, 41, "Made-up County flood facts")
    private val timer = RingRequest(RingKind.TIMER, 42, "pasta")
    private val alarm = RingRequest(RingKind.ALARM, 43, "07:00")

    @Test
    fun `the in-app button stops every ring at once, the emergency included`() {
        val s = ring(alert, timer, alarm)
        assertEquals(3, RingControl.stopAll(context, RingStopReason.BUTTON))
        assertSilentAndGone(s)
    }

    @Test
    fun `the voice stop stops an emergency ring`() {
        val s = ring(alert)
        assertTrue(RingControl.voiceStops("Stop the alarm"))
        RingControl.stopAll(context, RingStopReason.VOICE)
        assertSilentAndGone(s)
    }

    @Test
    fun `the ring screen's Dismiss stops an emergency ring`() {
        val s = ring(alert)
        RingControl.stopAll(context, RingStopReason.SCREEN)
        assertSilentAndGone(s)
    }

    @Test
    fun `the notification's Stop stops every kind`() {
        listOf(alert, timer, alarm).forEach { r ->
            val s = ring(r)
            val stop = RingNotification.build(context, r).actions.first { it.title == "Stop" }.actionIntent
            deliver(shadowOf(stop).savedIntent)
            assertSilentAndGone(s)
        }
    }

    @Test
    fun `swiping the notification away stops the sound`() {
        val s = ring(alert)
        deliver(shadowOf(RingNotification.build(context, alert).deleteIntent).savedIntent)
        assertSilentAndGone(s)
        assertTrue(lines().any { it == "ring_stop kind=emergency reason=swipe" })
    }

    @Test
    fun `an unanswered ring silences itself after the auto-silence`() {
        val s = ring(alert)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(ClockDefaults.AUTO_SILENCE_MIN.toLong()))
        assertSilentAndGone(s)
        assertTrue(lines().any { it == "ring_stop kind=emergency reason=timeout" })
    }

    @Test
    fun `start and stop are logged with the kind and the reason, never the alert's words`() {
        ring(alert)
        RingControl.stopAll(context, RingStopReason.VOICE)
        idle()
        assertEquals(listOf("ring_start kind=emergency", "ring_stop kind=emergency reason=voice"), lines())
        assertFalse(ShadowLog.getLogs().any { "Made-up County" in it.msg })
    }

    @Test
    fun `stop words count only while something rings, and only as the whole turn`() {
        assertFalse(RingControl.voiceStops("stop the alarm"))
        listOf("Stop.", "Stop the alarm", "Alfred, stop the alarm", "turn it off please", "Dismiss", "okay stop")
            .forEach { assertTrue(it, RingControl.isStopPhrase(it, "Alfred")) }
        listOf("stop the music", "set an alarm for seven", "what is this alarm", "")
            .forEach { assertFalse(it, RingControl.isStopPhrase(it, "Alfred")) }
    }

    @Test
    fun `the in-app Stop bar says what rings, the emergency included, and is absent when nothing does`() {
        assertEquals(null, ringStopLine(emptyList()))
        assertEquals("Emergency alert ringing", ringStopLine(listOf(timer, alert)))
        assertEquals("Timer done ringing", ringStopLine(listOf(timer)))
    }

    private fun deliver(i: Intent) = ClockActionReceiver().onReceive(context, i)

    private fun lines(): List<String> = ShadowLog.getLogsForTag(RingAudit.TAG).map { it.msg }
}
