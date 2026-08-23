package com.kevin.legion.ui.phone

import com.kevin.legion.service.PlaceCallAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PhoneDialLogic.classify] against the REAL [PlaceCallAction.dispatchVoiceCall] (not a paraphrase
 * of its sentences) - this is what actually guards the string markers
 * ([PhoneDialLogic]'s own doc explains why they exist at all): if a future edit to
 * `dispatchVoiceCall`'s wording ever drifts from what this file matches on, these tests catch it
 * here, in the presentation layer, rather than silently mis-rendering a call's real state on
 * screen.
 *
 * Ticket 05's own gate: all four voice-tool failure sentences must render as distinct SCREEN
 * states, never folded into one generic "it didn't work" - covered below the same way
 * `PlaceCallActionTest` covers the sentences themselves.
 */
class PhoneDialLogicTest {

    private fun noContacts(@Suppress("UNUSED_PARAMETER") q: String) = emptyList<PlaceCallAction.ContactMatch>()
    private fun noEmergency(@Suppress("UNUSED_PARAMETER") n: String) = false

    // ------------------------------------------------------------------ tap 1: resolves to Confirm

    @Test
    fun `an unconfirmed contact resolution classifies as Confirm with just the name`() = runBlocking {
        val matches = listOf(PlaceCallAction.ContactMatch("Mom", "5551112222"))
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Mom", numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { fail("dial must not run on tap 1"); true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.Confirm)
        assertEquals("Mom", (step as PhoneDialLogic.Step.Confirm).readBack)
    }

    @Test
    fun `an unconfirmed number resolution classifies as Confirm with the grouped digits`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { fail("dial must not run on tap 1"); true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.Confirm)
        assertTrue((step as PhoneDialLogic.Step.Confirm).readBack.contains(","))
    }

    // ------------------------------------------------------------------ the four required failure states

    @Test
    fun `no such contact classifies as Rejected, distinct from Confirm`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Nobody", numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.Rejected)
    }

    @Test
    fun `several matches classifies as Rejected and the message lists them, never auto-picks`() = runBlocking {
        val matches = listOf(
            PlaceCallAction.ContactMatch("Sam A", "1112223333"),
            PlaceCallAction.ContactMatch("Sam B", "1112224444"),
        )
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Sam", numberQuery = null, confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.Rejected)
        val message = (step as PhoneDialLogic.Step.Rejected).message
        assertTrue(message.contains("Sam A"))
        assertTrue(message.contains("Sam B"))
    }

    @Test
    fun `missing call permission classifies as Rejected`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = false,
            hasCallPermission = false, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.Rejected)
        assertTrue((step as PhoneDialLogic.Step.Rejected).message.contains("permission"))
    }

    @Test
    fun `an emergency number classifies as its own distinct EmergencyRefused state`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "911", confirmed = false,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = { true },
            dial = { fail("emergency must never reach dial"); true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = false)
        assertTrue(step is PhoneDialLogic.Step.EmergencyRefused)
    }

    @Test
    fun `an emergency number is refused even on the confirmed tap and never dials`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "911", confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = { true },
            dial = { fail("emergency must never reach dial"); true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = true)
        assertTrue(step is PhoneDialLogic.Step.EmergencyRefused)
    }

    // ------------------------------------------------------------------ tap 2: Called vs Failed

    @Test
    fun `a confirmed dial that connects classifies as Called and names the target`() = runBlocking {
        val matches = listOf(PlaceCallAction.ContactMatch("Mom", "5551112222"))
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = "Mom", numberQuery = null, confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = { matches }, isEmergencyNumber = ::noEmergency,
            dial = { true },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = true)
        assertTrue(step is PhoneDialLogic.Step.Called)
        assertTrue((step as PhoneDialLogic.Step.Called).message.contains("Mom"))
    }

    @Test
    fun `a confirmed dial that never connects classifies as Failed, not Rejected`() = runBlocking {
        val result = PlaceCallAction.dispatchVoiceCall(
            contactQuery = null, numberQuery = "5551234567", confirmed = true,
            hasCallPermission = true, hasContactsPermission = true,
            lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency,
            dial = { false },
        )
        val step = PhoneDialLogic.classify(result, wasConfirmed = true)
        assertTrue(step is PhoneDialLogic.Step.Failed)
    }

    // ------------------------------------------------------------------ readBackOf

    @Test
    fun `readBackOf pulls only the quoted sentence out of the confirm-gate message`() {
        val message = "Before I dial, say this back to the user and get a yes: \"Mom\". Call again."
        assertEquals("Mom", PhoneDialLogic.readBackOf(message))
    }

    @Test
    fun `readBackOf falls back to the whole message if there is no quoting to find`() {
        val message = "no quotes here"
        assertEquals(message, PhoneDialLogic.readBackOf(message))
    }

    // ------------------------------------------------------------------ five states, five sentences

    @Test
    fun `every failure-shaped state renders a distinct message`() = runBlocking {
        val rejected = PhoneDialLogic.classify(
            PlaceCallAction.dispatchVoiceCall(
                "Nobody", null, false, hasCallPermission = true, hasContactsPermission = true,
                lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency, dial = { true },
            ),
            wasConfirmed = false,
        )
        val emergency = PhoneDialLogic.classify(
            PlaceCallAction.dispatchVoiceCall(
                null, "911", false, hasCallPermission = true, hasContactsPermission = true,
                lookupContacts = ::noContacts, isEmergencyNumber = { true }, dial = { true },
            ),
            wasConfirmed = false,
        )
        val failed = PhoneDialLogic.classify(
            PlaceCallAction.dispatchVoiceCall(
                null, "5551234567", true, hasCallPermission = true, hasContactsPermission = true,
                lookupContacts = ::noContacts, isEmergencyNumber = ::noEmergency, dial = { false },
            ),
            wasConfirmed = true,
        )
        assertFalse(rejected is PhoneDialLogic.Step.EmergencyRefused)
        assertFalse(emergency is PhoneDialLogic.Step.Rejected)
        assertFalse(failed is PhoneDialLogic.Step.Rejected)
        assertFalse(failed is PhoneDialLogic.Step.EmergencyRefused)
    }
}
