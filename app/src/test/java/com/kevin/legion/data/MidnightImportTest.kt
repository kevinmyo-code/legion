package com.kevin.legion.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [MidnightImport.parseManifest] and
 * [MidnightImport.importOrder] - the two parts of the one-time Midnight AI
 * fleet-history import that touch no Android API and so run on a plain JVM.
 * Everything else in [MidnightImport] (the actual DB reads/writes) needs a
 * real `SupportSQLiteDatabase` and is out of reach of `testDebugUnitTest`
 * for the same reason `LedgerController`'s DB-write paths are (CLAUDE.md
 * sec 10) - not chased here for the same judgment call.
 */
class MidnightImportTest {

    @Test
    fun `parseManifest reads the tables map`() {
        val json = """
            {"tables":{"vehicles":2,"obd_samples":11511,"places":4},"source":"midnight_ai v12"}
        """.trimIndent().toByteArray()

        val tables = MidnightImport.parseManifest(json)

        assertEquals(mapOf("vehicles" to 2, "obd_samples" to 11511, "places" to 4), tables)
    }

    @Test
    fun `parseManifest handles an empty tables map`() {
        val json = """{"tables":{},"source":"midnight_ai v12"}""".toByteArray()

        assertEquals(emptyMap<String, Int>(), MidnightImport.parseManifest(json))
    }

    @Test
    fun `importOrder puts vehicles first regardless of manifest order`() {
        val manifestTables = setOf("obd_samples", "places", "vehicles", "code_events")

        val order = MidnightImport.importOrder(manifestTables)

        assertEquals("vehicles", order.first().table)
    }

    @Test
    fun `importOrder drops a table not present in the manifest`() {
        val manifestTables = setOf("vehicles", "places") // no obd_samples this time

        val order = MidnightImport.importOrder(manifestTables)

        assertEquals(listOf("vehicles", "places"), order.map { it.table })
    }

    @Test
    fun `importOrder ignores an unrecognized table name rather than crashing`() {
        val manifestTables = setOf("vehicles", "some_future_table_this_importer_does_not_know")

        val order = MidnightImport.importOrder(manifestTables)

        assertEquals(listOf("vehicles"), order.map { it.table })
    }

    @Test
    fun `importOrder returns nothing for an empty manifest`() {
        assertTrue(MidnightImport.importOrder(emptySet()).isEmpty())
    }

    @Test
    fun `every spec's identity columns are non-empty`() {
        // A blank identity list would make every row in that table compare
        // equal to every other (an empty List<Any?> key), silently collapsing
        // an entire table's rows into one on import.
        for (spec in MidnightImport.SPECS) {
            assertTrue("${spec.table} has an empty identity list", spec.identity.isNotEmpty())
        }
    }

    @Test
    fun `the real manifest bundle's tables all resolve to a known spec`() {
        // Guards the exporter and this importer staying in sync: every table
        // name tools/export_midnight_ai.py currently emits must have a
        // TableSpec here, or that table would silently import zero rows.
        val exportedTables = setOf(
            "obd_samples", "code_events", "companion_memories", "daily_drive_logs",
            "maintenance_items", "car_tasks", "places", "memories", "monthly_recaps",
            "vehicle_specs", "vehicles", "build_entries", "service_records",
        )
        val knownTables = MidnightImport.SPECS.map { it.table }.toSet()

        assertEquals(exportedTables, MidnightImport.importOrder(exportedTables).map { it.table }.toSet())
        assertEquals(exportedTables, knownTables)
    }

    // --- syntheticVehicleId (the 2026-08-03 sentinel-collision fix) ------------

    @Test
    fun `syntheticVehicleId rebuilds the real Outlander row's id`() {
        // The exact row from assets/midnight_import/vehicles.json.gz that collided
        // with LEGION's placeholder and lost.
        val outlander = JSONObject(
            """{"obdMac":"default","name":"Midnight","make":"Mitsubishi","model":"Outlander","year":2020}""",
        )

        assertEquals("imported-mitsubishi-outlander-2020", MidnightImport.syntheticVehicleId(outlander))
    }

    @Test
    fun `syntheticVehicleId is stable across calls so a re-run cannot duplicate`() {
        // The property the whole repair depends on: identity-keyed dedup only
        // holds if the id is derived, not minted. A UUID here would insert a
        // second Outlander on every pass.
        val row = JSONObject("""{"obdMac":"default","make":"Mitsubishi","model":"Outlander","year":2020}""")

        assertEquals(MidnightImport.syntheticVehicleId(row), MidnightImport.syntheticVehicleId(row))
    }

    @Test
    fun `syntheticVehicleId never returns the sentinel it exists to replace`() {
        val blank = JSONObject("""{"obdMac":"default","make":"","model":"","year":0}""")

        val id = MidnightImport.syntheticVehicleId(blank)

        assertEquals("imported-vehicle", id)
        assertTrue(id != MidnightImport.SENTINEL_VEHICLE_ID)
    }

    @Test
    fun `syntheticVehicleId slugs punctuation and spacing out of make and model`() {
        val row = JSONObject("""{"make":"Mercedes-Benz ","model":"C 300 (W205)","year":2018}""")

        assertEquals("imported-mercedes-benz-c-300-w205-2018", MidnightImport.syntheticVehicleId(row))
    }

    @Test
    fun `syntheticVehicleId distinguishes two cars that share the sentinel id`() {
        // Two devices, each with its own "default" car, exported and imported into
        // one database - the case that must never collapse into a single row.
        val outlander = JSONObject("""{"make":"Mitsubishi","model":"Outlander","year":2020}""")
        val cherokee = JSONObject("""{"make":"Jeep","model":"Cherokee","year":1998}""")

        assertTrue(
            MidnightImport.syntheticVehicleId(outlander) != MidnightImport.syntheticVehicleId(cherokee),
        )
    }
}
