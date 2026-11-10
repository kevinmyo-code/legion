package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.openloops.FakeOpenLoopsBackend
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.DENTIST
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.REGISTRATION
import com.kevin.legion.openloops.OpenLoopsController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

/**
 * ADR 0085: answering a proposed to-do by voice rides `manage_item` (no new declaration). A yes or
 * a no is refused in a read-through turn, with the engine untouched; three sequences are pinned
 * through a real [GeminiLiveSession]'s flag the way `LiveSessionController.handleToolCall` passes it:
 * the spoken (wake-up) brief and the turn after it (refused), the `get_sitrep` turn itself
 * (refused), and a clean later turn (allowed). Made-up tasks only.
 */
@RunWith(RobolectricTestRunner::class)
class OpenLoopsToolboxTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = FakeOpenLoopsBackend()
    private lateinit var session: GeminiLiveSession

    @Before
    fun setUp() {
        OpenLoopsController.Hint.pending = null
        OpenLoopsToolbox.controllerFactory = { OpenLoopsController(backend = { engine }) }
        engine.proposals += listOf(DENTIST, REGISTRATION)
        session = GeminiLiveSession(context) {}
    }

    @After
    fun tearDown() {
        OpenLoopsToolbox.controllerFactory = OpenLoopsController::forContext
        OpenLoopsController.Hint.pending = null
    }

    private fun answer(args: String, readThrough: Boolean = session.readThroughToolTouchedThisTurn()): JSONObject? =
        runBlocking { OpenLoopsToolbox.dispatch(context, "manage_item", JSONObject(args), readThrough) }

    private fun assertRefusedAndUntouched(why: String) {
        assertTrue(why, session.readThroughToolTouchedThisTurn())
        val yes = answer("""{"action":"add","item":"call the dentist"}""")!!
        val no = answer("""{"action":"remove","item":"renew the registration by November 20"}""")!!
        assertFalse(yes.getBoolean("success"))
        assertFalse(no.getBoolean("success"))
        assertEquals(OpenLoopsToolbox.READ_THROUGH_REFUSAL, yes.getString("message"))
        assertTrue("$why: nothing tracked", engine.tracked.isEmpty())
        assertTrue("$why: nothing dismissed", engine.dismissed.isEmpty())
    }

    @Test
    fun `yes tracks the proposal it names`() {
        val r = answer("""{"action":"add","item":"call the dentist"}""", readThrough = false)!!
        assertTrue(r.getBoolean("success"))
        assertEquals(listOf("p1" to null), engine.tracked)
    }

    @Test
    fun `yes with a date he gave passes that date`() {
        answer("""{"action":"add","item":"call the dentist","date":"2026-11-13"}""", readThrough = false)
        assertEquals(listOf("p1" to LocalDate.of(2026, 11, 13)), engine.tracked)
    }

    @Test
    fun `no drops the proposal it names for good`() {
        val r = answer("""{"action":"remove","item":"renew the registration by November 20"}""", readThrough = false)!!
        assertTrue(r.getBoolean("success"))
        assertEquals(listOf("p2"), engine.dismissed)
    }

    @Test
    fun `anything that names no proposal falls through to manage_item untouched`() {
        assertNull(answer("""{"action":"add","item":"buy milk"}""", readThrough = false))
        assertNull(answer("""{"action":"tick","item":"call the dentist"}""", readThrough = false))
        assertNull(runBlocking { OpenLoopsToolbox.dispatch(context, "watch", JSONObject("{}"), false) })
        assertTrue(engine.tracked.isEmpty())
    }

    @Test
    fun `a real list line or a fuller add is never taken for a proposal`() {
        // "remove call the dentist office": a different task; manage_item handles the list line.
        assertNull(answer("""{"action":"remove","item":"call the dentist office"}""", readThrough = false))
        assertNull(answer("""{"action":"add","item":"call the dentist about the crown"}""", readThrough = false))
        // An add with a time, an appointment or a repeat is manage_item's, words and all.
        assertNull(answer("""{"action":"add","item":"call the dentist","date":"2026-11-13","time":"15:00"}""", false))
        val appointment = """{"action":"add","item":"call the dentist","date":"2026-11-13","kind":"appointment"}"""
        assertNull(answer(appointment, false))
        assertNull(answer("""{"action":"add","item":"call the dentist","repeat_kind":"weekly"}""", false))
        assertTrue(engine.tracked.isEmpty() && engine.dismissed.isEmpty())
    }

    @Test
    fun `two proposals named at once ask which and change nothing`() {
        engine.proposals += FakeOpenLoopsBackend.REGISTRATION_DEC
        val r = answer("""{"action":"add","item":"renew the registration"}""", readThrough = false)!!
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message").startsWith("Nothing was changed. Which one?"))
        assertTrue(engine.tracked.isEmpty())
    }

    @Test
    fun `the spoken brief's turn and the turn after it cannot answer a proposal`() {
        // What LiveSessionController marks for a raise that carries read-through content (the
        // wake-up brief, whose news is web text).
        session.markTurnReadThrough()
        assertRefusedAndUntouched("brief turn")
        session.startNextTurnReadThrough()
        assertRefusedAndUntouched("turn after the brief")
    }

    @Test
    fun `the get_sitrep turn itself cannot answer a proposal`() {
        val call = JSONObject().put("id", "c1").put("name", "get_sitrep").put("args", JSONObject())
        GeminiLiveSession::class.java.getDeclaredMethod("handleToolCall", JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(session, JSONObject().put("functionCalls", JSONArray().put(call)))
        ShadowLooper.idleMainLooper()
        assertRefusedAndUntouched("get_sitrep turn")
    }

    @Test
    fun `a clean later turn can answer`() {
        session.markTurnReadThrough()
        session.startNextTurnReadThrough()
        session.startNextTurnReadThrough()
        assertFalse(session.readThroughToolTouchedThisTurn())
        val r = answer("""{"action":"add","item":"call the dentist"}""")!!
        assertTrue(r.getBoolean("success"))
        assertEquals(listOf("p1" to null), engine.tracked)
    }

    @Test
    fun `through LiveToolbox dispatch, the same answer reaches the engine`() {
        val r = runBlocking {
            LiveToolbox.dispatch(
                context, "manage_item", JSONObject("""{"action":"add","item":"call the dentist"}"""), false,
            )
        }!!
        assertTrue(r.getBoolean("success"))
        assertEquals(listOf("p1" to null), engine.tracked)
    }
}
