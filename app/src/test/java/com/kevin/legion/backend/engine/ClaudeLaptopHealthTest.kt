package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.claude.ClaudeMachine
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeStatus
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.LaptopHealth
import com.kevin.legion.claude.LaptopHealthWording
import com.kevin.legion.ui.claudesession.ClaudeQuickRows
import com.kevin.legion.ui.claudesession.ClaudeSessionRows
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Laptop health on the phone (2026-11-10): `status/`'s `health` parsed (and absent on an older
 * engine), and the sheet's and screen's lines built from it. Readings are made up.
 */
@RunWith(RobolectricTestRunner::class)
class ClaudeLaptopHealthTest {

    private val context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-12T03:05:00Z")
    private val wording = ClaudeWording(now = { now }, zone = { ZoneOffset.UTC })

    private fun statusFrom(body: String): ClaudeStatus = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(body) }
        val out = DjangoClaudeSessionBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))
            .status()
        assertTrue("status read: $out", out is ClaudeOutcome.Ok)
        (out as ClaudeOutcome.Ok).value
    }

    @Test
    fun `health is parsed with its 24 h temps`() {
        val h = statusFrom(NEW_STATUS).machines.single().health!!
        assertEquals(Instant.parse("2026-11-12T03:04:00Z"), h.at)
        assertEquals(61.0, h.cpuC!!, 0.0)
        assertNull(h.gpuC)
        assertEquals(mapOf("cpu" to 3100, "gpu" to 2900), h.fansRpm)
        assertEquals(70.1, h.ramUsedPct!!, 0.0)
        assertEquals(180.2, h.diskFreeGb!!, 0.0)
        assertEquals(93_600L, h.uptimeS)
        assertEquals(false, h.laptopctlAgent)
        assertEquals(listOf("agent_down"), h.alerts)
        assertEquals(3, h.temps24h.size)
        assertEquals(Instant.parse("2026-11-12T02:40:00Z"), h.temps24h[0].at)
        assertNull(h.temps24h[2].gpuC)
        assertEquals(listOf(55f, 60f, 63f), LaptopHealthWording.spark(h))
    }

    @Test
    fun `an older engine or a machine that sends none has no health`() {
        assertNull(statusFrom(OLD_STATUS).machines.single().health)
        assertNull(statusFrom(OLD_STATUS.replace("[]}]", "[], \"health\": null}]")).machines.single().health)
    }

    @Test
    fun `the screen lines and the sheet line say the readings in words`() {
        val m = statusFrom(NEW_STATUS).machines.single()
        val row = ClaudeSessionRows.machines(listOf(m), wording).single()
        assertEquals(
            listOf(
                "CPU 61 C, RAM 70%, C: 180 GB free",
                "CPU load 12%, fans 3100/2900 rpm, up 1 d 2 h",
                "Laptopctl agent not running",
                "Alert: laptopctl stopped",
                "Read 1 min ago",
            ),
            row.health,
        )
        assertTrue(row.alert)
        assertEquals(3, row.spark.size)
        val overview = ClaudeOverview(ClaudeStatus(listOf(m), emptyList()), emptyList())
        assertEquals(
            listOf("Alienware: online, CPU 61 C, RAM 70%, C: 180 GB free. Alert: laptopctl stopped"),
            ClaudeQuickRows.laptops(overview, wording),
        )
    }

    @Test
    fun `an offline machine says since when`() {
        val m = ClaudeMachine(
            "Alienware", "full", ClaudeMachine.STATE_OFFLINE, Instant.parse("2026-11-12T01:00:00Z"),
            emptyList(), emptyMap(), health = LaptopHealth(at = null, cpuC = 50.0, alerts = listOf("offline")),
        )
        assertEquals(
            "Alienware: off since 1:00 AM, CPU 50 C. Alert: offline",
            LaptopHealthWording.summary(m, wording),
        )
        assertNull(LaptopHealthWording.summary(m.copy(health = null), wording))
    }

    private companion object {
        /** An engine from before claude_session 0008: no `health` key. */
        const val OLD_STATUS = """{"machines": [{"name": "Alienware", "scope": "full", "state": "idle",
            "last_seen_at": "2026-11-10T12:00:00Z", "running": [], "progress": []}], "sessions": []}"""

        const val NEW_STATUS = """{"machines": [{"name": "Alienware", "scope": "full", "state": "idle",
            "last_seen_at": "2026-11-12T03:04:50Z", "running": [], "progress": [], "workflows": [],
            "health": {"at": "2026-11-12T03:04:00Z", "cpu_c": 61.0, "gpu_c": null,
            "fans_rpm": {"cpu": 3100, "gpu": 2900}, "cpu_load_pct": 12.5, "ram_used_pct": 70.1,
            "disk_free_gb": 180.2, "uptime_s": 93600, "boot_at": "2026-11-11T01:05:00+00:00",
            "laptopctl_agent": false, "on_mains": true, "reboot_pending": false,
            "alerts": ["agent_down"], "temps_24h": [["2026-11-12T02:40:00+00:00", 55.0, 50.0],
            ["2026-11-12T02:50:00+00:00", 58.0, 60.0], ["2026-11-12T03:00:00+00:00", 63.0, null]]}}],
            "sessions": []}"""
    }
}
