package com.kevin.legion.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** memory-and-vault ticket 13. Plates here are made up; none is a real one. */
class VehiclePlateControllerTest {

    private class FakeBackend(private val answer: (String?) -> PlateOutcome) : VehiclePlateBackend {
        val sent = mutableListOf<Pair<String, String?>>()
        override suspend fun setPlate(serverId: String, plate: String?): PlateOutcome {
            sent += serverId to plate
            return answer(plate)
        }
    }

    private class FakeStore(var serverId: String? = "v1", var plate: String? = null) : VehiclePlateStore {
        override suspend fun serverIdFor(vehicleId: String) = serverId
        override suspend fun plateFor(serverId: String) = plate
        override suspend fun savePlate(serverId: String, plate: String?) {
            this.plate = plate
        }
    }

    @Test
    fun `a plate longer than the engine allows is refused before anything is sent`() = runBlocking {
        val backend = FakeBackend { PlateOutcome.Ok(it) }
        val outcome = VehiclePlateController(backend, FakeStore()).setPlate("v1", "X".repeat(17))

        val sentence = (outcome as PlateOutcome.Refused).sentence
        assertTrue(sentence, sentence.contains("at most 16 characters"))
        assertTrue(sentence, sentence.contains("Nothing was changed"))
        assertTrue(backend.sent.isEmpty())
    }

    @Test
    fun `the typed plate is tidied, sent, and the engine's answer is kept locally`() = runBlocking {
        val backend = FakeBackend { PlateOutcome.Ok(it) }
        val store = FakeStore()
        val outcome = VehiclePlateController(backend, store).setPlate("v1", "  abc   123 ")

        assertEquals(PlateOutcome.Ok("ABC 123"), outcome)
        assertEquals(listOf("v1" to "ABC 123"), backend.sent)
        assertEquals("ABC 123", store.plate)
    }

    @Test
    fun `blank clears the plate`() = runBlocking {
        val backend = FakeBackend { PlateOutcome.Ok(it) }
        val store = FakeStore(plate = "ABC 123")
        VehiclePlateController(backend, store).setPlate("v1", "   ")

        assertEquals(listOf("v1" to null), backend.sent)
        assertNull(store.plate)
    }

    @Test
    fun `a refusal or an unconfirmed write leaves the local plate alone`() = runBlocking {
        for (answer in listOf(PlateOutcome.Refused("no. Nothing was changed."), PlateOutcome.Unconfirmed("?"))) {
            val store = FakeStore(plate = "ABC 123")
            VehiclePlateController(FakeBackend { answer }, store).setPlate("v1", "XYZ 9")
            assertEquals("ABC 123", store.plate)
        }
    }

    @Test
    fun `a car not on the engine has no target id`() = runBlocking {
        val controller = VehiclePlateController(FakeBackend { PlateOutcome.Ok(it) }, FakeStore(serverId = null))
        val target = controller.target("mac")
        assertNull(target.serverId)
    }

    @Test
    fun `every outcome is said in words`() {
        assertTrue(VehiclePlateController.sentence(PlateOutcome.Ok("ABC 123")).contains("ABC 123"))
        assertTrue(VehiclePlateController.sentence(PlateOutcome.Ok(null)).contains("no plate"))
        assertTrue(VehiclePlateController.sentence(PlateOutcome.Unreachable("offline")).contains("nothing was changed"))
        assertTrue(VehiclePlateController.sentence(PlateOutcome.Unconfirmed("x")).contains("may or may not"))
    }
}
