package com.kevin.legion.watches

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ProactiveRaiseRow
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.ProactiveUrgency
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.watches.FakeWatchesBackend.Companion.firing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.LocalTime
import java.util.Collections

/**
 * Integration of `feat/proactive-voice` (ADR 0082) and `feat/watches` (ADR 0083), 2026-11-10:
 * **one watch firing is exactly one delivery** whatever state the assistant is in. Watches used to
 * post its own notification when the assistant service was off; the bus now posts in that case
 * itself ([com.kevin.legion.service.ProactiveDelivery.route]'s `listenerUp`), so the watch path has
 * no fallback of its own and this test is what says the two did not end up both firing, or
 * neither.
 *
 * Runs the real [WatchFiringPoller] (fake engine) into the real [WatchPolling.deliver] and the real
 * [ProactiveBus.speakIfAllowed] with a fixed noon clock. "Spoken" is counted as an emit a collector
 * of [ProactiveBus.requestSpeak] received (that collector stands in for `AriaForegroundService`);
 * "posted" as a notification the shadow NotificationManager holds. Made-up facts only.
 */
@RunWith(RobolectricTestRunner::class)
class WatchDeliveryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val spoken = Collections.synchronizedList(mutableListOf<ProactiveBus.SpeakRequest>())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collector: Job? = null

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        CompanionProfile.saveGeminiKey(context, "test-key")
        GeminiKeyProvider.init(context)
        ConversationState.setBusy(false)
        ProactiveSettings.overrideForTest(
            master = true,
            on = setOf(ProactiveCategory.WATCHES, ProactiveCategory.CLAUDE_TASKS),
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        audio().ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun tearDown() {
        // Joined, and the listener count back to 0, so a later service-off test never sees this one.
        runBlocking {
            collector?.cancelAndJoin()
            withTimeout(WAIT_MS) { ProactiveBus.listeners.first { it == 0 } }
        }
        scope.cancel()
        context.getSharedPreferences("companion_profile", Context.MODE_PRIVATE).edit().clear().apply()
        GeminiKeyProvider.init(context)
        ProactiveSettings.resetForTest()
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun audio() = context.getSystemService(AudioManager::class.java)

    private fun posted(): Int = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size

    /** The assistant service's collector, standing in for `AriaForegroundService`. */
    private fun serviceRunning() = runBlocking {
        collector = scope.launch { ProactiveBus.requestSpeak.collect { spoken += it } }
        withTimeout(WAIT_MS) { ProactiveBus.listeners.first { it > 0 } }
    }

    private val noon = LocalTime.of(12, 0)
    private val backend = FakeWatchesBackend()
    private val delivered = mutableListOf<WatchDelivery>()
    private val poller = WatchFiringPoller(
        controller = { WatchesController(backend) },
        announce = { firings, more ->
            WatchPolling.deliver(context, WatchPolling.raise(firings, more)) { c, r ->
                ProactiveBus.speakIfAllowed(c, r, time = noon)
            }.also { delivered += it }
        },
        said = MapSaidFirings(),
        clockMs = { 1_000_000L },
    )

    /** One firing through the whole path; returns (spoken, posted, raise rows). */
    private fun fireOnce(): Triple<Int, Int, Int> = runBlocking {
        backend.pendingFirings += firing("f1", "A charge of 612.00 dollars at Example Hardware posted.")
        poller.maybePoll()
        // Let the collector take an emit already in the flow's buffer, then wait a little more so a
        // second (duplicate) delivery would be seen too.
        val end = System.currentTimeMillis() + WAIT_MS
        while (delivered.lastOrNull() == WatchDelivery.SPOKEN && spoken.isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(POLL_MS)
        }
        Thread.sleep(SETTLE_MS)
        val rows = CarDatabase.getDatabase(context).proactiveRaiseDao().mostRecent()?.let { 1 } ?: 0
        Triple(spoken.size, posted(), rows)
    }

    @Test
    fun `service running and free to speak - spoken once, nothing posted, acked spoken`() {
        serviceRunning()
        val (said, shade, rows) = fireOnce()
        assertEquals(listOf(WatchDelivery.SPOKEN), delivered)
        assertEquals(1, said)
        assertEquals(0, shade)
        assertEquals(1, rows)
        assertEquals(listOf(listOf("f1") to OUTCOME_SPOKEN), backend.acks)
    }

    @Test
    fun `service running and the phone silenced - posted once, never spoken`() {
        serviceRunning()
        audio().ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val (said, shade, _) = fireOnce()
        assertEquals(listOf(WatchDelivery.NOTIFIED), delivered)
        assertEquals(0, said)
        assertEquals(1, shade)
        assertEquals(listOf(listOf("f1") to OUTCOME_NOTIFIED), backend.acks)
    }

    @Test
    fun `service off - the bus posts it once, and the raise row exists for why-did-you-say-that`() {
        val (said, shade, rows) = fireOnce()
        assertEquals(listOf(WatchDelivery.NOTIFIED), delivered)
        assertEquals(0, said)
        assertEquals(1, shade)
        assertEquals(1, rows)
        val row = runBlocking { CarDatabase.getDatabase(context).proactiveRaiseDao().mostRecent() }
        assertEquals(ProactiveRaiseRow.DELIVERY_NOTIFIED, row?.delivery)
        assertEquals(listOf(listOf("f1") to OUTCOME_NOTIFIED), backend.acks)
    }

    @Test
    fun `service off - a Claude task result is posted by the same bus path, once`() = runBlocking {
        // The gap watches found for ClaudeResultPolling.announce: it raises through the same
        // speakIfAllowed, so the bus's no-listener route closes it too.
        val raise = ProactiveRaise(
            ruleId = "claude_task_result_7",
            category = ProactiveCategory.CLAUDE_TASKS,
            reason = "The test task finished.",
            facts = "The test task finished.",
            prompt = "Say the test task finished.",
        )
        val out = ProactiveBus.speakIfAllowed(context, raise, time = noon)
        assertEquals(true, out is ProactiveBus.RaiseOutcome.Notified)
        assertEquals(0, spoken.size)
        assertEquals(1, posted())
    }

    @Test
    fun `a spoken mail_reply line reaches the session marked read-through`() {
        serviceRunning()
        val out = runBlocking {
            WatchPolling.deliver(context, MailReplyAndroid.raise("Example Corp replied an hour ago.", 0L)) { c, r ->
                ProactiveBus.speakIfAllowed(c, r, time = noon)
            }
        }
        assertEquals(WatchDelivery.SPOKEN, out)
        val end = System.currentTimeMillis() + WAIT_MS
        while (spoken.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
        assertEquals(1, spoken.size)
        assertEquals(true, spoken.single().carriesReadThroughContent)
        assertEquals(0, posted())
    }

    @Test
    fun `a watch raise never carries an emergency alarm, and the bus refuses one on Watches`() = runBlocking {
        val raise = WatchPolling.raise(listOf(firing("f1", "Fact.")), 0)
        assertEquals(ProactiveUrgency.Ordinary, raise.urgency)
        assertEquals(ProactiveUrgency.Ordinary, MailReplyAndroid.raise("Example Corp replied an hour ago.", 0L).urgency)
        // Even if a future change set one, the bus refuses it before anything is delivered.
        val forged = raise.copy(urgency = ProactiveUrgency.Alarm(alertId = "test", severity = 2))
        assertEquals(
            ProactiveBus.RaiseOutcome.AlarmNotAllowed,
            ProactiveBus.speakIfAllowed(context, forged, time = noon),
        )
        assertEquals(
            WatchDelivery.SILENCED,
            WatchPolling.deliver(context, forged) { c, r -> ProactiveBus.speakIfAllowed(c, r, time = noon) },
        )
        assertEquals(0, posted())
    }

    @Test
    fun `quiet hours still apply to Watches with no cap - posted, not spoken`() = runBlocking {
        serviceRunning()
        val raise = WatchPolling.raise(listOf(firing("f1", "Fact.")), 0)
        val out = ProactiveBus.speakIfAllowed(context, raise, time = LocalTime.of(23, 30))
        assertEquals(true, out is ProactiveBus.RaiseOutcome.Notified)
        assertEquals(0, spoken.size)
        assertEquals(1, posted())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 100L
    }
}
