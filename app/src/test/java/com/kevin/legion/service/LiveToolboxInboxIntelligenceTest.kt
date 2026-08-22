package com.kevin.legion.service

import com.kevin.legion.gmail.GmailToolLogic
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ticket 25 (hands-and-senses map): "where's my package" / "when's my flight", read from mail
 * LEGION can already read. Ticket 18's resolution table settled four rules and this file pins
 * each one at the level that can actually be tested without a live Gmail account or a real
 * Gemini call - see `mailExtraction`'s own doc comment in [LiveToolbox] for why the message and
 * the estimate label are composed in plain Kotlin rather than trusted to the model:
 *
 * 1. Mail only - [SubAgent] is instantiated with `useSearch = false` inside `mailExtraction`,
 *    which nothing here can see directly (it's a local call, not a field), so this file instead
 *    pins the one thing that would leak the web back in if that ever regressed: the tool
 *    descriptions never invite a live lookup, and the calendar-boundary test below proves
 *    `flight_status` defers to the ONE deterministic source this app has (`read_calendar`)
 *    rather than to a web search.
 * 2. Every answer names its source - [LiveToolbox.buildMailSourceLine]'s exact wording.
 * 3. Estimate, never fact - [LiveToolbox.ESTIMATE_SUFFIX]'s exact wording, plus the tool
 *    descriptions themselves say "ESTIMATE" so the live model reads the rule before ever calling
 *    the tool.
 * 4. Read-through - both tool names are in [LiveToolbox.EPISODIC_EXCLUDED_TOOLS], asserted here
 *    and exercised end-to-end by `ConversationAuditRedactionTest`, which already iterates that
 *    whole set generically.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxInboxIntelligenceTest {

    private fun declarationFor(name: String): org.json.JSONObject {
        val all: JSONArray = LiveToolbox.declarations()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.getString("name") == name) return decl
        }
        throw AssertionError("$name is not declared to the live session at all")
    }

    private fun descriptionOf(name: String) = declarationFor(name).getString("description")

    // ------------------------------------------------------------------ declared, not hidden

    @Test
    fun `both tools are declared directly to the live session, not behind ask_mail`() {
        // Unlike search_mail/read_mail, these must never disappear from declarations() the way
        // LiveToolbox.DISPATCHED's members do - a boundary instruction the model never reads
        // cannot steer it, and rule 4's read-through exclusion below only fires for a tool the
        // GeminiLiveSession functionCall handler actually sees by name off the socket.
        assertTrue("track_package" in namesOf(LiveToolbox.declarations()))
        assertTrue("flight_status" in namesOf(LiveToolbox.declarations()))
    }

    private fun namesOf(arr: JSONArray): Set<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()

    @Test
    fun `neither tool takes required parameters`() {
        for (name in listOf("track_package", "flight_status")) {
            val required = declarationFor(name).getJSONObject("parameters").optJSONArray("required")
            assertTrue(
                "$name should need nothing from the model beyond the call itself",
                required == null || required.length() == 0,
            )
        }
    }

    // ------------------------------------------------------------------ rule 3: estimate, never fact

    @Test
    fun `both descriptions say ESTIMATE in words, not just imply it`() {
        assertTrue(descriptionOf("track_package").contains("ESTIMATE"))
        assertTrue(descriptionOf("flight_status").contains("ESTIMATE"))
    }

    @Test
    fun `both descriptions say the answer is never a confirmed fact`() {
        val pkg = descriptionOf("track_package").lowercase()
        val flight = descriptionOf("flight_status").lowercase()
        assertTrue("never a confirmed live status" in pkg)
        assertTrue("never a confirmed schedule" in flight)
    }

    @Test
    fun `ESTIMATE_SUFFIX is a fixed literal that labels the answer, not a fact`() {
        assertTrue(LiveToolbox.ESTIMATE_SUFFIX.contains("estimate", ignoreCase = true))
        assertFalse(
            "the suffix must never claim to be a live/current status itself",
            LiveToolbox.ESTIMATE_SUFFIX.contains("confirmed", ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------ rule 2: name the source

    @Test
    fun `buildMailSourceLine names the subject, sender and relative date together`() {
        val line = LiveToolbox.buildMailSourceLine(
            subject = "Your order has shipped",
            from = "UPS <noreply@ups.com>",
            relativeDate = "yesterday",
        )
        assertEquals(
            "Your \"Your order has shipped\" email from UPS <noreply@ups.com> (yesterday) says:",
            line,
        )
    }

    @Test
    fun `buildMailSourceLine never goes blank on a blank subject or sender`() {
        val line = LiveToolbox.buildMailSourceLine(subject = "  ", from = "  ", relativeDate = "today")
        assertFalse("a blank subject must not produce an empty quoted title", line.contains("\"\""))
        assertTrue(line.contains("an unknown sender"))
    }

    @Test
    fun `both tool descriptions instruct the model to say which email it read`() {
        assertTrue(descriptionOf("track_package").contains("names which email it read"))
        assertTrue(descriptionOf("flight_status").contains("names which email it read"))
    }

    // ------------------------------------------------------------------ the calendar boundary

    @Test
    fun `flight_status defers to read_calendar first, in words the live model reads`() {
        val flight = descriptionOf("flight_status")
        assertTrue(
            "flight_status must send the model to read_calendar BEFORE itself, or two paths " +
                "answer the same question at different confidence - CLAUDE.md's inbox-intelligence rule",
            flight.contains("CHECK `read_calendar` FIRST"),
        )
        assertTrue(
            "the description must say WHY read_calendar wins - it is exact, this tool is a guess",
            flight.contains("no guessing"),
        )
    }

    @Test
    fun `track_package carries no calendar-deferral language - packages never reach a calendar`() {
        // Ticket 18 resolution point 5: mail fills the gaps, packages being one of them. A
        // package tool that deferred to read_calendar would be deferring to a source that never
        // has the answer.
        assertFalse("track_package" == "flight_status")
        assertFalse(descriptionOf("track_package").contains("read_calendar"))
    }

    // ------------------------------------------------------------------ rule 4: read-through

    @Test
    fun `both tools are in EPISODIC_EXCLUDED_TOOLS`() {
        assertTrue("track_package" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
        assertTrue("flight_status" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }

    @Test
    fun `isEpisodicExcludedTool agrees for both, using the same production membership test`() {
        // Same function GeminiLiveSession itself calls off the socket - see
        // ConversationAuditRedactionTest's own doc comment for why composing that function here
        // (rather than re-testing set membership a second, parallel way) is the decision
        // production actually makes.
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("track_package"))
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("flight_status"))
    }

    // ------------------------------------------------------------------ rule 1: mail only, distinct failures

    /**
     * CLAUDE.md's "a failure result must say in words what did NOT happen" (§7's outcome-verb
     * rule, applied here to a tool with no outcome verb at all - only a read). Three different
     * causes must produce three different sentences: no permission/network to Gmail at all (one
     * of [GmailToolLogic]'s own four causes, already pinned by `GmailToolLogicTest`), Gmail
     * reachable but nothing matched, and Gmail reachable and a message found but Gemini could not
     * read it through. This test proves the three constants LiveToolbox actually returns for
     * those three cases are textually distinct, not a collapsed generic string.
     */
    @Test
    fun `no-match and extraction-failure messages are distinct from each other and from every Gmail cause`() {
        val gmailCauseMessages = GmailToolLogic.Cause.entries.map { GmailToolLogic.message(it) }
        val domainMessages = listOf(
            LiveToolbox.PACKAGE_NO_MATCH_MESSAGE,
            LiveToolbox.FLIGHT_NO_MATCH_MESSAGE,
            LiveToolbox.MAIL_EXTRACTION_FAILED_MESSAGE,
        )
        assertEquals(
            "all three domain-level failure sentences must be textually distinct from one another",
            domainMessages.size,
            domainMessages.toSet().size,
        )
        for (message in domainMessages) {
            assertFalse(
                "\"$message\" must not collapse into one of GmailToolLogic's own four cause " +
                    "messages - a lack of MATCH and a lack of PERMISSION are different sentences",
                message in gmailCauseMessages,
            )
        }
    }

    @Test
    fun `the no-match messages say nothing was found, not that something went wrong`() {
        // "found" language distinguishes a successfully-read, simply-empty inbox from an error -
        // GmailToolLogic's four messages all describe something failing to work; these must not.
        assertTrue("find" in LiveToolbox.PACKAGE_NO_MATCH_MESSAGE)
        assertTrue("find" in LiveToolbox.FLIGHT_NO_MATCH_MESSAGE)
    }

    @Test
    fun `the extraction-failure message says the mail WAS found, unlike the no-match messages`() {
        assertTrue(
            "the extraction failure happens strictly AFTER a match was found and read - the " +
                "wording must say so, or a driver can't tell which stage failed",
            LiveToolbox.MAIL_EXTRACTION_FAILED_MESSAGE.contains("found the mail"),
        )
    }
}
