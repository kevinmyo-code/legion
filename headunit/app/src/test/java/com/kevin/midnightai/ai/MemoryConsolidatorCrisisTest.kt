package com.kevin.midnightai.ai

import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.EpisodicTurn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The read side of the crisis memory rule, run against real Room rows (ticket #458).
 *
 * [CrisisMemoryGateTest] pins the decision; this pins that [MemoryConsolidator] acts on it, which is
 * the layer that reaches rows a unit recorded before the session's write gate shipped. A crisis
 * session must leave the buffer with no durable memory behind it.
 *
 * Only crisis sessions are left pending on purpose: an ordinary one would reach `distill`, which
 * calls a model over the network, and this is a unit test.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryConsolidatorCrisisTest {

    private val db = CarDatabase.getDatabase(RuntimeEnvironment.getApplication())

    @After
    fun clear() = runBlocking {
        db.episodicTurnDao().deleteAll()
        db.companionMemoryDao().deleteAll()
    }

    @Test
    fun `a crisis session is deleted from the buffer and distilled into nothing`() = runBlocking {
        val dao = db.episodicTurnDao()
        dao.insert(turn("the heater is weak"))
        dao.insert(turn("i want to kill myself"))
        assertEquals(1, dao.pendingSessionIds().size)

        MemoryConsolidator.consolidatePending(RuntimeEnvironment.getApplication())

        assertTrue("the raw turns go, the earlier one included", dao.forSession(SESSION).isEmpty())
        assertTrue("and nothing durable is written", dao.pendingSessionIds().isEmpty())
        assertEquals(
            "no memory of the distress survives the sweep",
            0,
            db.companionMemoryDao().getRecent(VEHICLE, limit = 10).size,
        )
    }

    private fun turn(text: String) = EpisodicTurn(
        sessionId = SESSION, vehicleId = VEHICLE,
        role = EpisodicTurn.Role.DRIVER, text = text, timestamp = 1L,
    )

    private companion object {
        const val SESSION = "session-under-test"
        const val VEHICLE = "car:test"
    }
}
