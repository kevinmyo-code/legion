package com.kevin.legion.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain-JVM half of `ui/help/VoiceGuideScreen.kt` (command-center ticket 09) - the
 * contains-filter that backs the search field, tested without Compose or Robolectric since
 * [filterGroups]/[entryMatches] are pure functions over [VoiceGuideData].
 */
class VoiceGuideFilterTest {

    private fun group(title: String, vararg entries: VoiceGuideData.Entry) =
        VoiceGuideData.Group(title = title, blurb = null, entries = entries.toList())

    private fun entry(name: String, say: String = "say", does: String = "does", hands: String = "hands") =
        VoiceGuideData.Entry(name = name, say = say, does = does, hands = hands)

    @Test
    fun `blank query returns every group unchanged`() {
        val groups = listOf(group("A", entry("a")), group("B", entry("b")))
        assertEquals(groups, filterGroups(groups, ""))
        assertEquals(groups, filterGroups(groups, "   "))
    }

    @Test
    fun `matches on the does text, not just the name`() {
        val groups = listOf(
            group(
                "Money",
                entry(
                    name = "log_pending_transaction",
                    say = "I just spent thirty on petrol",
                    does = "Records a spend by voice before it hits the bank.",
                ),
            ),
        )
        val result = filterGroups(groups, "fuel")
        assertTrue("neither the name nor the copy contains \"fuel\", so no match is expected here", result.isEmpty())

        val petrolMatch = filterGroups(groups, "petrol")
        assertEquals(1, petrolMatch.size)
        assertEquals(1, petrolMatch[0].entries.size)
    }

    @Test
    fun `matches on the hands field`() {
        val groups = listOf(group("The cars", entry("get_specs", hands = "Fleet > Vehicle specs screen.")))
        assertEquals(1, filterGroups(groups, "vehicle specs").size)
        assertTrue(filterGroups(groups, "no such thing anywhere").isEmpty())
    }

    @Test
    fun `is case-insensitive`() {
        val groups = listOf(group("Money", entry("get_balance", say = "What's my balance?")))
        assertEquals(1, filterGroups(groups, "BALANCE").size)
    }

    @Test
    fun `a group with no surviving entries is dropped entirely, not shown empty`() {
        val groups = listOf(
            group("A", entry("a", say = "alpha")),
            group("B", entry("b", say = "bravo")),
        )
        val result = filterGroups(groups, "alpha")
        assertEquals(listOf("A"), result.map { it.title })
    }

    @Test
    fun `matching the group title alone surfaces every entry in it`() {
        val groups = listOf(group("Phone calls", entry("answer_call"), entry("place_call")))
        val result = filterGroups(groups, "phone calls")
        assertEquals(1, result.size)
        assertEquals(2, result[0].entries.size)
    }
}
