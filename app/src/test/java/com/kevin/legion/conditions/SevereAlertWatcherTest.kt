package com.kevin.legion.conditions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 5: the severe-weather raise's decisions, with no network and no Android. */
class SevereAlertWatcherTest {

    private class MemorySeen : SeenAlerts {
        val rows = mutableMapOf<String, Long>()
        override fun seen() = rows.toMap()
        override fun markSeen(id: String, atMs: Long) { rows[id] = atMs }
        override fun forgetOlderThan(cutoffMs: Long) { rows.entries.removeIf { it.value < cutoffMs } }
    }

    private fun alert(id: String, event: String = "Tornado Warning") = SevereAlert(
        id, event, "Extreme", "Tornado Warning until 4 PM", "Example County", "NWS Example",
        "2026-11-10T16:00:00-06:00",
    )

    private fun read(vararg alerts: SevereAlert) = ConditionsOutcome.Read("text", emptyList(), alerts.toList())

    @Test
    fun `a new alert is raised once, and not again on the next poll`() = runBlocking {
        val seen = MemorySeen()
        val said = mutableListOf<Pair<String, String>>()
        val watcher = SevereAlertWatcher(
            check = { read(alert("a1")) },
            here = { ConditionsPoint.Here(30.0, -98.0) },
            seenStore = seen,
            announce = { a, where -> said += a.id to where; AlertDelivery.DELIVERED },
            clockMs = { 1_000L },
        )
        assertEquals(listOf("a1"), watcher.pollOnce().map { it.id })
        assertEquals(listOf("a1" to "where you are"), said)
        assertTrue(watcher.pollOnce().isEmpty())
        assertEquals(1, said.size)
    }

    @Test
    fun `the same alert here and at home is said once`() = runBlocking {
        val asked = mutableListOf<ConditionsPoint>()
        var count = 0
        val watcher = SevereAlertWatcher(
            check = { asked += it; read(alert("same")) },
            here = { ConditionsPoint.Here(30.0, -98.0) },
            seenStore = MemorySeen(),
            announce = { _, _ -> count++; AlertDelivery.DELIVERED },
        )
        watcher.pollOnce()
        assertEquals(1, count)
        assertEquals(listOf(ConditionsPoint.Here(30.0, -98.0), ConditionsPoint.Place("home")), asked)
    }

    @Test
    fun `no fix still checks home, and a failed read raises nothing`() = runBlocking {
        val asked = mutableListOf<ConditionsPoint>()
        var count = 0
        val watcher = SevereAlertWatcher(
            check = { asked += it; ConditionsOutcome.Failed("The engine could not be reached.") },
            here = { null },
            seenStore = MemorySeen(),
            announce = { _, _ -> count++; AlertDelivery.DELIVERED },
        )
        assertTrue(watcher.pollOnce().isEmpty())
        assertEquals(listOf<ConditionsPoint>(ConditionsPoint.Place("home")), asked)
        assertEquals(0, count)
    }

    @Test
    fun `not now is retried on the next poll`() = runBlocking {
        val seen = MemorySeen()
        var answer = AlertDelivery.RETRY
        val watcher = SevereAlertWatcher(
            check = { read(alert("r1")) },
            here = { null },
            seenStore = seen,
            announce = { _, _ -> answer },
        )
        assertTrue(watcher.pollOnce().isEmpty())
        assertFalse("r1" in seen.rows)
        answer = AlertDelivery.DELIVERED
        assertEquals(1, watcher.pollOnce().size)
    }

    @Test
    fun `seen ids are forgotten after two days`() = runBlocking {
        val seen = MemorySeen().apply { rows["old"] = 0L }
        var now = SevereAlertWatcher.FORGET_AFTER_MS + 1
        var count = 0
        val watcher = SevereAlertWatcher(
            check = { read(alert("old")) }, here = { null }, seenStore = seen,
            announce = { _, _ -> count++; AlertDelivery.DELIVERED }, clockMs = { now },
        )
        watcher.pollOnce()
        assertEquals(1, count)
    }

    @Test
    fun `the facts name the alert and its source and nothing about the user`() {
        val facts = SevereAlertWatcher.facts(alert("x", "Hurricane Warning"), "at home")
        assertTrue(facts.startsWith("The National Weather Service has an active Hurricane Warning (Extreme) at home"))
        assertTrue("until 2026-11-10T16:00:00-06:00" in facts && "NWS Example" in facts)
        val prompt = SevereAlertWatcher.prompt(facts)
        assertTrue(facts in prompt)
        // Compulsion test (c): nothing about absence, streaks or use of the app.
        for (banned in listOf("haven't", "streak", "miss", "been a while", "come back", "days since")) {
            assertFalse(banned, prompt.lowercase().contains(banned))
        }
    }

    @Test
    fun `the fix sentence is coordinates, accuracy and age`() {
        assertEquals(
            "lat 30.05000, lon -98.85000 (about 12 m, 3 min old)",
            ConditionsWords.fixText(30.05, -98.85, 12.4f, 3 * 60_000L + 5),
        )
        assertEquals("lat 1.00000, lon 2.00000 (0 min old)", ConditionsWords.fixText(1.0, 2.0, null, -5))
    }
}
