package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-03, A25: "When's the car due for service?" with no dongle connected and nothing picked
 * answered about the "default" placeholder ("Nothing on the schedule yet - register the car") while
 * a real car was on file. [VehicleResolver.pickDefault], [VehicleResolver.matchSpoken] and
 * [VehicleResolver.mentionedIn] decide which car now. Pure, so no Robolectric.
 */
class VehicleDefaultPickTest {

    private fun car(id: String, name: String, year: Int, make: String, model: String) =
        Vehicle(obdMac = id, name = name, make = make, model = model, year = year, personaPrompt = "")

    private val jeep = car("car:jeep", "", 1998, "Jeep", "Cherokee")
    private val outlander = car("AA:11", "Mom's car", 2020, "Mitsubishi", "Outlander")

    private fun resolvedId(m: VehicleMatch?) = (m as VehicleMatch.Resolved).vehicle.obdMac

    @Test
    fun `the only car on file is the car, not a placeholder`() {
        val m = VehicleResolver.pickDefault(listOf(jeep), connectedId = null, selectedId = null, lastConnectedId = null)
        assertEquals("car:jeep", resolvedId(m))
    }

    @Test
    fun `two cars and nothing to choose by asks which`() {
        val m = VehicleResolver.pickDefault(listOf(jeep, outlander), null, null, null)
        assertTrue(m is VehicleMatch.NoneChosen)
        assertEquals(2, (m as VehicleMatch.NoneChosen).candidates.size)
        val msg = LiveToolbox.noneChosenResult(m).getString("message")
        assertTrue(msg, msg.contains("nothing was looked up") && msg.endsWith("Which one?"))
    }

    @Test
    fun `order is connected, picked, only, last connected`() {
        val both = listOf(jeep, outlander)
        assertEquals("AA:11", resolvedId(VehicleResolver.pickDefault(both, "AA:11", "car:jeep", null)))
        assertEquals("car:jeep", resolvedId(VehicleResolver.pickDefault(both, null, "car:jeep", "AA:11")))
        assertEquals("AA:11", resolvedId(VehicleResolver.pickDefault(both, null, null, "AA:11")))
        // A stale pick (car gone) falls through rather than answering about nothing.
        assertEquals("AA:11", resolvedId(VehicleResolver.pickDefault(both, null, "car:gone", "AA:11")))
    }

    @Test
    fun `empty roster or a dongle in an unknown car keeps the old behaviour`() {
        assertNull(VehicleResolver.pickDefault(emptyList(), null, null, null))
        assertNull(VehicleResolver.pickDefault(listOf(jeep), "ZZ:99", null, null))
    }

    @Test
    fun `named car matches by make, model, year and with an article`() {
        val both = listOf(jeep, outlander)
        assertEquals("car:jeep", resolvedId(VehicleResolver.matchSpoken(both, "the Jeep")))
        assertEquals("car:jeep", resolvedId(VehicleResolver.matchSpoken(both, "my 98 Cherokee")))
        assertEquals("car:jeep", resolvedId(VehicleResolver.matchSpoken(both, "Cherokee")))
        assertEquals("AA:11", resolvedId(VehicleResolver.matchSpoken(both, "Mom's car")))
        assertNull(VehicleResolver.matchSpoken(both, "the Tacoma"))
    }

    @Test
    fun `two cars fitting one name is ambiguous, never a guess`() {
        val jeep2 = car("car:wj", "", 2003, "Jeep", "Grand Cherokee")
        assertTrue(VehicleResolver.matchSpoken(listOf(jeep, jeep2), "the Jeep") is VehicleMatch.Ambiguous)
    }

    @Test
    fun `a question names the car it is about`() {
        val both = listOf(jeep, outlander)
        assertEquals("car:jeep", VehicleResolver.mentionedIn("When's the Jeep due for service?", both)?.obdMac)
        assertEquals("AA:11", VehicleResolver.mentionedIn("any recalls on the outlander", both)?.obdMac)
        assertNull(VehicleResolver.mentionedIn("When's the car due for service?", both))
    }

    @Test
    fun `no schedule is said by name, not register the car`() {
        val msg = LiveToolbox.noScheduleMessage("1998 Jeep Cherokee", scheduleEmpty = true)
        assertEquals(
            "The 1998 Jeep Cherokee has no maintenance schedule on file yet, so nothing can be timed. " +
                "Set one up on the Fleet tab, or log a service and it starts from there.",
            msg,
        )
        assertTrue(!msg.contains("register"))
    }

    @Test
    fun `recalls use the stated identity only for missing fields`() {
        val blank = car("car:x", "XJ", 0, "", "")
        val s = LiveToolbox.statedIdentity(blank, 1998, "Jeep", "Cherokee")!!
        assertEquals(LiveToolbox.StatedIdentity(1998, "Jeep", "Cherokee", listOf("year", "make", "model")), s)
        // Complete record: the record wins, nothing stated is used.
        assertNull(LiveToolbox.statedIdentity(jeep, 2001, "Jeep", "Cherokee"))
        // The question does not fill the gap: refuse as before.
        assertNull(LiveToolbox.statedIdentity(blank, 1998, "Jeep", ""))
    }

    // Phone audit 2026-11-10: asked about a car the household does not own, the fleet lookup read
    // another car's codes and named it. A question that names only a make no car on file has
    // answers nowhere in the records.
    @Test
    fun `a question about a make no car on file has is not about any car on file`() {
        val both = listOf(jeep, outlander)
        assertEquals("subaru", VehicleResolver.makeNotOnFile("why does my Subaru drop its radio presets?", both))
        assertEquals("volkswagen", VehicleResolver.makeNotOnFile("Is a 2015 VW Jetta reliable?", both))
        assertNull(VehicleResolver.makeNotOnFile("When's the Jeep due for service?", both))
        assertNull(VehicleResolver.makeNotOnFile("what does P0420 mean", both))
        assertNull(VehicleResolver.makeNotOnFile("compare my outlander with a subaru forester", both))
    }

    @Test
    fun `a make on file is never refused, and an empty roster refuses nothing`() {
        assertNull(VehicleResolver.makeNotOnFile("recalls on the jeep?", listOf(jeep)))
        assertNull(VehicleResolver.makeNotOnFile("recalls on my Subaru?", emptyList()))
    }
}
