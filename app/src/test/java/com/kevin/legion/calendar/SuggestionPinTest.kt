package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pure half of suggestion pins (Kevin, 2026-10-09): the sentence the row shows, the parser for
 * the stored `pinned_by` JSON, the "Pinned only" filter, and what the model hears. The wording
 * mirrors the web exactly, so these strings are the contract.
 */
@RunWith(RobolectricTestRunner::class)
class SuggestionPinTest {
    private val me = "u-kevin"
    private val kevin = SuggestionPin(me, "Kevin")
    private val mia = SuggestionPin("u-mia", "Mia")
    private val sam = SuggestionPin("u-sam", "Sam")

    private fun words(vararg pins: SuggestionPin, myId: String? = me) = SuggestionPin.words(pins.toList(), myId)

    @Test
    fun `nobody pinned is no line at all`() = assertNull(words())

    @Test
    fun `only me`() = assertEquals("You want to go", words(kevin))

    @Test
    fun `only another member`() = assertEquals("Mia wants to go", words(mia))

    @Test
    fun `me and Mia puts You first even when Mia pinned first`() =
        assertEquals("You and Mia want to go", words(mia, kevin))

    @Test
    fun `me and two others lists You first then the rest in pin order`() =
        assertEquals("You, Mia and Sam want to go", words(mia, sam, kevin))

    @Test
    fun `two others without me`() = assertEquals("Mia and Sam want to go", words(mia, sam))

    @Test
    fun `an unknown own id never says You`() {
        assertEquals("Kevin and Mia want to go", words(kevin, mia, myId = null))
        assertEquals("Kevin wants to go", words(kevin, myId = null))
    }

    @Test
    fun `parse reads a valid array in order`() {
        val pins = SuggestionPin.parse(
            """[{"user_id":"u-mia","display_name":"Mia"},{"user_id":"u-kevin","display_name":"Kevin"}]""",
        )
        assertEquals(listOf(mia, kevin), pins)
    }

    @Test
    fun `parse reads malformed, blank, null and empty as no pins`() {
        assertTrue(SuggestionPin.parse("{not json").isEmpty())
        assertTrue(SuggestionPin.parse("""{"user_id":"x"}""").isEmpty())
        assertTrue(SuggestionPin.parse("").isEmpty())
        assertTrue(SuggestionPin.parse(null).isEmpty())
        assertTrue(SuggestionPin.parse("[]").isEmpty())
    }

    @Test
    fun `parse skips an entry missing an id or a name instead of guessing`() {
        val pins = SuggestionPin.parse(
            """[{"user_id":"u-mia"},{"display_name":"Sam"},{"user_id":"","display_name":"x"},
                {"user_id":"u-kevin","display_name":"Kevin"}]""",
        )
        assertEquals(listOf(kevin), pins)
    }

    @Test
    fun `withMine adds my pin once and removes only mine`() {
        val start = SuggestionPin.toJson(listOf(mia))
        val pinned = SuggestionPin.withMine(start, me, pinned = true)
        assertEquals(listOf(mia, SuggestionPin(me, SuggestionPin.SELF)), SuggestionPin.parse(pinned))
        assertEquals(pinned, SuggestionPin.withMine(pinned, me, pinned = true))
        assertEquals(listOf(mia), SuggestionPin.parse(SuggestionPin.withMine(pinned, me, pinned = false)))
        assertEquals("[]", SuggestionPin.withMine(null, me, pinned = false))
    }

    private fun suggestion(title: String, pinnedByJson: String?) = Event(
        serverId = title,
        title = title,
        startsAt = 1_000L,
        source = "legion",
        updatedAtMs = 1L,
        kind = EventKind.SUGGESTION,
        pinnedByJson = pinnedByJson,
    )

    @Test
    fun `the pinned only filter hides rows nobody pinned and nothing else`() {
        val a = suggestion("a", null)
        val b = suggestion("b", "[]")
        val c = suggestion("c", SuggestionPin.toJson(listOf(mia)))
        assertEquals(listOf(a, b, c), SuggestionPinActions.visibleRows(listOf(a, b, c), pinnedOnly = false))
        assertEquals(listOf(c), SuggestionPinActions.visibleRows(listOf(a, b, c), pinnedOnly = true))
        assertTrue(SuggestionPinActions.visibleRows(listOf(a, b), pinnedOnly = true).isEmpty())
    }

    @Test
    fun `the model hears who wants to go, self as you, and nothing when nobody does`() {
        val pinned = suggestion("Jazz night", SuggestionPin.toJson(listOf(mia, kevin)))
        val modelRow = EventSuggestions.toModelJson(pinned, me)!!
        assertEquals("[\"you\",\"Mia\"]", modelRow.getJSONArray("wants_to_go").toString())

        assertTrue(!EventSuggestions.toModelJson(suggestion("Quiet", null), me)!!.has("wants_to_go"))
        assertTrue(!EventSuggestions.toModelJson(suggestion("Quiet", "[]"), me)!!.has("wants_to_go"))
    }
}
