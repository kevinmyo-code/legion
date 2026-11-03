package com.kevin.legion.browser

import com.kevin.legion.dumb.FeedDomains
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0071's `browser` tool over a fake browser: outcome words only on a result, the password
 * refusal, stale numbers, and the ask-first handshake (one use, the same page, 90 seconds, the very
 * next call).
 */
class BrowserToolTest {
    private class FakePort : BrowserPort {
        override var currentUrl: String? = null
        var loadResult: (String) -> LoadOutcome = { LoadOutcome.Loaded(it, "Title of $it") }
        var page: PageSnapshot? = null
        val opened = mutableListOf<String>()
        val clicked = mutableListOf<Int>()
        val typed = mutableListOf<Triple<Int, String, Boolean>>()
        var clickResult: ClickOutcome = ClickOutcome.Clicked(null)
        var closes = true

        override suspend fun open(url: String): LoadOutcome {
            opened += url
            return loadResult(url).also { if (it is LoadOutcome.Loaded) currentUrl = it.url }
        }

        override suspend fun snapshot(): PageSnapshot? = page
        override suspend fun click(n: Int): ClickOutcome {
            clicked += n
            return clickResult
        }

        override suspend fun type(n: Int, text: String, submit: Boolean): TypeOutcome {
            typed += Triple(n, text, submit)
            return TypeOutcome.Typed(null)
        }

        override suspend fun scroll(direction: ScrollDirection) =
            currentUrl?.let { ScrollOutcome(atTop = false, atEnd = direction == ScrollDirection.DOWN) }
        override suspend fun back(): LoadOutcome = LoadOutcome.Failed("there is no earlier page")
        override suspend fun close(): Boolean = closes.also { if (it) currentUrl = null }
    }

    private val port = FakePort()
    private var now = 1_000_000L
    private var tokens = 0
    private val tool = BrowserTool(port, clock = { now }, newToken = { "t${++tokens}" })

    private fun call(vararg pairs: Pair<String, Any>): JSONObject = runBlocking {
        tool.run(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } })
    }

    private val shop = "https://shop.example/item/1"

    private fun onShop() {
        port.currentUrl = shop
        port.page = PageSnapshot(
            url = shop, title = "Item", text = "A nice item", textCut = false,
            elements = listOf(
                PageElement(1, ElementKind.LINK, "Reviews", host = "shop.example"),
                PageElement(2, ElementKind.FIELD, "Email"),
                PageElement(3, ElementKind.PASSWORD, "Password", sensitive = true),
                PageElement(4, ElementKind.BUTTON, "Buy now"),
                PageElement(5, ElementKind.BUTTON, "Reviews"),
            ),
            moreElements = false, paymentFields = false,
        )
    }

    @Test fun `open says opened only after the page loaded`() {
        val out = call("action" to "open", "text" to "red xj stock height", "kind" to "images")
        assertTrue(out.getBoolean("success"))
        assertEquals("https://www.google.com/search?q=red+xj+stock+height&tbm=isch", port.opened.single())
        assertTrue(out.getString("message").startsWith("Opened"))
    }

    @Test fun `a page that did not finish, failed or was blocked is not opened`() {
        port.loadResult = { LoadOutcome.TimedOut }
        call("action" to "open", "text" to "example.com").let {
            assertFalse(it.getBoolean("success"))
            assertFalse(it.getString("message").startsWith("Opened"))
        }
        port.loadResult = { LoadOutcome.Failed("net::ERR_NAME_NOT_RESOLVED") }
        assertTrue(call("action" to "open", "text" to "example.com").getString("message").contains("did not open"))
        val site = FeedDomains.siteFor("instagram.com")!!
        port.loadResult = { LoadOutcome.Blocked(FeedBlock(site, it, matrix = false)) }
        call("action" to "open", "text" to "instagram.com").let {
            assertFalse(it.getBoolean("success"))
            assertTrue(it.getString("message").contains("Instagram is blocked"))
        }
        port.loadResult = { LoadOutcome.NotOpen }
        assertFalse(call("action" to "open", "text" to "x").getBoolean("success"))
    }

    @Test fun `reading needs an open browser and returns numbered elements`() {
        assertFalse(call("action" to "read").getBoolean("success"))
        onShop()
        val out = call("action" to "read")
        assertTrue(out.getBoolean("success"))
        assertTrue(out.getString("elements").contains("4 button \"Buy now\""))
        assertEquals("A nice item", out.getString("text"))
    }

    @Test fun `a number needs a read of this same page first`() {
        onShop()
        assertTrue(call("action" to "click", "number" to 1).getString("message").contains("Read it first"))
        call("action" to "read")
        port.currentUrl = "https://shop.example/other"
        assertTrue(call("action" to "click", "number" to 1).getString("message").contains("Read it first"))
        assertTrue(port.clicked.isEmpty())
    }

    @Test fun `tapped only when an element was found and clicked`() {
        onShop()
        call("action" to "read")
        call("action" to "click", "number" to 1).let {
            assertTrue(it.getBoolean("success"))
            assertEquals("Tapped \"Reviews\".", it.getString("message"))
        }
        assertFalse(call("action" to "click", "number" to 99).getBoolean("success"))
        port.clickResult = ClickOutcome.Missing
        call("action" to "click", "number" to 1).let {
            assertFalse(it.getBoolean("success"))
            assertFalse(it.getString("message").startsWith("Tapped"))
        }
    }

    @Test fun `a click by words that match two things asks which, and taps nothing`() {
        onShop()
        val out = call("action" to "click", "text" to "reviews")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Say the number"))
        assertTrue(port.clicked.isEmpty())
    }

    @Test fun `a password field is refused, and nothing is typed`() {
        onShop()
        call("action" to "read")
        val out = call("action" to "type", "number" to 3, "text" to "hunter2")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("password"))
        assertTrue(port.typed.isEmpty())
    }

    @Test fun `typing into an ordinary field just types`() {
        onShop()
        call("action" to "read")
        assertTrue(call("action" to "type", "number" to 2, "text" to "kevin@example.com").getBoolean("success"))
        assertEquals(Triple(2, "kevin@example.com", false), port.typed.single())
    }

    @Test fun `buy asks first, and only the token on the next call taps it`() {
        onShop()
        call("action" to "read")
        val asked = call("action" to "click", "number" to 4)
        assertFalse(asked.getBoolean("success"))
        assertTrue(asked.getBoolean("needs_confirmation"))
        assertEquals("t1", asked.getString("confirm"))
        assertTrue(port.clicked.isEmpty())

        val done = call("action" to "click", "confirm" to "t1")
        assertTrue(done.getBoolean("success"))
        assertEquals(listOf(4), port.clicked)

        // One use.
        assertFalse(call("action" to "click", "confirm" to "t1").getBoolean("success"))
        assertEquals(listOf(4), port.clicked)
    }

    @Test fun `a yes after 90 seconds does nothing`() {
        onShop()
        call("action" to "read")
        call("action" to "click", "number" to 4)
        now += BrowserTool.CONFIRM_TTL_MS + 1
        val late = call("action" to "click", "confirm" to "t1")
        assertFalse(late.getBoolean("success"))
        assertTrue(late.getString("message").contains("too late"))
        assertTrue(port.clicked.isEmpty())
    }

    @Test fun `any other browser call in between drops the question`() {
        onShop()
        call("action" to "read")
        call("action" to "click", "number" to 4)
        call("action" to "scroll", "direction" to "down")
        assertFalse(call("action" to "click", "confirm" to "t1").getBoolean("success"))
        assertTrue(port.clicked.isEmpty())
    }

    @Test fun `a yes on a different page does nothing`() {
        onShop()
        call("action" to "read")
        call("action" to "click", "number" to 4)
        port.currentUrl = "https://shop.example/elsewhere"
        assertFalse(call("action" to "click", "confirm" to "t1").getBoolean("success"))
        assertTrue(port.clicked.isEmpty())
    }

    @Test fun `a confirmed type types what was asked about, not new words`() {
        port.currentUrl = "https://shop.example/checkout"
        port.page = PageSnapshot(
            "https://shop.example/checkout", "Checkout", "", false,
            listOf(PageElement(1, ElementKind.FIELD, "Address")), false, false,
        )
        call("action" to "read")
        val asked = call("action" to "type", "number" to 1, "text" to "1 Main St")
        assertTrue(asked.getBoolean("needs_confirmation"))
        assertTrue(port.typed.isEmpty())
        call("action" to "type", "confirm" to "t1", "text" to "something else")
        assertEquals(Triple(1, "1 Main St", false), port.typed.single())
    }

    @Test fun `close says closed only when the screen went`() {
        assertEquals("The browser was not open.", call("action" to "close").getString("message"))
        port.currentUrl = shop
        port.closes = false
        assertFalse(call("action" to "close").getBoolean("success"))
        port.closes = true
        assertEquals("Closed the browser.", call("action" to "close").getString("message"))
    }

    @Test fun `back with nowhere to go says so`() {
        port.currentUrl = shop
        val out = call("action" to "back")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("no earlier page"))
    }

    @Test fun `an unknown action does nothing and says so`() {
        assertFalse(call("action" to "dance").getBoolean("success"))
    }
}
