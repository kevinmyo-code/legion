package com.kevin.legion.clock

import android.app.Notification
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phantom alarm fix (2026-11-21): the ring notification was posted silent with no button for an
 * emergency, so it never popped up and nothing on screen could stop it. Every kind now alerts
 * (heads-up, or the ring screen where Android allows it), carries Stop, and a swipe stops the sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun build(kind: RingKind, fullScreen: Boolean = true) =
        RingNotification.build(context, RingRequest(kind, 7, "made-up label"), fullScreen)

    @Test
    fun `no ring kind is posted silent`() {
        RingKind.entries.forEach { kind ->
            val n = build(kind)
            // NotificationCompat.setSilent files it in group "silent" with GROUP_ALERT_SUMMARY, which
            // suppresses the heads-up and the full-screen intent.
            assertNull("$kind group", n.group)
            assertNotEquals("$kind alert behaviour", Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        }
    }

    @Test
    fun `every ring kind carries Stop, and Snooze stays on the clock alarm only`() {
        assertEquals(listOf("Snooze", "Stop"), titles(build(RingKind.ALARM)))
        assertEquals(listOf("+1 min", "Stop"), titles(build(RingKind.TIMER)))
        assertEquals(listOf("Stop"), titles(build(RingKind.ALERT)))
    }

    @Test
    fun `Stop and a swipe both reach the one stop-everything receiver`() {
        RingKind.entries.forEach { kind ->
            val n = build(kind)
            val stop = n.actions.first { it.title == "Stop" }.actionIntent
            assertEquals(ClockActionReceiver.ACTION_STOP_RING, shadowOf(stop).savedIntent.action)
            assertNotNull("$kind delete intent", n.deleteIntent)
            assertEquals(ClockActionReceiver.ACTION_RING_SWIPED, shadowOf(n.deleteIntent).savedIntent.action)
        }
    }

    @Test
    fun `the ring screen is the full-screen intent only when Android allows it, the tap always opens it`() {
        RingKind.entries.forEach { kind ->
            assertNotNull(build(kind, fullScreen = true).fullScreenIntent)
            val denied = build(kind, fullScreen = false)
            assertNull("$kind falls back to a heads-up", denied.fullScreenIntent)
            assertNotNull(denied.contentIntent)
        }
    }

    private fun titles(n: Notification): List<String> = n.actions.orEmpty().map { it.title.toString() }
}
