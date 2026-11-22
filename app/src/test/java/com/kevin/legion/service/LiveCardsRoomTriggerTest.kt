package com.kevin.legion.service

import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The whole live-card path with nothing called by hand (review of 79c642b9): [LiveCardReads.follow]
 * on Room's own change signal for the real tables, a write through the real tool, and the open card
 * changing in place. Invented names.
 */
@RunWith(RobolectricTestRunner::class)
class LiveCardsRoomTriggerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(LocalDateTime.of(2027, 3, 9, 10, 0).atZone(zone).toInstant(), zone))
        ConversationCards.end()
        ConversationCards.begin()
    }

    @After
    fun tearDown() {
        ConversationCards.end()
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun titles(): List<String> =
        (ConversationCards.state.value.cards.single() as ConversationCard.Timeline).items.map { it.title }

    @Test
    fun `a write to the day on screen reaches the card through Room's change signal`() = runBlocking {
        LiveToolbox.dispatch(context, "read_calendar", JSONObject().put("from", "2027-03-10").put("to", "2027-03-10"))
        val follow = launch(Dispatchers.IO) { LiveCardReads.follow(context) }
        try {
            // Let the observer register before the write: the signal carries no initial emission.
            delay(OBSERVER_SETTLE_MS)
            // Written straight to the table, past every tool, so only the change signal can refresh.
            com.kevin.legion.backend.EventsAppointmentWriter.addEvent(
                context, "Team supper",
                startsAtMs = LocalDateTime.of(2027, 3, 10, 19, 0).atZone(zone).toInstant().toEpochMilli(),
                endsAtMs = LocalDateTime.of(2027, 3, 10, 20, 0).atZone(zone).toInstant().toEpochMilli(),
                allDay = false, source = "legion", kind = com.kevin.legion.backend.EventKind.EVENT,
            )
            withTimeout(TIMEOUT_MS) {
                while (titles() != listOf("Team supper")) delay(POLL_MS)
            }
            assertEquals(listOf("Team supper"), titles())
        } finally {
            follow.cancel()
        }
    }

    private companion object {
        const val OBSERVER_SETTLE_MS = 300L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
