package com.kevin.legion.conditions

import android.app.Application
import android.content.Context
import android.media.AudioManager
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.clock.RingService
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.ProactiveUrgency
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.LocalTime

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

    private fun alert(id: String, severity: String) = SevereAlert(
        id, "Tornado Warning", severity, "Tornado Warning until 4 PM", "Example County", "NWS Example",
        "2026-11-10T16:00:00-06:00",
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
}
