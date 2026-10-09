package com.kevin.legion.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The guard on a suggestion row's tap (Kevin, 2026-10-09): only an http(s) page opens in the
 * browser. Anything else leaves the row untappable, so nothing pretends it is.
 */
class SuggestionPageUrlTest {

    @Test
    fun `http and https pages are accepted`() {
        assertEquals("https://example.test/riverfest", SuggestionMeta.openablePageUrl("https://example.test/riverfest"))
        assertEquals("http://example.test/a?b=c", SuggestionMeta.openablePageUrl("http://example.test/a?b=c"))
        assertEquals("HTTPS://Example.test/x", SuggestionMeta.openablePageUrl("HTTPS://Example.test/x"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.test/", SuggestionMeta.openablePageUrl("  https://example.test/ \n"))
    }

    @Test
    fun `null and blank are refused`() {
        assertNull(SuggestionMeta.openablePageUrl(null))
        assertNull(SuggestionMeta.openablePageUrl(""))
        assertNull(SuggestionMeta.openablePageUrl("   "))
    }

    @Test
    fun `non-web schemes are refused`() {
        assertNull(SuggestionMeta.openablePageUrl("javascript:alert(1)"))
        assertNull(SuggestionMeta.openablePageUrl("JavaScript:alert(1)"))
        assertNull(SuggestionMeta.openablePageUrl("file:///sdcard/secret.txt"))
        assertNull(SuggestionMeta.openablePageUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(SuggestionMeta.openablePageUrl("ftp://example.test/file"))
        assertNull(SuggestionMeta.openablePageUrl("mailto:someone@example.test"))
    }

    @Test
    fun `relative, hostless and unparseable values are refused`() {
        assertNull(SuggestionMeta.openablePageUrl("example.test/riverfest"))
        assertNull(SuggestionMeta.openablePageUrl("https://"))
        assertNull(SuggestionMeta.openablePageUrl("https:///path-only"))
        assertNull(SuggestionMeta.openablePageUrl("https://exa mple.test/"))
    }

    @Test
    fun `the url read from structured meta passes through the same guard`() {
        val meta = SuggestionMeta.parse("""{"url": "javascript:alert(1)", "venue": "Park"}""")
        assertNull(SuggestionMeta.openablePageUrl(meta?.url))
        val good = SuggestionMeta.parse("""{"url": "https://example.test/e"}""")
        assertEquals("https://example.test/e", SuggestionMeta.openablePageUrl(good?.url))
    }
}
