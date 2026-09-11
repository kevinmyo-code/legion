package com.kevin.legion.news

import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * One headline read out of an RSS 2.0 `<item>` or an Atom `<entry>`. **Prose only - CLAUDE.md
 * section 4's "nothing numeric escapes" applies to this whole surface** (one-home ticket 07):
 * [title] is never parsed for a number, and nothing here becomes a ledger row, a macro or a
 * maintenance date. [link] is carried for a future "open in browser" affordance; nothing in this
 * ticket renders it as a tappable link yet.
 */
data class FeedHeadline(val title: String, val link: String?)

/**
 * Why a feed fetch did not come back with items - the RSS analogue of [NewsDigestOutcome]/the
 * Gmail sitrep's three sentences (`SitrepBuilder`'s own class doc), one-home ticket 07's own
 * requirement: *"RSS needs its own set: no items, feed unreachable, feed returned something
 * unparseable... A feed that 404s and a feed that is quiet are not the same fact."*
 *
 * - [Success] - parsed fine, at least one item. [Empty] - parsed fine, zero items: a feed that
 *   really has nothing new right now, never confused with a feed the app could not read.
 * - [Unreachable] - the request never got a 2xx body to parse: a network failure (timeout, DNS,
 *   connection refused) OR a non-2xx HTTP status (404, 500). **Deliberately merged**, per the
 *   ticket's own "may share a sentence only if you say so deliberately" - both mean "nothing this
 *   feed said could be read", and [detail] keeps the two distinguishable in the rendered sentence
 *   (`"HTTP 404"` vs. the exception's own message) without needing a fourth branch a caller has to
 *   handle differently. Neither is ever rendered as [Empty] - a 404 is not a quiet feed.
 * - [Unparseable] - a 2xx response whose body is not RSS or Atom [FeedFetcher] recognises.
 */
sealed interface FeedFetchResult {
    data class Success(val items: List<FeedHeadline>) : FeedFetchResult
    object Empty : FeedFetchResult
    data class Unreachable(val detail: String) : FeedFetchResult
    data class Unparseable(val detail: String) : FeedFetchResult
}

/**
 * Plain GETs against whatever URL Kevin subscribed to - **not [com.kevin.legion.backend.engine.EngineHttp]**,
 * which is bound to the engine's own base URL and auth token (this ticket's own brief, verbatim).
 * Ktor + OkHttp only - no new HTTP stack (one-home ticket 06's resolution point 3, CLAUDE.md
 * section 3: adding a dependency is a ruling, not a bump). `android.util.Xml`'s pull parser is the
 * platform XML answer, same reasoning.
 *
 * **Called only on a tap** (`ui/news/NewsScreen.kt`'s own row), never on a schedule - the same
 * "refresh is a tap, never a poll" posture [com.kevin.legion.ui.world.NewsDigestCard] already
 * holds for Gmail, extended here rather than relaxed for RSS.
 */
object FeedFetcher {

    /** Cap on items read out of one feed per tap - the RSS analogue of `SitrepBuilder`'s
     * `NEWS_MESSAGE_CAP`. A feed's own item count is unbounded; this keeps one tap's cost and
     * screen space bounded regardless. */
    private const val FEED_ITEM_CAP = 15

    private val defaultClient: HttpClient by lazy { HttpClient(OkHttp.create()) }

    /** [client] is the test seam, same shape [com.kevin.legion.backend.engine.EngineHttp]'s own
     * doc comment describes: production omits it and gets the lazily-built real client, a test
     * passes an [HttpClient] built on [io.ktor.client.engine.mock.MockEngine] so no socket ever
     * opens. */
    suspend fun fetch(url: String, client: HttpClient = defaultClient): FeedFetchResult {
        val response: HttpResponse = try {
            client.get(url)
        } catch (e: IOException) {
            return FeedFetchResult.Unreachable(e.message ?: "The feed could not be reached.")
        }
        val code = response.status.value
        if (code !in SUCCESS_RANGE) {
            return FeedFetchResult.Unreachable("HTTP $code")
        }
        val body = try {
            response.bodyAsText()
        } catch (e: IOException) {
            return FeedFetchResult.Unreachable(e.message ?: "The feed could not be reached.")
        }
        val items = try {
            parseFeed(body)
        } catch (e: Exception) {
            // XmlPullParser throws XmlPullParserException (checked, extends Exception, not
            // IOException) for malformed markup - caught broadly here on purpose, unlike
            // EngineHttp's IOException-only catch, because this path has no coroutine
            // cancellation risk to protect (no suspend call inside [parseFeed]) and a parser
            // exception is exactly what [Unparseable] exists to report.
            return FeedFetchResult.Unparseable(e.message ?: "The feed's content could not be read as RSS or Atom.")
        }
        return if (items.isEmpty()) FeedFetchResult.Empty else FeedFetchResult.Success(items.take(FEED_ITEM_CAP))
    }

    /** RSS 2.0 (`<rss><channel><item><title>`) and Atom (`<feed><entry><title>`) - the two formats
     * every mainstream publisher emits. A feed in neither shape parses to an empty list, which
     * [fetch] reports as [FeedFetchResult.Unparseable] only when the underlying parse itself
     * throws; a well-formed XML document with no `item`/`entry` elements at all is legitimately
     * [FeedFetchResult.Empty] rather than a parse failure. */
    private fun parseFeed(body: String): List<FeedHeadline> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(body))
        val items = mutableListOf<FeedHeadline>()
        var inEntry = false
        var currentTitle: String? = null
        var currentLink: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item", "entry" -> {
                        inEntry = true
                        currentTitle = null
                        currentLink = null
                    }
                    "title" -> if (inEntry) currentTitle = readText(parser)
                    "link" -> if (inEntry) {
                        // Atom's <link href="..."/> carries the URL as an attribute; RSS's
                        // <link>https://...</link> carries it as text content. Try the attribute
                        // first since reading it does not consume the tag's text content.
                        currentLink = parser.getAttributeValue(null, "href") ?: readText(parser)
                    }
                }
                XmlPullParser.END_TAG -> if ((parser.name == "item" || parser.name == "entry") && inEntry) {
                    val title = currentTitle
                    if (!title.isNullOrBlank()) items.add(FeedHeadline(title, currentLink))
                    inEntry = false
                }
            }
            eventType = parser.next()
        }
        return items
    }

    /** Reads the text immediately inside the current start tag, leaving the parser positioned on
     * that tag's END_TAG - the shape every caller above assumes. */
    private fun readText(parser: XmlPullParser): String? {
        if (parser.next() != XmlPullParser.TEXT) return null
        val text = parser.text
        return text
    }

    private val SUCCESS_RANGE = 200..299
}
