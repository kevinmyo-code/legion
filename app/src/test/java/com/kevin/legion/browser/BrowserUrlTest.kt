package com.kevin.legion.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0071: what was said or typed becomes a web address or a Google search, never anything else. */
class BrowserUrlTest {
    @Test fun `words become a Google search`() {
        assertEquals(
            "https://www.google.com/search?q=joe%27s+pizza+menu",
            BrowserUrl.target("joe's pizza menu"),
        )
    }

    @Test fun `images asks Google Images, even for words that look like an address`() {
        assertEquals(
            "https://www.google.com/search?q=red+xj+stock+height&tbm=isch",
            BrowserUrl.target("red xj stock height", SearchKind.IMAGES),
        )
        assertEquals(
            "https://www.google.com/search?q=jeep.com&tbm=isch",
            BrowserUrl.target("jeep.com", SearchKind.IMAGES),
        )
    }

    @Test fun `a bare domain or path opens over https`() {
        assertEquals("https://example.com", BrowserUrl.target("example.com"))
        assertEquals("https://menu.joes-pizza.co.uk/dinner?x=1", BrowserUrl.target("menu.joes-pizza.co.uk/dinner?x=1"))
        assertEquals("https://localhost:8080", BrowserUrl.target("localhost:8080"))
        assertEquals("https://192.168.1.10/admin", BrowserUrl.target("192.168.1.10/admin"))
    }

    @Test fun `a full web address is kept as it is`() {
        assertEquals("https://example.com/menu", BrowserUrl.target("https://example.com/menu"))
        assertEquals("http://neverssl.com", BrowserUrl.target("http://neverssl.com"))
    }

    @Test fun `a number with a dot is a search, not an address`() {
        assertEquals("https://www.google.com/search?q=3.14", BrowserUrl.target("3.14"))
    }

    @Test fun `other schemes are never loaded, only searched for`() {
        val schemes = listOf("javascript:alert(1)", "file:///sdcard/x", "content://contacts", "intent://scan#Intent;end")
        for (bad in schemes) {
            val target = BrowserUrl.target(bad)!!
            assertTrue("$bad became $target", target.startsWith("https://www.google.com/search?q="))
        }
    }

    @Test fun `blank is nothing`() {
        assertNull(BrowserUrl.target("   "))
    }

    @Test fun `only http and https with a host count as web pages`() {
        assertTrue(BrowserUrl.isWebUrl("https://x.com/a"))
        assertTrue(BrowserUrl.isWebUrl("http://example.com"))
        assertFalse(BrowserUrl.isWebUrl("intent://foo"))
        assertFalse(BrowserUrl.isWebUrl("tel:123"))
        assertFalse(BrowserUrl.isWebUrl("about:blank"))
        assertFalse(BrowserUrl.isWebUrl("https://"))
    }
}
