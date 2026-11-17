package com.kevin.legion.vehicle.diag

import com.kevin.legion.assistant.cards.RowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The module scan card never draws a module it could not read as clean. Made-up modules only. */
class DiagCardTest {

    private fun report(
        modules: List<ModuleReport> = emptyList(),
        engine: EngineReport? = EngineReport(
            CodeRead(Outcome.READ, "03"), CodeRead(Outcome.READ, "07"), CodeRead(Outcome.READ, "0A"),
        ),
        stopped: String? = null,
        coverage: Coverage = Coverage("quick", 4, 4, ""),
    ) = ScanReport(
        startedAtMs = 0, finishedAtMs = 1_000,
        adapter = AdapterSummary("ELM327", "ELM327 v1.5", words = ""),
        bus = BusKind.CAN_11, engine = engine, modules = modules, coverage = coverage, stopped = stopped,
    )

    private fun module(address: String, read: CodeRead) = ModuleReport(address, "", "Test module", codes = read)

    @Test
    fun `each outcome gets its own status, and only a read with no faults is OK`() {
        val card = DiagCard.scan(
            "test car",
            report(
                listOf(
                    module("7E1", CodeRead(Outcome.READ, "19")),
                    module("760", CodeRead(Outcome.READ, "19", listOf(ModuleDtc("C0040")))),
                    module("726", CodeRead(Outcome.REFUSED, "19", detail = "security access denied")),
                    module("737", CodeRead(Outcome.NO_ANSWER, "19")),
                    module("720", CodeRead(Outcome.PARTIAL, "19", detail = "cut off")),
                ),
            ),
        )
        val byName = card.items.associate { it.name to it.status }
        assertEquals(RowStatus.OK, byName["Test module (7E1)"])
        assertEquals(RowStatus.ATTENTION, byName["Test module (760)"])
        assertEquals(RowStatus.UNKNOWN, byName["Test module (726)"])
        assertEquals(RowStatus.NOT_REACHED, byName["Test module (737)"])
        assertEquals(RowStatus.UNKNOWN, byName["Test module (720)"])
        assertEquals(RowStatus.OK, byName["Engine (generic)"])
        assertEquals("needs-a-look rows come first", RowStatus.ATTENTION, card.items.first().status)
        assertTrue(card.note!!.contains(DiagCard.NOT_LISTED))
        assertEquals(1_000L, card.provenance?.readAtMs)
    }

    @Test
    fun `a stopped scan is one unknown row, never an empty card`() {
        val card = DiagCard.scan("test car", report(engine = null, stopped = "The car is moving."))
        assertEquals(1, card.items.size)
        assertEquals(RowStatus.UNKNOWN, card.items.single().status)
        assertEquals("The car is moving.", card.items.single().note)
    }

    @Test
    fun `engine with one read failed is unknown, with none read is not reached`() {
        val half = EngineReport(
            CodeRead(Outcome.READ, "03"), CodeRead(Outcome.NO_ANSWER, "07"), CodeRead(Outcome.READ, "0A"),
        )
        assertEquals(RowStatus.UNKNOWN, DiagCard.scan("c", report(engine = half)).items.single().status)
        assertEquals(RowStatus.NOT_REACHED, DiagCard.scan("c", report(engine = null)).items.single().status)
    }

    @Test
    fun `a partial scan says so and rows past the limit are counted`() {
        val many = (0 until 15).map { module("7%02X".format(it), CodeRead(Outcome.READ, "19")) }
        val card = DiagCard.scan("c", report(many, coverage = Coverage("quick", 40, 20, "", timeBudgetHit = true)))
        assertEquals(12, card.items.size)
        assertEquals(4, card.more)
        assertTrue(card.note!!.startsWith("Partial scan: 20 of 40"))
    }
}
