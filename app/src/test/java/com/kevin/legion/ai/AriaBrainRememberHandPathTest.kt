package com.kevin.legion.ai

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Command-center ticket 11: `remember` by hand. [ui.companions.MemoryScreen]'s `AddMemoryDialog`
 * calls [AriaBrain.remember] directly - the SAME call `service/LiveToolbox.kt`'s `remember` case
 * makes (`AriaBrain.get(context).remember(args.optString("text"))`), traced before writing that
 * dialog. This pins [AriaBrain.remember]'s own behaviour so a future change to it is caught
 * regardless of which of the two callers exercises it - `LiveToolbox` has no equivalent test of
 * its own single-line dispatch (see [com.kevin.legion.service.RememberReadThroughGateTest], which
 * covers the GATE, not the write `remember` makes once past it).
 *
 * Not a Compose test - this repo has no Compose-under-Robolectric harness (same gap
 * `ui/body/BodyWriteDialogs.kt` ships with zero dialog tests of its own, CLAUDE.md §10's
 * "DB-write paths... judged not worth chasing" for the UI layer specifically). What IS testable
 * without one, and is the actual load-bearing claim, is that [AriaBrain.remember] - the exact
 * function object the dialog calls - behaves the same way here as it does under the voice path.
 */
@RunWith(RobolectricTestRunner::class)
class AriaBrainRememberHandPathTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // AriaBrain is ALSO a plain Kotlin object singleton (`AriaBrain.get`'s own doc comment:
        // "shared instance"), holding a reference to a PREVIOUS test method's now-torn-down
        // Robolectric application the same way CarDatabase's own INSTANCE does - RoomTestReset's
        // own doc comment explains the mechanism this mirrors. Only one @Test method below reaches
        // AriaBrain, so this reset is defensive (a second test added later would need it), not
        // covering an observed failure in this file today.
        val field = AriaBrain::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `remember writes one MemoryEntry with no category field, and dedups a repeat`() = runBlocking {
        val brain = AriaBrain.get(context)
        val db = CarDatabase.getDatabase(context)

        brain.remember("The Jeep's oil filter is a Fram PH3614.")
        val afterFirst = db.memoryDao().getRecent(10)
        assertEquals(1, afterFirst.size)
        assertEquals("The Jeep's oil filter is a Fram PH3614.", afterFirst.single().text)

        // Same text again - dedup touches recency rather than inserting a second row (see
        // AriaBrain.remember's own doc comment). A hand-typed entry through the exact same
        // function gets the exact same dedup behaviour, not a second insert path.
        brain.remember("The Jeep's oil filter is a Fram PH3614.")
        val afterSecond = db.memoryDao().getRecent(10)
        assertEquals("a repeat must dedup, never double-insert", 1, afterSecond.size)

        // The load-bearing finding this dialog's own doc comment records: MemoryEntry carries no
        // category column at all, so there is nothing for a hand-typed dialog to prompt for beyond
        // the text itself - proven here by construction (MemoryEntry's own constructor has no such
        // parameter) rather than merely asserted in a comment.
        val entry = afterSecond.single()
        // `$stable` is a Compose-compiler-injected stability marker present on EVERY Kotlin class
        // in this module once the Compose compiler plugin runs (confirmed via javap, not assumed -
        // MemoryEntry is a plain Room @Entity with no Composable in sight), so it is filtered out
        // rather than added to the expected set, which would misstate what's actually a DOMAIN field.
        val realFields = com.kevin.legion.data.local.MemoryEntry::class.java.declaredFields
            .map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertTrue(
            "MemoryEntry must carry only text/timestamp/syncId - no category field to select",
            realFields == setOf("id", "text", "timestamp", "syncId"),
        )
    }

    @Test
    fun `blank text is a no-op ack, never an empty row`() = runBlocking {
        val brain = AriaBrain.get(context)
        val db = CarDatabase.getDatabase(context)

        val ack = brain.remember("   ")

        assertTrue("still returns an acknowledgement, never silence", ack.isNotBlank())
        assertTrue("a blank remember must never write a row", db.memoryDao().getRecent(10).isEmpty())
    }
}
