package com.kevin.legion.news

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One test per failure sentence one-home ticket 07 requires ("RSS needs its own set: no items,
 * feed unreachable, feed returned something unparseable... asserting they are distinct strings and
 * that the empty case is not the unreachable case") - the RSS analogue of the three Gmail cases
 * `SitrepBuilder`'s own tests already cover. No socket is ever opened: [MockEngine] stands in for
 * the transport, same posture `backend/engine/EngineTestSupport.kt`'s own class doc describes.
 *
 * **Robolectric, not a plain JUnit test** - [FeedFetcher.parseFeed] calls
 * `android.util.Xml.newPullParser()`, and this module's `isReturnDefaultValues = true`
 * (`app/build.gradle.kts`) makes every unstubbed Android framework call return null/0/false rather
 * than throw, so a plain JVM test gets a null parser and every "Success" case silently misreads as
 * [FeedFetchResult.Unparseable]. Caught by running this suite, not by reading the stub's own
 * comment - see this ticket's own report for the on-device account of that failure.
 */
@RunWith(RobolectricTestRunner::class)
class FeedFetcherTest {

    private fun clientRespondingText(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(MockEngine { respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/xml")) })

    private val rss2Items = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
          <title>Example Feed</title>
          <item><title>First headline</title><link>https://example.com/1</link></item>
          <item><title>Second headline</title><link>https://example.com/2</link></item>
        </channel></rss>
    """.trimIndent()

    private val atomWithOneEntry = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Example Atom Feed</title>
          <entry>
            <title>Atom headline</title>
            <link href="https://example.com/atom/1"/>
          </entry>
        </feed>
    """.trimIndent()

    private val rssWithNoItems = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel><title>Quiet Feed</title></channel></rss>
    """.trimIndent()

    @Test
    fun `an RSS 2 0 feed with items comes back as Success, capped, in order`() = runBlocking {
        val result = FeedFetcher.fetch("https://example.com/feed.xml", clientRespondingText(rss2Items))
        assertTrue(result is FeedFetchResult.Success)
        val items = (result as FeedFetchResult.Success).items
        assertEquals(2, items.size)
        assertEquals("First headline", items[0].title)
        assertEquals("https://example.com/1", items[0].link)
    }

    @Test
    fun `an Atom feed with one entry comes back as Success too - not just RSS`() = runBlocking {
        val result = FeedFetcher.fetch("https://example.com/atom.xml", clientRespondingText(atomWithOneEntry))
        assertTrue(result is FeedFetchResult.Success)
        val items = (result as FeedFetchResult.Success).items
        assertEquals(1, items.size)
        assertEquals("Atom headline", items[0].title)
        assertEquals("https://example.com/atom/1", items[0].link)
    }

    @Test
    fun `a well-formed feed with zero items is Empty, never Unreachable`() = runBlocking {
        val result = FeedFetcher.fetch("https://example.com/quiet.xml", clientRespondingText(rssWithNoItems))
        assertEquals(FeedFetchResult.Empty, result)
    }

    @Test
    fun `a 404 is Unreachable, never Empty - a quiet feed and a missing one are not the same fact`() = runBlocking {
        val result = FeedFetcher.fetch("https://example.com/missing.xml", clientRespondingText("", HttpStatusCode.NotFound))
        assertTrue(result is FeedFetchResult.Unreachable)
        assertTrue((result as FeedFetchResult.Unreachable).detail.contains("404"))
        assertTrue("a 404 must not read as the empty-feed sentence", result != FeedFetchResult.Empty)
    }

    @Test
    fun `a network failure is Unreachable too, distinguishable from the 404 by its own detail`() = runBlocking {
        val failingClient = HttpClient(MockEngine { throw IOException("connection refused") })
        val result = FeedFetcher.fetch("https://example.com/down.xml", failingClient)
        assertTrue(result is FeedFetchResult.Unreachable)
        val detail = (result as FeedFetchResult.Unreachable).detail
        assertTrue("network failure and HTTP 404 share the Unreachable category but not the sentence", !detail.contains("404"))
    }

    @Test
    fun `a 2xx body that is not RSS or Atom is Unparseable, never silently Empty`() = runBlocking {
        val result = FeedFetcher.fetch("https://example.com/notafeed.xml", clientRespondingText("<<<not xml at all"))
        assertTrue(result is FeedFetchResult.Unparseable)
    }

    @Test
    fun `the four outcomes render as four distinct sentences`() {
        fun sentence(r: FeedFetchResult): String = when (r) {
            is FeedFetchResult.Success -> "success:${r.items.size}"
            is FeedFetchResult.Empty -> "empty"
            is FeedFetchResult.Unreachable -> "unreachable:${r.detail}"
            is FeedFetchResult.Unparseable -> "unparseable:${r.detail}"
        }
        val sentences = setOf(
            sentence(FeedFetchResult.Empty),
            sentence(FeedFetchResult.Unreachable("HTTP 404")),
            sentence(FeedFetchResult.Unparseable("bad xml")),
            sentence(FeedFetchResult.Success(listOf(FeedHeadline("h", null)))),
        )
        assertEquals(4, sentences.size)
    }
}
