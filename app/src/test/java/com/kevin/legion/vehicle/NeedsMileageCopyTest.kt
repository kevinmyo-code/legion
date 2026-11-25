package com.kevin.legion.vehicle

import com.kevin.legion.advisor.digest.FleetDigestBuilder
import com.kevin.legion.assistant.cards.CarCards
import com.kevin.legion.assistant.cards.CarPart
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.ui.fleet.MileageEntry
import com.kevin.legion.ui.fleet.mileageFieldText
import com.kevin.legion.ui.fleet.parseMileageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The surfaces round 2 of the RED review on PR #5 found UNCOVERED: the spoken ask (the main voice
 * surface, and the one whose question had nowhere to land), the `CarCards` line, the digest's
 * NEXT-line suffix, the `mileage` paramNote on the folded `car` tool, and the edit dialog's blank
 * prefill (a Compose `remember`, which reverted green).
 *
 * Each asserts what a USER or a model can observe - the sentence, the note, the field's starting
 * text - rather than how it is assembled. The ask and the write that answers it are deliberately
 * one route ([needsMileageAskNote] and [pinStatedReadingToWaitingItems]); the write half is pinned
 * by [OdometerAnswersTheMileageAskTest].
 */
class NeedsMileageCopyTest {

    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L

    private fun vehicle() = Vehicle(
        obdMac = vehicleId, name = "", make = "Honda", model = "Civic", year = 2018,
        personaPrompt = "", odometerBaseline = 100_000,
    )

    private fun milesOnlyWaiting() = MaintenanceItem(
        vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5_000,
        intervalMonths = null, lastDoneMileage = null, lastDoneDate = now,
    )

    private fun next() = VehicleController.computeNextService(
        items = listOf(milesOnlyWaiting()),
        currentMileage = 100_500,
        now = now,
        odometerBaseline = 100_000,
    )!!

    // --- the spoken ask ------------------------------------------------------------------------

    @Test
    fun `the spoken answer names the item and asks for the reading`() {
        val note = needsMileageAskNote(listOf("Oil Change"))

        assertTrue(note, note.contains("Oil Change"))
        assertTrue("it asks for a reading, which is what set_odometer takes: $note", note.contains("odometer"))
        assertTrue("and says why it matters: $note", note.contains("by miles"))
    }

    @Test
    fun `the spoken answer pluralises rather than naming one item twice`() {
        val note = needsMileageAskNote(listOf("Oil Change", "Spark Plugs"))

        assertTrue(note, note.contains("Oil Change, Spark Plugs"))
        assertTrue("those, not that one: $note", note.contains("those"))
    }

    @Test
    fun `nothing waiting says nothing at all`() {
        assertEquals("", needsMileageAskNote(emptyList()))
    }

    // --- the cards and the digests -------------------------------------------------------------

    @Test
    fun `the car card names the item and the write that anchors it`() {
        val card = CarCards.service(
            label = "the Civic",
            vehicle = vehicle(),
            next = next(),
            zone = ZoneId.of("UTC"),
            nowMs = now,
        )

        val note = card.parts.getValue(CarPart.SERVICE)
        assertTrue(note, note.contains("No mileage reading against Oil Change"))
        assertTrue("a model reading this card must be able to tell him what fixes it: $note", note.contains("anchor"))
    }

    @Test
    fun `the digest's NEXT line carries the item and what is owed`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(),
            currentMileage = 100_500,
            items = listOf(milesOnlyWaiting()),
            unknownNames = emptyList(),
            nextService = next(),
            codeEvents = emptyList(),
            recentServices = emptyList<ServiceRecord>(),
            now = now,
        )

        assertTrue("the NEXT line may not read as 'fine': $text", text.contains("no mileage reading against Oil Change"))
        assertTrue("and says a reading clears it: $text", text.contains("until a reading is stated"))
    }

    // --- the folded voice surface --------------------------------------------------------------

    @Test
    fun `the car tool's mileage param keeps its wording on the surface that ships`() {
        val car = LiveSurface.ACTION_TOOLS.single { it.name == "car" }

        val note = car.paramNotes["mileage"]
        assertTrue("the folded car tool drops every description but a paramNote, so this is the only copy", note != null)
        assertTrue("it must still say to omit an unstated reading: $note", note!!.lowercase().contains("omit"))
    }

    // --- the edit dialog's own field -----------------------------------------------------------

    @Test
    fun `a record with no reading opens the mileage box blank, and blank stays no reading`() {
        assertEquals("", mileageFieldText(null))
        assertEquals(MileageEntry.None, parseMileageEntry(mileageFieldText(null)))
    }

    @Test
    fun `a record with a reading opens on it, and round-trips back to the same figure`() {
        assertEquals("42300", mileageFieldText(42_300))
        assertEquals(MileageEntry.Reading(42_300), parseMileageEntry(mileageFieldText(42_300)))
    }
}
