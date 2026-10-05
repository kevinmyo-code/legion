package com.kevin.legion.meditations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search quality over the real text, plus the two properties the persona's honesty rule leans on:
 * every returned string is verbatim from the book, and a question the book does not touch returns
 * nothing rather than a weak guess.
 *
 * The expected citations were read, not assumed: each was opened in `meditations.txt` and checked
 * to be about the question before it was pinned. They are asserted as "in the top three" rather
 * than "first", because a ranking tweak that reorders two good passages is not a regression.
 */
class MeditationsSearchTest {

    private val search = MeditationsFixture.search

    /** Mirrors MeditationsSearch's cap: a section this short is returned whole, a longer one is excerpted. */
    private val wholeSectionCap = 1_100

    private fun topCites(query: String): List<String> = search.search(query).map { it.passage.cite }

    private fun assertTopThreeHas(query: String, cite: String) {
        val got = topCites(query)
        assertTrue("\"$query\" should surface $cite in its top three but got $got", cite in got)
    }

    @Test
    fun `death finds the passage that says do not despise it`() = assertTopThreeHas("death", "Book IX, 3")

    @Test
    fun `anger finds the man whose breath is foul`() = assertTopThreeHas("anger", "Book V, 28")

    @Test
    fun `the morning finds rising unwillingly`() = assertTopThreeHas("the morning", "Book V, 1")

    @Test
    fun `what others think finds the neighbour's opinion passage`() =
        assertTopThreeHas("what others think", "Book XII, 4")

    @Test
    fun `a modern phrasing reaches the same morning passage through the concept bridge`() =
        assertTopThreeHas("I can't get out of bed", "Book V, 1")

    @Test
    fun `a rude coworker reaches the begin-the-morning meeting with the busybody`() =
        assertTopThreeHas("my coworker is rude to me", "Book II, 1")

    @Test
    fun `being held up reaches the obstacle that becomes the road`() {
        assertTopThreeHas("traffic is making me late", "Book V, 20")
        assertTopThreeHas("obstacle", "Book V, 20")
    }

    @Test
    fun `loneliness reaches the retreat into oneself`() = assertTopThreeHas("I feel lonely", "Book IV, 3")

    @Test
    fun `a question the book does not touch returns nothing`() {
        assertEquals(emptyList<String>(), topCites("pizza recipe"))
        assertEquals(emptyList<String>(), topCites("xyzzy"))
    }

    @Test
    fun `a question made only of stop words returns nothing`() {
        assertEquals(emptyList<String>(), topCites("what is it"))
        assertEquals(emptyList<String>(), topCites("   "))
    }

    @Test
    fun `at most the limit comes back and never a padded list`() {
        assertEquals(3, search.search("death").size)
        assertEquals(1, search.search("death", limit = 1).size)
    }

    @Test
    fun `search is deterministic`() {
        assertEquals(topCites("what others think"), topCites("what others think"))
        assertEquals(
            search.search("anger").map { it.text },
            MeditationsSearch(MeditationsFixture.passages).search("anger").map { it.text },
        )
    }

    /** The honesty rule's foundation: whatever is returned, the persona may quote, so it must be in the book. */
    @Test
    fun `every returned text is a verbatim slice of its section`() {
        val queries = listOf(
            "death", "anger", "the morning", "what others think", "fear", "time", "nature", "duty", "fame",
            "friends", "pain", "justice", "the universe", "the body", "wrong", "reason", "gods", "soul",
        )
        var excerpts = 0
        for (q in queries) {
            for (hit in search.search(q, limit = 5)) {
                assertTrue("${hit.passage.cite} text is not a slice of the book", hit.passage.text.contains(hit.text))
                val shorter = hit.text.length < hit.passage.text.length
                assertTrue("an excerpt flag must match reality", hit.isExcerpt == shorter)
                val withinCap = hit.text.length <= wholeSectionCap || !hit.isExcerpt
                assertTrue("${hit.passage.cite} text is over the cap", withinCap)
                if (hit.isExcerpt) excerpts++
            }
        }
        assertTrue("the excerpt path was never exercised, so this test proved nothing about it", excerpts > 0)
    }

    @Test
    fun `lookup returns exactly the named section`() {
        val hit = search.lookup(4, 3)!!
        assertEquals("Book IV, 3", hit.passage.cite)
        assertTrue(hit.text.startsWith("Men seek retreats for themselves"))
        assertEquals(null, search.lookup(4, 99))
    }

    @Test
    fun `lookup by reference understands Roman, Arabic and spoken forms`() {
        for (q in listOf("Book IV, 3", "book 4 section 3", "Book iv.3", "what is book IV number 3")) {
            val outcome = MeditationsLookup.run(search, q)
            val cites = (outcome as MeditationsLookup.Outcome.Found).hits.map { it.passage.cite }
            assertEquals("for \"$q\"", listOf("Book IV, 3"), cites)
        }
    }
}
