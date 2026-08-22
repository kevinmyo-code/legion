package com.kevin.legion.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PlaceCallAction.dispatchVoiceCall] with every side effect injected, so this reaches the whole
 * confirm-gate + resolution + emergency-refusal logic with no `Context`/Robolectric - same split
 * `GarageController.dispatchVoiceActivate`'s own test file uses.
 *
 * **Ticket 26's own gate**: four failure sentences must be distinct (no such contact, several
 * matches, no permission, emergency refusal), an ambiguous match must ASK rather than pick, and an
 * emergency number must be refused before [dial] is ever reached. Every one of those is asserted
 * here directly against the real dispatch function, not against a paraphrase of it.
 */
class PlaceCallActionTest {

    private fun noContacts(@Suppress("UNUSED_PARAMETER") q: String) = emptyList<PlaceCallAction.ContactMatch>()
    private fun noEmergency(@Suppress("UNUSED_PARAMETER") n: String) = false

    // ------------------------------------------------------------------ pure helpers

    @Test
    fun `normalizeDigits accepts a plausible number and strips punctuation`() {
        assertEquals("5551234567", PlaceCallAction.normalizeDigits("(555) 123-4567"))
        assertEquals("+15551234567", PlaceCallAction.normalizeDigits("+1 555 123 4567"))
    }

    @Test
    fun `normalizeDigits rejects too short or too long`() {
        assertEquals(null, PlaceCallAction.normalizeDigits("12"))
        assertEquals(null, PlaceCallAction.normalizeDigits("1234567890123456"))
    }

    @Test
    fun `normalizeDigits accepts a 3-digit short code like an emergency number`() {
        // Not an endorsement of dialling one - dispatchVoiceCall's emergency check is what refuses
        // it. This only asserts normalizeDigits does not misroute it into "invalid number" first.
        assertEquals("911", PlaceCallAction.normalizeDigits("911"))
    }

    @Test
    fun `groupForSpeech chunks digits rather than reading one unbroken string`() {
        val grouped = PlaceCallAction.groupForSpeech("5551234567")
        assertTrue(grouped.contains(","))
        assertFalse("must not be the raw unbroken string", grouped == "5551234567")
    }

    // ------------------------------------------------------------------ nothing to call

    @Test
    fun `neither a contact nor a number asks which`() = runBlocking {
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { fail("dial must not run"); true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("Who"))
    }

    // ------------------------------------------------------------------ no permission (distinct sentence 1)

    @Test
    fun `missing CALL_PHONE permission is its own sentence and never dials`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = true,
            hasCallPermission = false, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("permission"))
        assertFalse(dialed)
    }

    @Test
    fun `missing contacts permission for a contact query is its own sentence too`() = runBlocking {
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Sam", numberQuery = null, confirmed = true,
            hasCallPermission = true, hasContactsPermission = false,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { fail("dial must not run"); true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("contacts"))
    }

    // ------------------------------------------------------------------ no such contact (distinct sentence 2)

    @Test
    fun `a name matching nobody says so and never dials`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Nobody", numberQuery = null, confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("Nobody"))
        assertTrue(r.message.lowercase().contains("anyone") || r.message.lowercase().contains("nobody"))
        assertFalse(dialed)
    }

    // ------------------------------------------------------------------ several matches (distinct sentence 3), asks not picks

    @Test
    fun `a name matching several people asks which one and never dials`() = runBlocking {
        var dialed = false
        val matches = listOf(
            PlaceCallAction.ContactMatch("Sam Retriever", "5550001111"),
            PlaceCallAction.ContactMatch("Sam Okafor", "5550002222"),
        )
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Sam", numberQuery = null, confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("Sam Retriever"))
        assertTrue(r.message.contains("Sam Okafor"))
        assertTrue(
            "must ask which one, never pick the nearest",
            r.message.contains("Which"),
        )
        assertFalse("an ambiguous match must never dial", dialed)
    }

    @Test
    fun `several numbers under the SAME contact name are not treated as ambiguous`() = runBlocking {
        val matches = listOf(
            PlaceCallAction.ContactMatch("Sam Okafor", "5550002222"),
            PlaceCallAction.ContactMatch("Sam Okafor", "5550003333"),
        )
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Sam", numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { true },
        )
        // Not an ambiguous-match failure - it resolves to one contact and asks for the read-back
        // confirm instead.
        assertTrue(r.message.contains("Sam Okafor"))
        assertFalse(r.message.contains("Which"))
    }

    // ------------------------------------------------------------------ invalid number

    @Test
    fun `an unparsable number never dials`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "12", confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertFalse(dialed)
    }

    // ------------------------------------------------------------------ the read-back IS the confirm gate

    @Test
    fun `an unconfirmed contact call resolves and reads back the name, never dials`() = runBlocking {
        var dialed = false
        val matches = listOf(PlaceCallAction.ContactMatch("Mom", "5551112222"))
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Mom", numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("Mom"))
        assertFalse("the confirm turn must never dial", dialed)
    }

    @Test
    fun `an unconfirmed digit call reads back the GROUPED digits, never dials`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.contains(","))
        assertFalse(dialed)
    }

    @Test
    fun `confirmed=true after a resolved contact actually dials and reports success`() = runBlocking {
        var dialedNumber: String? = null
        val matches = listOf(PlaceCallAction.ContactMatch("Mom", "5551112222"))
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Mom", numberQuery = null, confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { n -> dialedNumber = n; true },
        )
        assertTrue(r.success)
        assertEquals("5551112222", dialedNumber)
    }

    @Test
    fun `confirmed=true that fails to actually connect is never reported as success`() = runBlocking {
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { false },
        )
        assertFalse(r.success)
        assertFalse(r.message.lowercase().contains("calling"))
    }

    // ------------------------------------------------------------------ emergency refusal (distinct sentence 4)

    @Test
    fun `an emergency number is refused before any dial is attempted, even when confirmed`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "911", confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = { true },
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.lowercase().contains("emergency"))
        assertFalse("an emergency refusal must never reach dial", dialed)
    }

    @Test
    fun `an emergency number is refused on the FIRST unconfirmed call too, not just the confirmed one`() = runBlocking {
        var dialed = false
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "911", confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = { true },
            dial = { dialed = true; true },
        )
        assertFalse(r.success)
        assertTrue(r.message.lowercase().contains("emergency"))
        assertFalse(dialed)
        // Must never be phrased as an ordinary "confirm this" read-back prompt.
        assertFalse(r.message.contains("Before I dial"))
    }

    // ------------------------------------------------------------------ the four sentences are actually distinct

    @Test
    fun `the four required failure sentences never collapse into each other`() = runBlocking {
        val noPermission = PlaceCallAction.dispatchVoiceCall(
            null, "5551234567", true, hasCallPermission = false, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency, dial = { true },
        ).message

        val noSuchContact = PlaceCallAction.dispatchVoiceCall(
            "Nobody", null, true, hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency, dial = { true },
        ).message

        val severalMatches = PlaceCallAction.dispatchVoiceCall(
            "Sam", null, true, hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = {
                listOf(
                    PlaceCallAction.ContactMatch("Sam A", "1112223333"),
                    PlaceCallAction.ContactMatch("Sam B", "1112224444"),
                )
            },
            isEmergencyNumber = ::noEmergency, dial = { true },
        ).message

        val emergencyRefused = PlaceCallAction.dispatchVoiceCall(
            null, "911", true, hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = { true }, dial = { true },
        ).message

        val all = listOf(noPermission, noSuchContact, severalMatches, emergencyRefused)
        assertEquals("all four must be distinct sentences", all.toSet().size, all.size)
    }
}
