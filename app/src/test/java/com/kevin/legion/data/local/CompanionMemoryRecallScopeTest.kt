package com.kevin.legion.data.local

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CompanionMemoryDao.getRecallScan] - what the companion can still remember about the driver when
 * he changes cars.
 *
 * `companion_memories` is keyed by `vehicleId` because it was built for a car launcher, and recall
 * used to read only the active car's slice. On Kevin's real device that hid **46 memories about
 * him** whenever the Jeep was active: his music taste, his work address, his opinion of an album.
 * LEGION is a phone assistant with a fleet aspect, so the person is the same person in every car.
 *
 * These cases are the whole rule: **driver and relationship memories cross cars, car facts do
 * not.** The second half matters as much as the first - surfacing the Outlander's service history
 * while he is sitting in the Jeep would be a different bug, not a fix.
 */
@RunWith(RobolectricTestRunner::class)
class CompanionMemoryRecallScopeTest {

    private lateinit var db: CarDatabase
    private lateinit var dao: CompanionMemoryDao

    private val jeep = "00:1D:A5:0E:82:0E"
    private val outlander = "imported-mitsubishi-outlander-2020"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.companionMemoryDao()
    }

    @After
    fun tearDown() = db.close()

    private fun memory(vehicleId: String, text: String, category: String) = CompanionMemory(
        vehicleId = vehicleId,
        text = text,
        category = category,
        source = CompanionMemory.Source.CONSOLIDATED,
        createdAt = System.currentTimeMillis(),
    )

    @Test
    fun `a driver memory formed in one car is recalled in another`() = runBlocking {
        dao.insert(memory(outlander, "Listens to Attack on Titan opening themes", CompanionMemory.Category.DRIVER))

        val scan = dao.getRecallScan(jeep, 50)

        assertEquals(
            "a fact about the driver must survive changing cars - this is the whole defect",
            1, scan.size,
        )
        assertTrue(scan.first().text.contains("Attack on Titan"))
    }

    @Test
    fun `a relationship memory also crosses cars`() = runBlocking {
        dao.insert(memory(outlander, "Calls the companion by a nickname", CompanionMemory.Category.RELATIONSHIP))

        assertEquals(1, dao.getRecallScan(jeep, 50).size)
    }

    @Test
    fun `another car's service history does NOT leak into this car`() = runBlocking {
        dao.insert(memory(outlander, "Oil change on the Outlander at 73,500 miles", CompanionMemory.Category.CAR_ANCHORED))

        assertTrue(
            "car facts must stay with their car - the fix must not become a leak in the other direction",
            dao.getRecallScan(jeep, 50).isEmpty(),
        )
    }

    @Test
    fun `this car's own service history is still recalled`() = runBlocking {
        dao.insert(memory(jeep, "Oil change on the Cherokee at 227,483 miles", CompanionMemory.Category.CAR_ANCHORED))

        val scan = dao.getRecallScan(jeep, 50)
        assertEquals(1, scan.size)
        assertTrue(scan.first().text.contains("Cherokee"))
    }

    @Test
    fun `a mixed fleet returns every driver memory and only the active car's facts`() = runBlocking {
        dao.insert(memory(outlander, "Prefers the windows down", CompanionMemory.Category.DRIVER))
        dao.insert(memory(jeep, "Works on Bunker Hill Road", CompanionMemory.Category.DRIVER))
        dao.insert(memory(outlander, "Outlander: new brakes", CompanionMemory.Category.CAR_ANCHORED))
        dao.insert(memory(jeep, "Cherokee: new brakes", CompanionMemory.Category.CAR_ANCHORED))

        val texts = dao.getRecallScan(jeep, 50).map { it.text }

        assertEquals(3, texts.size)
        assertTrue(texts.any { it.contains("windows down") })
        assertTrue(texts.any { it.contains("Bunker Hill") })
        assertTrue(texts.any { it.contains("Cherokee: new brakes") })
        assertTrue(
            "the other car's brake job must not appear",
            texts.none { it.contains("Outlander: new brakes") },
        )
    }

    @Test
    fun `the old per-car query is untouched, so the reset and reflection paths still scope`() = runBlocking {
        dao.insert(memory(outlander, "Prefers the windows down", CompanionMemory.Category.DRIVER))

        assertTrue(
            "getRecent must stay car-scoped - deleteForVehicle and reflection still rely on it",
            dao.getRecent(jeep, 50).isEmpty(),
        )
    }
}
