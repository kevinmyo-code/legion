package com.kevin.legion.assistant.cards

import com.kevin.legion.ui.assistant.CardGeneralWords
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The general kinds (readings, status_list, trend) against `shared/cards/fixtures.json`, the file
 * the engine's `test_cards_general.py` and the web's `general-cards.test.tsx` read too: each card
 * parses, says exactly the fixture's words, and writes back the shape it came in.
 */
@RunWith(RobolectricTestRunner::class)
class GeneralCardsTest {

    private val fixtures get() = SharedCardFixtures.all

    private fun of(kind: String) = fixtures.filter { it.getJSONObject("card").getString("card") == kind }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test
    fun `every fixture parses, and every kind is there`() {
        val kinds = fixtures.map { f -> assertNotNull(ServerCards.parse(f.getJSONObject("card"))); f.getJSONObject("card").getString("card") }
        assertEquals(setOf("readings", "status_list", "trend"), kinds.toSet())
    }

    @Test
    fun `readings say the fixture words, unread as not read, money from whole cents`() {
        for (f in of("readings")) {
            val card = ServerCards.parse(f.getJSONObject("card")) as ConversationCard.Readings
            val words = f.getJSONObject("words")
            assertEquals(strings(words.getJSONArray("values")), card.items.map(CardGeneralWords::reading))
            assertTrue(CardGeneralWords.asOf(card.at)!!.startsWith(words.getString("asOfStartsWith")))
            assertEquals(2, card.more)
        }
    }

    @Test
    fun `status rows say the status in words`() {
        for (f in of("status_list")) {
            val card = ServerCards.parse(f.getJSONObject("card")) as ConversationCard.StatusList
            assertEquals(strings(f.getJSONObject("words").getJSONArray("statuses")), card.items.map { it.status.words })
        }
    }

    @Test
    fun `a trend says each series latest, low and high in words`() {
        for (f in of("trend")) {
            val card = ServerCards.parse(f.getJSONObject("card")) as ConversationCard.Trend
            assertEquals(
                strings(f.getJSONObject("words").getJSONArray("series")),
                card.series.map { CardGeneralWords.series(it, card.unit) },
            )
        }
    }

    @Test
    fun `the phone writes back the engine's shape, field for field`() {
        for (f in fixtures) {
            val wire = f.getJSONObject("card")
            val written = CardJson.write(ServerCards.parse(wire)!!)
            assertEquals(wire.getString("card"), written.getString("kind"))
            written.remove("kind")
            wire.remove("card")
            assertTrue("${wire.getString("title")}: $written", same(wire, written))
        }
    }

    @Test
    fun `the phone's clock is 24-hour`() {
        assertEquals("As of Sun Nov 15, 2026, 14:20", CardGeneralWords.asOf("2026-11-15T14:20"))
        assertNull(CardGeneralWords.asOf(null))
    }

    @Test
    fun `a shape that does not fit is no card`() {
        val fractionOfACent = """{"card":"readings","title":"x","items":[{"label":"a","value":1.5,"unit":"usd"}],"more":0}"""
        assertNull(ServerCards.parse(JSONObject(fractionOfACent)))
        val colour = """{"card":"status_list","title":"x","items":[{"name":"a","status":"green"}],"more":0}"""
        assertNull(ServerCards.parse(JSONObject(colour)))
        assertNull(ServerCards.parse(JSONObject("""{"card":"trend","title":"x","unit":"lb","series":[]}""")))
        val badDate = """{"card":"trend","title":"x","unit":"lb","series":[{"name":"a","points":[{"at":"Nov 4","value":1}]}]}"""
        assertNull(ServerCards.parse(JSONObject(badDate)))
    }

    @Test
    fun `numbers keep what the source sent, grouped, no trailing zeros`() {
        assertEquals("1,234.5", CardGeneralWords.number(BigDecimal("1234.50")))
        assertEquals("0.1", CardGeneralWords.number(BigDecimal("0.1")))
        assertEquals("-2.5 °C", CardGeneralWords.withUnit(BigDecimal("-2.5"), "°C"))
    }

    /** JSON equality with numbers compared by value (90 and 90.0 are one reading). */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        else -> a == b
    }
}
