package com.kevin.legion.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0071: the page reader's numbers and words, and that a password never comes through. */
class PageSnapshotTest {
    private fun el(n: Int, kind: String, label: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("n", n).put("kind", kind).put("label", label).apply(extra)

    private val raw = JSONObject()
        .put("url", "https://www.google.com/search?q=xj")
        .put("title", "xj - Google Search")
        .put("text", "Results for xj")
        .put("textCut", true)
        .put("more", true)
        .put("payment", false)
        .put(
            "elements",
            JSONArray()
                .put(el(1, "link", "Images") { put("host", "www.google.com") })
                .put(el(2, "field", "Search") { put("value", "red xj"); put("form", true); put("search", true) })
                .put(el(3, "button", "Google Search") { put("submit", true); put("form", true); put("search", true) })
                .put(el(4, "password", "Password") { put("value", "hunter2"); put("sensitive", true) })
                .put(el(5, "field", "Card number") { put("value", "4111"); put("sensitive", true) })
                .put(el(6, "checkbox", "Remember me") { put("checked", true) })
                .put(el(7, "button", "Sign in") { put("submit", true); put("form", true) })
                .put(el(8, "mystery", "ignored")),
        )

    /** What evaluateJavascript hands back: the JSON string, itself JSON-quoted. */
    private val evaluated = JSONObject.quote(raw.toString())

    @Test fun `parses what evaluateJavascript returns`() {
        val page = PageSnapshot.parse(evaluated)!!
        assertEquals("xj - Google Search", page.title)
        assertTrue(page.textCut)
        assertTrue(page.moreElements)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), page.elements.map { it.n })
        assertEquals(ElementKind.PASSWORD, page.element(4)!!.kind)
    }

    @Test fun `a password or card value never survives, even if a page sends one`() {
        val page = PageSnapshot.parse(evaluated)!!
        assertNull(page.element(4)!!.value)
        assertNull(page.element(5)!!.value)
        assertTrue(page.element(5)!!.sensitive)
        val lines = page.elementLines()
        assertFalse(lines.contains("hunter2"))
        assertFalse(lines.contains("4111"))
    }

    @Test fun `each element is one numbered line in words`() {
        val lines = PageSnapshot.parse(evaluated)!!.elementLines().lines()
        assertEquals("1 link \"Images\" (www.google.com)", lines[0])
        assertEquals("2 field \"Search\" = \"red xj\"", lines[1])
        assertEquals("3 button \"Google Search\"", lines[2])
        assertEquals("4 password field \"Password\"", lines[3])
        assertEquals("5 field \"Card number\" (card field)", lines[4])
        assertEquals("6 checkbox \"Remember me\" (checked)", lines[5])
        assertEquals("7 button \"Sign in\" (submits a form)", lines[6])
    }

    @Test fun `anything that is not a page comes back as nothing`() {
        assertNull(PageSnapshot.parse(null))
        assertNull(PageSnapshot.parse("null"))
        assertNull(PageSnapshot.parse("\"not json\""))
    }

    @Test fun `words match an exact label first, then a unique partial one`() {
        val page = PageSnapshot.parse(evaluated)!!
        assertEquals(3, (page.match("google search") as MatchResult.One).element.n)
        assertEquals(6, (page.match("remember") as MatchResult.One).element.n)
        assertTrue(page.match("search") is MatchResult.One) // exact "Search" beats "Google Search"
        assertTrue(page.match("s") is MatchResult.Several)
        assertEquals(MatchResult.None, page.match("checkout"))
    }

    @Test fun `the reader script numbers at most forty elements and never reads a password value`() {
        val js = PageScripts.SNAPSHOT
        assertTrue(js.contains("MAX=${PageScripts.MAX_ELEMENTS}"))
        assertTrue(js.contains("data-legion-n"))
        // A value is read only for a non-sensitive field or menu; a password is always sensitive.
        assertTrue(js.contains("sensitive:sens||kind==='password'"))
        assertTrue(js.contains("&&!o.sensitive){o.value="))
    }

    @Test fun `the type script refuses a password field itself`() {
        assertTrue(PageScripts.type(2, "x", submit = false).contains("return 'password'"))
        assertTrue(PageScripts.type(2, "a\"b</script>", submit = false).contains("\"a\\\"b<\\/script>\""))
    }
}
