package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeWorkflowName
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.InMemoryClaudeModalMemory
import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.openloops.FakeOpenLoopsBackend
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.DENTIST
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.REGISTRATION
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.watches.FakeWatchesBackend
import com.kevin.legion.watches.WatchesController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Integrate 2026-11-10 (`integrate/2026-11-10-all`), check 2: watches (ADR 0083), worker workflows
 * (ADR 0067 amendment) and open loops (ADR 0085) each guard their own writes against a read-through
 * turn, written on three branches that could not see each other. This is the whole matrix on the
 * merged tree: every source that marks a turn, times every durable write a voice turn can make
 * across the three features plus memory, through a real [GeminiLiveSession]'s flag into the real
 * [LiveToolbox.dispatch] (as `LiveSessionController.handleToolCall` passes it), asserting the fake
 * engines saw NO write, then that a clean turn after it can make every one of them.
 *
 * Sources: a mail tool, a web-grounded answer, ask_engine's GitHub/web text, the `get_sitrep`
 * brief tool, a camera look and its carry turn, a spoken brief (wake-up) and the turn after it, a
 * `mail_reply` watch line and the turn after it. Writes: watch set, watch resume, a workflow start,
 * a yes and a no to a proposed to-do, a memory save. Made-up data only.
 */
@RunWith(RobolectricTestRunner::class)
class AllFeaturesReadThroughGuardTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var session: GeminiLiveSession
    private lateinit var watches: FakeWatchesBackend
    private lateinit var memories: FakeMemberMemoryBackend
    private lateinit var claude: FakeClaudeBackend
    private lateinit var loops: FakeOpenLoopsBackend

    @Before
    fun setUp() {
        fresh()
    }

    private fun fresh() {
        session = GeminiLiveSession(context) {}
        watches = FakeWatchesBackend()
        memories = FakeMemberMemoryBackend()
        claude = FakeClaudeBackend()
        loops = FakeOpenLoopsBackend()
        watches.watches += FakeWatchesBackend.watch("w1", "A paused balance watch")
            .copy(paused = true, status = "paused")
        claude.machines(machine("Alienware", workflows = listOf(ClaudeWorkflowName("tidy-downloads", "Tidies."))))
        loops.proposals += listOf(DENTIST, REGISTRATION)
        WatchesToolbox.controllerFactory = { WatchesController(watches) }
        MemoryToolbox.controllerFactory = {
            MemoryController(memories, InMemoryMemoryCache(), now = { FakeMemberMemoryBackend.NOW })
        }
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(claude) }
        ClaudeSessionToolbox.modalFactory = { ClaudeModalHolder(InMemoryClaudeModalMemory()) }
        OpenLoopsToolbox.controllerFactory = { OpenLoopsController(backend = { loops }) }
        OpenLoopsController.Hint.pending = null
    }

    @After
    fun tearDown() {
        WatchesToolbox.controllerFactory = WatchesController::forContext
        MemoryToolbox.controllerFactory = MemoryController::forContext
        ClaudeSessionToolbox.controllerFactory = ClaudeSessionController::forContext
        ClaudeSessionToolbox.modalFactory = ClaudeModalHolder::get
        OpenLoopsToolbox.controllerFactory = OpenLoopsController::forContext
        OpenLoopsController.Hint.pending = null
    }

    private fun dispatch(name: String, args: String): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, name, JSONObject(args), session.readThroughToolTouchedThisTurn())!!
    }

    private fun nextTurn() = session.startNextTurnReadThrough()

    private val writes = listOf(
        "watch set" to ("watch" to """{"action":"set","kind":"charge_over","dollars":50}"""),
        "watch resume" to ("watch" to """{"action":"resume","text":"w1"}"""),
        "workflow start" to ("claude_session" to """{"action":"workflow","request":"tidy my downloads"}"""),
        "proposal yes" to ("manage_item" to """{"action":"add","item":"call the dentist"}"""),
        "proposal no" to ("manage_item" to """{"action":"remove","item":"renew the registration by November 20"}"""),
        "memory save" to ("memory" to """{"action":"remember","text":"A made-up fact from outside text"}"""),
    )

    private fun assertEveryWriteRefused(source: String) {
        assertTrue("$source: the turn is marked", session.readThroughToolTouchedThisTurn())
        for ((what, call) in writes) {
            val out = dispatch(call.first, call.second)
            assertFalse("$source / $what was allowed: $out", out.getBoolean("success"))
        }
        assertTrue("$source: no watch created", watches.created.isEmpty())
        assertTrue("$source: the paused watch stayed paused", watches.watches.single().paused)
        assertTrue("$source: no workflow or task queued", claude.created.isEmpty() && claude.runs.isEmpty())
        assertTrue("$source: no proposal tracked", loops.tracked.isEmpty())
        assertTrue("$source: no proposal dismissed", loops.dismissed.isEmpty())
        assertTrue("$source: no memory written", memories.calls.none { it.startsWith("create") })
    }

    private fun assertEveryWriteAllowed(source: String) {
        assertFalse("$source: a clean turn", session.readThroughToolTouchedThisTurn())
        for ((what, call) in writes) {
            val out = dispatch(call.first, call.second)
            assertTrue("$source / $what refused in a clean turn: $out", out.getBoolean("success"))
        }
        assertEquals(1, watches.created.size)
        assertFalse(watches.watches.first { it.id == "w1" }.paused)
        assertEquals(1, claude.created.size)
        assertEquals(listOf("p1" to null), loops.tracked)
        assertEquals(listOf("p2"), loops.dismissed)
        assertEquals(1, memories.calls.count { it.startsWith("create") })
    }

    private fun toolCall(name: String) {
        val call = JSONObject().put("id", "c-$name").put("name", name).put("args", JSONObject())
        GeminiLiveSession::class.java.getDeclaredMethod("handleToolCall", JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(session, JSONObject().put("functionCalls", JSONArray().put(call)))
        ShadowLooper.idleMainLooper()
    }

    private fun serverContent(content: JSONObject) {
        GeminiLiveSession::class.java.getDeclaredMethod("handleServerContent", JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(session, content)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a mail tool's turn refuses every write, the next turn allows them`() {
        toolCall("read_mail")
        assertEveryWriteRefused("read_mail")
        nextTurn()
        assertEveryWriteAllowed("after read_mail")
    }

    @Test
    fun `a web-grounded answer's turn refuses every write`() {
        serverContent(JSONObject().put("groundingMetadata", JSONObject()))
        assertEveryWriteRefused("grounding")
        nextTurn()
        assertEveryWriteAllowed("after grounding")
    }

    @Test
    fun `ask_engine's GitHub or web text refuses every write`() {
        // What AskEngine's WebReadMark makes the runner call for github_status, web_search, brief_latest.
        session.markToolResultReadThrough("ask_engine")
        assertEveryWriteRefused("ask_engine github_status")
        nextTurn()
        assertEveryWriteAllowed("after ask_engine")
    }

    @Test
    fun `the get_sitrep brief's own turn refuses every write`() {
        toolCall("get_sitrep")
        assertEveryWriteRefused("get_sitrep")
        nextTurn()
        assertEveryWriteAllowed("after get_sitrep")
    }

    @Test
    fun `a camera look and its carry turn refuse every write`() {
        session.markLookSeen()
        assertEveryWriteRefused("look")
        nextTurn()
        assertEveryWriteRefused("look carry")
        nextTurn()
        assertEveryWriteAllowed("after the look's carry")
    }

    @Test
    fun `a spoken brief and the turn after it refuse every write`() {
        session.markTurnReadThrough()
        assertEveryWriteRefused("spoken brief")
        nextTurn()
        assertEveryWriteRefused("turn after the spoken brief")
        nextTurn()
        assertEveryWriteAllowed("two turns after the spoken brief")
    }

    @Test
    fun `a mail_reply line and the turn after it refuse every write`() {
        val raise = com.kevin.legion.watches.MailReplyAndroid.raise("Example Corp replied an hour ago.", 0L)
        assertTrue(raise.carriesReadThroughContent)
        session.markTurnReadThrough()
        assertEveryWriteRefused("mail_reply line")
        nextTurn()
        assertEveryWriteRefused("turn after mail_reply")
        nextTurn()
        assertEveryWriteAllowed("two turns after mail_reply")
    }

    /**
     * Check 3: a yes straight after the spoken wake-up brief is refused once (kept strict; loosening
     * it is Kevin's call). The refusal names both ways forward, saying it again works, and the Track
     * button (hands path, same controller) works whatever the voice turn was.
     */
    @Test
    fun `a yes right after the spoken brief is refused once, says what to do, then works`() {
        session.markTurnReadThrough() // the brief is spoken
        nextTurn() // his "yes" lands in the carry turn
        val refused = dispatch("manage_item", """{"action":"add","item":"call the dentist"}""")
        assertFalse(refused.getBoolean("success"))
        assertEquals(OpenLoopsToolbox.READ_THROUGH_REFUSAL, refused.getString("message"))
        assertTrue(refused.getString("message").contains("say it again"))
        assertTrue(refused.getString("message").contains("tap Track or Dismiss in Settings, Proactive speech"))
        assertTrue("nothing tracked, nothing dismissed", loops.tracked.isEmpty() && loops.dismissed.isEmpty())
        assertEquals("the proposal is still pending", 2, loops.proposals.size)

        nextTurn() // he says it again
        val again = dispatch("manage_item", """{"action":"add","item":"call the dentist"}""")
        assertTrue(again.toString(), again.getBoolean("success"))
        assertEquals(listOf("p1" to null), loops.tracked)

        // The Track button calls the same controller, with no read-through state at all.
        val tapped = runBlocking { OpenLoopsController(backend = { loops }).track(REGISTRATION.id) }
        assertTrue(tapped.words, tapped.ok)
        assertEquals(listOf("p1" to null, "p2" to null), loops.tracked)
    }
}
