package com.kevin.legion.service

import com.kevin.legion.watches.FakeWatchesBackend
import com.kevin.legion.watches.FakeWatchesBackend.Companion.firing
import com.kevin.legion.watches.KIND_CHARGE_OVER
import com.kevin.legion.watches.WatchOutcome
import com.kevin.legion.watches.WatchPolling
import com.kevin.legion.watches.WatchWording
import com.kevin.legion.watches.WatchesController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0083: the `watch` tool's words for each action, the read-through gate on set and resume, and
 * the raise a firing becomes. Robolectric for `org.json`. Made-up merchants and facts only.
 */
@RunWith(RobolectricTestRunner::class)
class WatchesToolboxTest {

    private val backend = FakeWatchesBackend()
    private val controller = WatchesController(backend)

    private fun call(args: String, readThrough: Boolean = false): JSONObject = runBlocking {
        WatchesToolbox.run(controller, JSONObject(args), readThrough)
    }

    @Test
    fun `set sends typed params and says the engine set it`() {
        val out = call("""{"action":"set","kind":"charge_over","dollars":250,"text":"Example Hardware"}""")
        assertTrue(out.toString(), out.getBoolean("success"))
        assertTrue(out.getString("message").startsWith("Watch set on the engine"))
        val (kind, params) = backend.created.single()
        assertEquals(KIND_CHARGE_OVER, kind)
        assertEquals("25000", params["amount_cents"].toString())
    }

    @Test
    fun `a turn that read mail or the web cannot set or resume a watch`() {
        backend.watches += FakeWatchesBackend.watch("w1", "A charge watch")
        val set = call("""{"action":"set","kind":"grade_posted"}""", readThrough = true)
        assertFalse(set.getBoolean("success"))
        assertEquals(WatchWording.READ_THROUGH_REFUSAL, set.getString("message"))
        assertFalse(call("""{"action":"resume","id":"w1"}""", readThrough = true).getBoolean("success"))
        assertTrue(backend.created.isEmpty())
        // Quieter is always allowed.
        assertTrue(call("""{"action":"pause","text":"w1"}""", readThrough = true).getBoolean("success"))
        assertTrue(call("""{"action":"remove","id":"w1"}""", readThrough = true).getBoolean("success"))
    }

    @Test
    fun `the engine's refusal for a kind that fits nothing is passed on in its words`() {
        backend.refuseCreate = "No watch was set: 'stock_price' is not a kind of watch. LEGION can watch: ..."
        val out = call("""{"action":"set","kind":"stock_price"}""")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").startsWith("No watch was set"))
    }

    @Test
    fun `offline nothing is claimed`() {
        backend.offline = WatchOutcome.Unreachable("No engine address saved.")
        val out = call("""{"action":"set","kind":"grade_posted"}""")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").startsWith("Nothing was set"))
        assertFalse(call("""{"action":"list"}""").getBoolean("success"))
    }

    @Test
    fun `list carries ids so stop watching X has something to point at`() {
        backend.watches += FakeWatchesBackend.watch("w1", "A charge of \$500.00 or more")
        val out = call("""{"action":"list"}""")
        assertEquals("w1", out.getJSONArray("watches").getJSONObject(0).getString("id"))
        assertFalse(call("""{"action":"remove"}""").getBoolean("success"))
    }

    @Test
    fun `a batch of firings is one watches raise stating its facts and naming each watch`() {
        val raise = WatchPolling.raise(listOf(firing("abcdef123456", "A charge of \$612.00 at EXAMPLE HARDWARE.")), 2)
        assertEquals(ProactiveCategory.WATCHES, raise.category)
        assertTrue(raise.statesItsFacts)
        assertEquals("watch_firing_abcdef12", raise.ruleId)
        assertTrue(raise.reason.contains("Watch \"A charge of \$500.00 or more\" found: A charge of \$612.00"))
        assertTrue(raise.prompt.contains("There are 2 more on the Watches screen"))
        assertTrue(raise.prompt.contains("mention any other subject"))
    }
}
