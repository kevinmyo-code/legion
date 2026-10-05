package com.kevin.legion.service

import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseDraft
import com.kevin.legion.purchases.PurchaseListing
import com.kevin.legion.purchases.PurchaseMatch
import com.kevin.legion.purchases.PurchaseOutcome
import com.kevin.legion.purchases.PurchasesBackend
import com.kevin.legion.purchases.PurchasesController
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `bought_log` voice tool (purchase-log ticket 08): result wording for each action and each
 * outcome state, and that a write is reported done only from an ok outcome (CLAUDE.md section 7's
 * outcome-verb rule). The tool calls the same [PurchasesController] the screens call (ADR 0035), so
 * the controller is built over a fake backend here and the wording is the shared
 * `PurchaseWording`'s.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseToolboxTest {

    private val context = RuntimeEnvironment.getApplication()
    private val today = LocalDate.of(2026, 10, 4).toEpochDay().toInt()
    private val sep20 = LocalDate.of(2026, 9, 20).toEpochDay().toInt()

    private fun entry(item: String, by: String? = "Mia") = Purchase(
        id = item, item = item, boughtOn = sep20, loggedBy = by, loggedByMe = false, store = null,
        priceCents = null, priceNote = null, quantityNote = null, isPrivate = false, source = "MANUAL",
    )

    private class FakeBackend(
        var create: PurchaseOutcome<Purchase>,
        var last: PurchaseOutcome<List<PurchaseMatch>>,
        var list: PurchaseOutcome<PurchaseListing>,
    ) : PurchasesBackend {
        val drafts = mutableListOf<PurchaseDraft>()
        override suspend fun lastBought(query: String) = last
        override suspend fun list(query: String?, limit: Int) = list
        override suspend fun create(draft: PurchaseDraft): PurchaseOutcome<Purchase> {
            drafts += draft
            return create
        }
        override suspend fun update(edit: com.kevin.legion.purchases.PurchaseEdit): PurchaseOutcome<Purchase> = create
        override suspend fun delete(id: String): PurchaseOutcome<Unit> = PurchaseOutcome.Ok(Unit)
    }

    private lateinit var backend: FakeBackend

    @Before
    fun setUp() {
        backend = FakeBackend(
            create = PurchaseOutcome.Ok(entry("shampoo", "Kevin")),
            last = PurchaseOutcome.Ok(emptyList()),
            list = PurchaseOutcome.Ok(PurchaseListing(emptyList(), false, null)),
        )
        PurchaseToolbox.controllerFactory = { PurchasesController(backend, { today }, { "sync" }) }
    }

    @After
    fun tearDown() {
        PurchaseToolbox.controllerFactory = PurchasesController::forContext
    }

    private fun call(vararg args: Pair<String, Any>): JSONObject = runBlocking {
        val json = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }
        PurchaseToolbox.dispatch(context, "bought_log", json)!!
    }

    @Test
    fun `another tool name is not this tool's`() = runBlocking {
        assertEquals(null, PurchaseToolbox.dispatch(context, "get_balance", JSONObject()))
    }

    @Test
    fun `a log that came back ok says it was logged, with the date and the hand-entered price`() {
        backend.create = PurchaseOutcome.Ok(
            entry("shampoo", "Kevin").copy(priceCents = 899, priceNote = "entered by hand"),
        )

        val r = call("action" to "log", "item" to "shampoo", "price_cents" to 899)

        assertTrue(r.getBoolean("success"))
        assertEquals("Logged \"shampoo\" as bought on Sep 20. Price \$8.99, entered by hand.", r.getString("message"))
        assertEquals(899L, backend.drafts.single().priceCents)
        assertEquals("a missing date is today", today, backend.drafts.single().boughtOn)
    }

    @Test
    fun `log reads the date, store and private flag it was given`() {
        call("action" to "log", "item" to "razors", "date" to "2026-09-20", "store" to "HEB", "private" to true)

        val draft = backend.drafts.single()
        assertEquals(sep20, draft.boughtOn)
        assertEquals("HEB", draft.store)
        assertTrue(draft.isPrivate)
    }

    @Test
    fun `an unreachable log says nothing was logged and success is false`() {
        backend.create = PurchaseOutcome.Unreachable("no engine")

        val r = call("action" to "log", "item" to "shampoo")

        assertFalse(r.getBoolean("success"))
        assertEquals("I can't reach the bought log right now, so I didn't log shampoo.", r.getString("message"))
    }

    @Test
    fun `a refused log and an unreadable reply never read as done`() {
        backend.create = PurchaseOutcome.Refused("not allowed")
        val refused = call("action" to "log", "item" to "shampoo")
        assertFalse(refused.getBoolean("success"))
        assertTrue(refused.getString("message").contains("I didn't log shampoo: not allowed"))

        backend.create = PurchaseOutcome.Unconfirmed("garbled")
        val unknown = call("action" to "log", "item" to "shampoo")
        assertFalse(unknown.getBoolean("success"))
        assertTrue(unknown.getString("message").contains("can't say whether shampoo was logged"))
    }

    @Test
    fun `a blank item and an unreadable date log nothing`() {
        val blank = call("action" to "log")
        assertFalse(blank.getBoolean("success"))
        val badDate = call("action" to "log", "item" to "shampoo", "date" to "last tuesday")
        assertFalse(badDate.getBoolean("success"))
        assertTrue(badDate.getString("message").contains("I didn't log shampoo"))
        assertTrue("nothing was sent", backend.drafts.isEmpty())
    }

    @Test
    fun `last names the exact entry it matched and who logged it`() {
        backend.last = PurchaseOutcome.Ok(
            listOf(PurchaseMatch(entry("Head & Shoulders shampoo"), exact = false, timesLogged = 3)),
        )

        val r = call("action" to "last", "item" to "shampoo")

        assertTrue(r.getBoolean("success"))
        assertEquals("You logged \"Head & Shoulders shampoo\" on Sep 20 (Mia).", r.getString("message"))
    }

    @Test
    fun `last lists several matches and says who is not recorded for a backfilled one`() {
        backend.last = PurchaseOutcome.Ok(
            listOf(
                PurchaseMatch(entry("shampoo"), exact = true, timesLogged = 2),
                PurchaseMatch(entry("dog shampoo", by = null), exact = false, timesLogged = 1),
            ),
        )

        val message = call("action" to "last", "item" to "shampoo").getString("message")

        val other = "Other matches: \"dog shampoo\" on Sep 20 (who logged it was not recorded)"
        assertTrue(message, message.contains(other))
    }

    @Test
    fun `last with no match is no record, never never-bought, and is still a successful read`() {
        val r = call("action" to "last", "item" to "conditioner")

        assertTrue(r.getBoolean("success"))
        val message = r.getString("message")
        assertTrue(message, message.startsWith("I have no record of buying conditioner"))
        assertFalse(message.contains("never bought", ignoreCase = true))
    }

    @Test
    fun `last against an unreachable log says so rather than answering no record`() {
        backend.last = PurchaseOutcome.Unreachable("no engine")

        val r = call("action" to "last", "item" to "shampoo")

        assertFalse(r.getBoolean("success"))
        val message = r.getString("message")
        assertTrue(message, message.startsWith("I can't reach the bought log right now"))
        assertFalse(message.contains("no record"))
    }

    @Test
    fun `recent lists newest first, and an unreachable log is not an empty list`() {
        backend.list = PurchaseOutcome.Ok(PurchaseListing(listOf(entry("shampoo"), entry("soap")), false, null))
        val ok = call("action" to "recent").getString("message")
        assertEquals("Most recent first: \"shampoo\" on Sep 20 (Mia); \"soap\" on Sep 20 (Mia).", ok)

        backend.list = PurchaseOutcome.Unreachable("no engine")
        val down = call("action" to "recent")
        assertFalse(down.getBoolean("success"))
        assertTrue(down.getString("message").contains("That is not the same as nothing being logged"))
    }

    @Test
    fun `the tool is declared to the live session and says the honest things`() {
        val live = LiveToolbox.declarations()
        val decl = (0 until live.length()).map { live.getJSONObject(it) }.first { it.getString("name") == "bought_log" }
        val description = decl.getString("description")

        assertTrue(description.contains("never 'never bought'"))
        assertTrue(description.contains("typed by hand"))
        assertTrue(description.contains("unreachable means nothing was logged"))
    }
}
