package com.kevin.legion.vehicle

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The bundled trouble-code dictionary must exist, parse, and carry the common generic codes
 * (2026-11-03). It was never ported from Midnight AI into the phone app: [DtcDescriptions.loadSeed]
 * swallowed the missing asset and every code on the phone read "Not yet described". This test is
 * the guard that it cannot silently go missing again: one check reads the source file, one goes
 * through the real [DtcDescriptions.loadSeed] on the merged assets.
 */
@RunWith(RobolectricTestRunner::class)
class DtcSeedAssetTest {

    private val codeShape = Regex("^[PBCU][0-3][0-9A-F]{3}$")

    @Test
    fun `the seed file exists, parses, and every entry is a generic code with a title and detail`() {
        val file = File("src/main/assets/dtc_descriptions_seed.json")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val root = JSONObject(file.readText())
        assertTrue("seed shrank to ${root.length()} codes", root.length() >= 128)
        for (code in root.keys()) {
            assertTrue("bad code key $code", codeShape.matches(code))
            val o = root.getJSONObject(code)
            assertTrue("$code has no title", o.getString("title").isNotBlank())
            assertTrue("$code has no detail", o.getString("detail").isNotBlank())
        }
    }

    @Test
    fun `loadSeed reads the bundled asset and knows P0420 and P0128`() {
        val seed = DtcDescriptions.loadSeed(ApplicationProvider.getApplicationContext())
        assertTrue("seed loaded only ${seed.size} codes", seed.size >= 128)
        assertEquals("Catalyst System Efficiency Below Threshold (Bank 1)", seed["P0420"]?.first)
        assertEquals("Coolant Thermostat Below Regulating Temperature", seed["P0128"]?.first)
    }
}
