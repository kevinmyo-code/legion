package com.kevin.legion.ui.dumb

import com.kevin.legion.vehicle.VehicleController.ScheduleUnit
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix fleet: loading, empty, unreadable apart from empty; guesses and unknowns in words. */
class MatrixFleetViewModelTest {
    private class Source(var cars: List<MatrixCar> = emptyList(), var throws: Boolean = false) : MatrixFleetSource {
        override suspend fun cars(): List<MatrixCar> {
            if (throws) error("Room is closed")
            return cars
        }
    }

    private fun model(source: Source) =
        MatrixFleetViewModel(source, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun next(
        miles: VehicleController.ServiceCandidate? = null,
        time: VehicleController.ServiceCandidate? = null,
        unknown: List<String> = emptyList(),
        odometerUnset: Boolean = false,
        allDue: Boolean = false,
    ) = VehicleController.NextService(miles, time, unknown.size, unknown, odometerUnset, allDue)

    @Test
    fun `loading, empty and unreadable are different sentences`() {
        val vm = model(Source())
        assertEquals(MatrixFleetText.LOADING, MatrixFleetText.listNote(vm.state.value)?.text)
        vm.refresh()
        assertEquals(MatrixFleetText.EMPTY, MatrixFleetText.listNote(vm.state.value)?.text)
        val broken = model(Source(throws = true))
        broken.refresh()
        assertEquals(MatrixFleetText.UNREADABLE, MatrixFleetText.listNote(broken.state.value)?.text)
    }

    @Test
    fun `a failed refresh keeps the cars and warns they may be out of date`() {
        val source = Source(listOf(MatrixFleetText.carOf("Civic", "84,000 mi", null)))
        val vm = model(source)
        vm.refresh()
        source.throws = true
        vm.refresh()
        assertEquals(MatrixFleetText.STALE, MatrixFleetText.listNote(vm.state.value)?.text)
        assertEquals(1, vm.state.value.cars.size)
    }

    @Test
    fun `the next service by miles is the Fleet row's own sentence and a guess says so`() {
        val sure = VehicleController.ServiceCandidate("Oil change", 1_200, ScheduleUnit.MILES)
        assertEquals(
            "84,000 mi · Oil change in 1200 mi",
            MatrixFleetText.carOf("Civic", "84,000 mi", next(miles = sure)).status,
        )
        val guess = sure.copy(isGuess = true)
        val guessed = MatrixFleetText.carOf("Civic", "84,000 mi", next(miles = guess))
        assertTrue(guessed.status.endsWith(" - guess, unconfirmed"))
    }

    @Test
    fun `no schedule, unset odometer and everything due are different sentences`() {
        assertTrue(MatrixFleetText.carOf("A", "m", null).status.endsWith("no maintenance schedule yet"))
        assertTrue(MatrixFleetText.carOf("A", "m", next(odometerUnset = true)).status.startsWith("odometer not set"))
        val allDue = MatrixFleetText.carOf("A", "m", next(allDue = true))
        assertTrue(allDue.status.endsWith("everything scheduled is already due"))
        assertTrue(MatrixFleetText.carOf("A", "m", next()).status.endsWith("nothing due yet"))
    }

    @Test
    fun `items with no anchor are named as not known, not dropped`() {
        val car = MatrixFleetText.carOf("Civic", "84,000 mi", next(unknown = listOf("Brake fluid", "Coolant")))
        assertTrue(car.unknown!!.contains("2 maintenance item(s) have no recorded last-done date"))
        assertTrue(car.unknown!!.contains("Brake fluid, Coolant"))
        assertTrue(car.unknown!!.contains("not known to be fine"))
        assertNull(MatrixFleetText.carOf("Civic", "84,000 mi", next()).unknown)
    }

    @Test
    fun `a car whose schedule could not be read says that, apart from no schedule yet`() {
        val car = MatrixFleetText.unreadableCar("Civic", "84,000 mi")
        assertTrue(car.failed)
        assertTrue(car.status.contains("couldn't read the maintenance schedule"))
        assertFalse(car.status.contains("no maintenance schedule yet"))
        assertFalse(MatrixFleetText.carOf("Civic", "84,000 mi", null).failed)
    }
}
