package com.kevin.legion.dumb

import android.app.NotificationManager.Policy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDndGateway(
    var access: Boolean = true,
    var live: DndSnapshot,
    var failNextApply: Boolean = false,
) : DndGateway {
    val applied = mutableListOf<DndSnapshot>()
    override fun hasAccess() = access
    override fun read() = live
    override fun apply(snapshot: DndSnapshot) {
        if (failNextApply) {
            failNextApply = false
            throw SecurityException("access revoked")
        }
        applied += snapshot
        live = snapshot
    }
}

private class FakeSavedStore(var saved: DndSnapshot? = null) : DndSavedStore {
    override fun load() = saved
    override fun save(snapshot: DndSnapshot) {
        saved = snapshot
    }
    override fun clear() {
        saved = null
    }
}

class DumbDndTest {
    /** A user who runs with everything on and a policy of their own. */
    private val users = DndSnapshot(
        filter = 1, // INTERRUPTION_FILTER_ALL
        categories = Policy.PRIORITY_CATEGORY_MESSAGES or Policy.PRIORITY_CATEGORY_EVENTS,
        callSenders = Policy.PRIORITY_SENDERS_STARRED,
        messageSenders = Policy.PRIORITY_SENDERS_CONTACTS,
        suppressedEffects = 8,
        conversationSenders = 2,
    )
    private val gateway = FakeDndGateway(live = users)
    private val store = FakeSavedStore()
    private val dnd = DumbDnd(gateway, store)

    @Test
    fun `entering applies the priority policy and saves the user's own state`() {
        assertEquals(DndResult.Applied, dnd.enter())
        assertEquals(users, store.saved)
        assertEquals(DumbDnd.DUMB_POLICY, gateway.live)
    }

    @Test
    fun `the dumb policy is priority only, calls from anyone, no messages, no conversations`() {
        val p = DumbDnd.DUMB_POLICY
        assertEquals(2, p.filter) // INTERRUPTION_FILTER_PRIORITY
        assertTrue(p.categories and Policy.PRIORITY_CATEGORY_CALLS != 0)
        assertTrue(p.categories and Policy.PRIORITY_CATEGORY_ALARMS != 0)
        assertTrue(p.categories and Policy.PRIORITY_CATEGORY_MEDIA != 0)
        assertTrue(p.categories and Policy.PRIORITY_CATEGORY_SYSTEM != 0)
        assertEquals(0, p.categories and Policy.PRIORITY_CATEGORY_MESSAGES)
        assertEquals(0, p.categories and Policy.PRIORITY_CATEGORY_EVENTS)
        assertEquals(0, p.categories and Policy.PRIORITY_CATEGORY_REMINDERS)
        assertEquals(Policy.PRIORITY_SENDERS_ANY, p.callSenders)
        assertEquals(3, p.conversationSenders) // CONVERSATION_SENDERS_NONE
    }

    @Test
    fun `leaving restores the user's filter and policy exactly`() {
        dnd.enter()
        assertEquals(DndResult.Applied, dnd.exit())
        assertEquals(users, gateway.live)
        assertNull(store.saved)
    }

    @Test
    fun `entering twice does not overwrite the saved state with the dumb policy`() {
        dnd.enter()
        dnd.enter()
        assertEquals(users, store.saved)
        dnd.exit()
        assertEquals(users, gateway.live)
    }

    @Test
    fun `a save left by a crash is restored by a fresh instance`() {
        dnd.enter()
        val afterRestart = DumbDnd(gateway, store)
        assertEquals(DndResult.Applied, afterRestart.exit())
        assertEquals(users, gateway.live)
    }

    @Test
    fun `leaving with nothing saved does nothing`() {
        assertEquals(DndResult.NothingToRestore, dnd.exit())
        assertTrue(gateway.applied.isEmpty())
    }

    @Test
    fun `without access nothing is changed and it says so`() {
        gateway.access = false
        assertEquals(DndResult.NoAccess, dnd.enter())
        assertTrue(gateway.applied.isEmpty())
        assertNull(store.saved)
    }

    @Test
    fun `access lost between entering and leaving keeps the save for next time`() {
        dnd.enter()
        gateway.access = false
        assertEquals(DndResult.NoAccess, dnd.exit())
        assertNotNull(store.saved)
        gateway.access = true
        assertEquals(DndResult.Applied, dnd.exit())
        assertEquals(users, gateway.live)
    }

    @Test
    fun `a revoked grant that throws is reported, not crashed, and the save survives`() {
        dnd.enter()
        gateway.failNextApply = true
        assertEquals(DndResult.NoAccess, dnd.exit())
        assertNotNull(store.saved)
    }
}

class DumbModeEffectsTest {
    private val gateway = FakeDndGateway(live = DndSnapshot(1, 0, 0, 0, 0, -1))
    private val store = FakeSavedStore()
    private val digest = HeldDigest(FakeDigestPersistence())
    private val results = mutableListOf<DndResult>()
    private val effects = DumbModeEffects(
        isOn = MutableStateFlow(false),
        dnd = DumbDnd(gateway, store),
        digest = digest,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onResult = { results += it },
    )

    @Test
    fun `turning on clears the last session's digest and applies the policy`() {
        digest.record("a", 1L)
        digest.markPending()
        effects.step(previous = false, on = true)
        assertTrue(digest.state.value.entries.isEmpty())
        assertEquals(DumbDnd.DUMB_POLICY, gateway.live)
    }

    @Test
    fun `turning off restores Do Not Disturb and marks the digest pending`() {
        effects.step(previous = false, on = true)
        digest.record("a", 1L)
        effects.step(previous = true, on = false)
        assertEquals(DndSnapshot(1, 0, 0, 0, 0, -1), gateway.live)
        assertTrue(digest.state.value.pending)
    }

    @Test
    fun `turning off with nothing held shows no digest`() {
        effects.step(previous = false, on = true)
        effects.step(previous = true, on = false)
        assertFalse(digest.state.value.pending)
    }

    @Test
    fun `the first observation after a restart re-asserts without clearing the digest`() {
        digest.record("a", 1L)
        effects.step(previous = null, on = true)
        assertEquals(1, digest.state.value.entries.size)
        assertEquals(DumbDnd.DUMB_POLICY, gateway.live)
    }

    @Test
    fun `the first observation off after a crash restores a leftover save`() {
        effects.step(previous = null, on = true)
        effects.step(previous = null, on = false)
        assertEquals(DndSnapshot(1, 0, 0, 0, 0, -1), gateway.live)
        assertFalse(digest.state.value.pending)
    }
}
