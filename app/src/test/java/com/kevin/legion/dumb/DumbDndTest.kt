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
    val filterWrites = mutableListOf<Int>()
    override fun hasAccess() = access
    override fun read() = live
    override fun apply(snapshot: DndSnapshot, writeFilter: Boolean) {
        if (failNextApply) {
            failNextApply = false
            throw SecurityException("access revoked")
        }
        applied += snapshot
        if (writeFilter) filterWrites += snapshot.filter
        live = if (writeFilter) snapshot else snapshot.copy(filter = live.filter)
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

/**
 * The stuck-DND bug (Kevin, 2026-11-02): Quiet off, Matrix off, and a manual Do Not Disturb that
 * LEGION made still silencing calls and messages. Filters: 1 = ALL, 2 = PRIORITY.
 */
class DumbDndQuietTest {
    private val users = DndSnapshot(1, Policy.PRIORITY_CATEGORY_ALARMS, Policy.PRIORITY_SENDERS_STARRED,
        Policy.PRIORITY_SENDERS_STARRED, 0, 3)
    private var quietOn = false
    private var quietUserFilter: Int? = null
    private val gateway = FakeDndGateway(live = users)
    private val store = FakeSavedStore()
    private val dnd = DumbDnd(gateway, store, quietOn = { quietOn }, quietUserFilter = { quietUserFilter })

    /** Quiet's automatic rule on: the consolidated filter the gateway reads becomes PRIORITY. */
    private fun quietGoesOn(recorded: Int) {
        quietOn = true
        quietUserFilter = recorded
        gateway.live = gateway.live.copy(filter = 2)
    }

    @Test
    fun `entering Matrix while Quiet is on saves the user's filter, not Quiet's priority`() {
        quietGoesOn(recorded = 1)
        dnd.enter()
        assertEquals(1, store.saved?.filter)
    }

    @Test
    fun `Quiet on, Matrix on, Quiet off, Matrix off leaves no Do Not Disturb`() {
        quietGoesOn(recorded = 1)
        dnd.enter()
        quietOn = false
        quietUserFilter = null
        dnd.exit()
        assertEquals(listOf(2, 1), gateway.filterWrites) // dumb policy on, then ALL: never PRIORITY on the way out
        assertEquals(users, gateway.live)
    }

    @Test
    fun `leaving Matrix while Quiet is still on restores the policy but leaves the filter to Quiet`() {
        quietGoesOn(recorded = 1)
        dnd.enter()
        assertEquals(DndResult.Applied, dnd.exit())
        assertEquals(listOf(2), gateway.filterWrites) // ALL now would snooze Quiet's rule
        assertEquals(users.policyOnly(), gateway.live.policyOnly())
        assertNull(store.saved)
    }

    @Test
    fun `a user's own Do Not Disturb from before Quiet is still restored exactly`() {
        gateway.live = users.copy(filter = 2)
        quietGoesOn(recorded = 2)
        dnd.enter()
        dnd.exit()
        assertEquals(listOf(2, 2), gateway.filterWrites)
        assertEquals(users.copy(filter = 2), gateway.live)
    }

    private fun DndSnapshot.policyOnly() = copy(filter = 0)
}

class DndOwnershipTest {
    private val save = DndSnapshot(1, 0, 0, 0, 0, -1)

    @Test
    fun `the user's filter is the other mode's save first, then Quiet's record, then the live one`() {
        assertEquals(1, DndOwnership.userFilter(live = 2, matrixSaved = save, quietRecorded = 2))
        assertEquals(1, DndOwnership.userFilter(live = 2, matrixSaved = null, quietRecorded = 1))
        assertEquals(2, DndOwnership.userFilter(live = 2, matrixSaved = null, quietRecorded = null))
    }

    @Test
    fun `Quiet off clears a Do Not Disturb the user did not have`() {
        // The A25 on 2026-11-02: manual rule on (PRIORITY), enabler LEGION, user had none.
        assertTrue(DndOwnership.clearAfterQuietOff(matrixSaved = null, userFilter = 1, live = 2))
        assertTrue(DndOwnership.clearAfterQuietOff(matrixSaved = null, userFilter = 0, live = 3))
    }

    @Test
    fun `a Quiet started by an older build that recorded nothing still clears`() {
        assertTrue(DndOwnership.clearAfterQuietOff(matrixSaved = null, userFilter = null, live = 2))
    }

    @Test
    fun `Quiet off leaves the user's own Do Not Disturb alone`() {
        assertFalse(DndOwnership.clearAfterQuietOff(matrixSaved = null, userFilter = 2, live = 2))
    }

    @Test
    fun `Quiet off leaves Do Not Disturb to Matrix while Matrix holds a save`() {
        assertFalse(DndOwnership.clearAfterQuietOff(matrixSaved = save, userFilter = 1, live = 2))
    }

    @Test
    fun `nothing to clear when Do Not Disturb is already off`() {
        assertFalse(DndOwnership.clearAfterQuietOff(matrixSaved = null, userFilter = 1, live = 1))
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
