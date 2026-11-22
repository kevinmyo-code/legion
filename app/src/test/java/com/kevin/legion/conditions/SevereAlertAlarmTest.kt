package com.kevin.legion.conditions

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.clock.ClockNotifier
import com.kevin.legion.clock.RingAudit
import com.kevin.legion.clock.RingService
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.EmergencyRingRules
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.ProactiveUrgency
import com.kevin.legion.service.SafetyAlarms
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.time.LocalTime
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * Integration 2026-11-10 (round 5 + ADR 0082): Kevin asked by name, "if any emergency in the area
 * is noted, alarm me". A Severe or Extreme NWS alert from the conditions feed is a Safety
 * [ProactiveUrgency.Alarm]: it RINGS in quiet hours or on a silenced phone, and one alert id rings
 * once unless its severity rises. Real [ProactiveBus.speakIfAllowed] and Room; the ring service
 * start is read off Robolectric's started-service queue. Made-up alerts, tests only.
 */
@RunWith(RobolectricTestRunner::class)
class SevereAlertAlarmTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val noon = LocalTime.of(12, 0)
    private val night = LocalTime.of(2, 30)

    private fun alert(id: String, severity: String, vararg refs: String) = event("Tornado Warning", id, severity, *refs)

    private fun event(event: String, id: String, severity: String, vararg refs: String) = SevereAlert(
        id, event, severity, "$event until 4 PM", "Example County", "NWS Example",
        "2026-11-10T16:00:00-06:00", refs.toList(),
    )

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        CompanionProfile.saveGeminiKey(context, "test-key")
        GeminiKeyProvider.init(context)
        ConversationState.setBusy(false)
        ProactiveSettings.overrideForTest(master = true, on = setOf(ProactiveCategory.SAFETY))
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("companion_profile", Context.MODE_PRIVATE).edit().clear().apply()
        GeminiKeyProvider.init(context)
        ProactiveSettings.resetForTest()
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun rang(): Boolean {
        val started = shadowOf(context as Application).nextStartedService ?: return false
        return started.component?.className == RingService::class.java.name
    }

    private fun deliver(a: SevereAlert, time: LocalTime) = runBlocking {
        ProactiveBus.speakIfAllowed(context, SevereAlertPolling.raise(a, "at home", null), time = time)
    }

    @Test
    fun `Severe and Extreme alerts carry a Safety alarm, anything else stays ordinary`() {
        val severe = SevereAlertPolling.raise(alert("a1", "Severe"), "at home", null)
        assertEquals(ProactiveCategory.SAFETY, severe.category)
        assertEquals(ProactiveUrgency.Alarm("a1", 1), severe.urgency)
        val extreme = SevereAlertPolling.raise(alert("a1", "Extreme"), "", null)
        assertEquals(ProactiveUrgency.Alarm("a1", 2), extreme.urgency)
        val update = SevereAlertPolling.raise(alert("a2", "Severe", "a1"), "", null)
        assertEquals(ProactiveUrgency.Alarm("a2", 1, listOf("a1")), update.urgency)
        assertEquals(ProactiveUrgency.Ordinary, SevereAlertPolling.raise(alert("a1", "Moderate"), "", null).urgency)
    }

    @Test
    fun `a Severe alert in quiet hours rings, and the same id does not ring twice`() {
        assertTrue(deliver(alert("q1", "Severe"), night) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
        assertEquals(ProactiveBus.RaiseOutcome.AlreadyAlarmed, deliver(alert("q1", "Severe"), night))
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `a Severe alert on a silenced phone rings in the daytime too`() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(deliver(alert("s1", "Severe"), noon) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
    }

    @Test
    fun `the same id rings again when its severity rises, and not when it falls`() {
        assertTrue(deliver(alert("r1", "Severe"), night) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
        assertTrue(deliver(alert("r1", "Extreme"), night) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
        assertEquals(ProactiveBus.RaiseOutcome.AlreadyAlarmed, deliver(alert("r1", "Severe"), night))
    }

    @Test
    fun `an NWS update of an alert that rang does not ring again, nor an update of the update`() {
        assertTrue(deliver(alert("u1", "Severe"), night) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
        assertEquals(ProactiveBus.RaiseOutcome.AlreadyAlarmed, deliver(alert("u2", "Severe", "u1"), night))
        assertEquals(ProactiveBus.RaiseOutcome.AlreadyAlarmed, deliver(alert("u3", "Severe", "u2"), night))
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `past the nightly cap a new alert is posted, never rung and never dropped`() {
        repeat(EmergencyRingRules.MAX_RINGS_PER_NIGHT) { i ->
            assertTrue(deliver(alert("c$i", "Severe"), night) is ProactiveBus.RaiseOutcome.Rang)
            assertTrue(rang())
        }
        assertTrue(deliver(alert("c-over", "Severe"), night) is ProactiveBus.RaiseOutcome.Notified)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `with LEGION's notifications blocked an emergency does not ring with nothing on screen`() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertTrue(deliver(alert("b1", "Severe"), night) !is ProactiveBus.RaiseOutcome.Rang)
        assertNull(shadowOf(context as Application).nextStartedService)
        assertTrue(skipLogged())
    }

    @Test
    fun `with the ring channel blocked an emergency does not ring either`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val blocked = NotificationManager.IMPORTANCE_NONE
        nm.createNotificationChannel(NotificationChannel(ClockNotifier.CHANNEL_RING, "Alarms ringing", blocked))
        assertTrue(deliver(alert("b2", "Severe"), night) !is ProactiveBus.RaiseOutcome.Rang)
        assertNull(shadowOf(context as Application).nextStartedService)
        assertTrue(skipLogged())
    }

    private fun skipLogged(): Boolean = ShadowLog.getLogsForTag(RingAudit.TAG)
        .any { it.msg == "ring_skip kind=emergency reason=notifications_blocked" }

    @Test
    fun `two poll loops admitting the same alert at once deliver it once`() {
        repeat(RACES) { i ->
            val alarm = ProactiveUrgency.Alarm("race$i", 1)
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<Boolean>())
            val loops = List(2) { Thread { start.await(); results.add(SafetyAlarms.admit(context, alarm)) } }
            loops.forEach(Thread::start)
            start.countDown()
            loops.forEach(Thread::join)
            assertEquals("race$i", listOf(false, true), results.sorted())
        }
    }

    @Test
    fun `the same alert id admitted again hours later, after a restart, does not ring again`() {
        // Production 11-13: a "Severe" Freeze alert rang again 5 to 6.5 hours later. The memory is
        // on disk (SharedPreferences, read on every admit, nothing held in the process), so a
        // restart reads what the first admit wrote.
        val alarm = ProactiveUrgency.Alarm("urn:oid:2.49.0.1.840.0.feedc0de.001.1", 1)
        val t0 = 1_800_000_000_000L
        assertTrue(SafetyAlarms.admit(context, alarm, t0))
        val stored = context.getSharedPreferences("safety_alarms", Context.MODE_PRIVATE)
        assertTrue(stored.contains(alarm.alertId))
        val restarted = RuntimeEnvironment.getApplication().applicationContext
        assertFalse(SafetyAlarms.admit(restarted, alarm, t0 + SIX_HOURS))
        assertFalse(SafetyAlarms.admit(restarted, alarm, t0 + 2 * SIX_HOURS))
    }

    @Test
    fun `a Freeze Warning at Severe is posted, never rung, at night and on a silenced phone`() {
        // The owner, 2026-11-21: ring only for dangerous events; freeze and the like just notify.
        assertTrue(deliver(event("Freeze Warning", "f1", "Severe"), night) is ProactiveBus.RaiseOutcome.Notified)
        assertNull(shadowOf(context as Application).nextStartedService)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(deliver(event("Freeze Warning", "f2", "Severe"), noon) is ProactiveBus.RaiseOutcome.Notified)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `a non-dangerous Severe alert is posted, not spoken, in the daytime too`() {
        assertTrue(deliver(event("Extreme Heat Warning", "h1", "Severe"), noon) is ProactiveBus.RaiseOutcome.Notified)
    }

    @Test
    fun `a Tornado Warning at Severe rings`() {
        assertTrue(deliver(event("Tornado Warning", "t1", "Severe"), night) is ProactiveBus.RaiseOutcome.Rang)
        assertTrue(rang())
    }

    @Test
    fun `an unknown event, even Extreme, is posted, never rung`() {
        val unknown = deliver(event("Made-up Hazard Warning", "x1", "Extreme"), night)
        assertTrue(unknown is ProactiveBus.RaiseOutcome.Notified)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `a Watch never rings, even a Tornado Watch at Extreme`() {
        assertTrue(deliver(event("Tornado Watch", "w1", "Extreme"), night) is ProactiveBus.RaiseOutcome.Notified)
        assertNull(shadowOf(context as Application).nextStartedService)
        assertFalse(SevereAlertWatcher.mayRing("Tornado Watch"))
        assertTrue(SevereAlertWatcher.mayRing(" tornado warning "))
    }

    private companion object {
        const val SIX_HOURS = 6L * 60 * 60 * 1000
        const val RACES = 20
    }
}
