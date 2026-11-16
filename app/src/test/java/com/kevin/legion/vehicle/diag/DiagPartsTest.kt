package com.kevin.legion.vehicle.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces: byte layouts, reply parsing, status words, code text, packs, compare. */
class DiagPartsTest {

    @Test
    fun `SAE code packing`() {
        assertEquals("P0133", DtcBytes.sae(0x01, 0x33))
        assertEquals("U0100", DtcBytes.sae(0xC1, 0x00))
        assertEquals("B1A12", DtcBytes.sae(0x9A, 0x12))
        assertEquals("C0040", DtcBytes.sae(0x40, 0x40))
    }

    @Test
    fun `CAN generic codes carry a count byte, legacy ones do not`() {
        val can = listOf(0x02, 0x01, 0x33, 0x04, 0x20, 0x00)
        assertEquals(listOf("P0133", "P0420"), DtcBytes.obdCodes(can, can = true))
        assertEquals(emptyList<String>(), DtcBytes.obdCodes(listOf(0x00), can = true))
        val legacy = listOf(0x01, 0x33, 0x04, 0x20, 0x00, 0x00)
        assertEquals(listOf("P0133", "P0420"), DtcBytes.obdCodes(legacy, can = false))
    }

    @Test
    fun `UDS status words`() {
        assertEquals(listOf("confirmed", "warning light requested"), DtcStatus.words(0x88))
        assertEquals(listOf("history"), DtcStatus.words(0x20))
        assertEquals(listOf("not failed (test not complete)"), DtcStatus.words(0x50))
        assertFalse(DtcStatus.isFault(0x50))
        assertTrue(DtcStatus.isFault(0x04))
    }

    @Test
    fun `two senders reassemble apart, a missing consecutive frame is cut off`() {
        val raw = "7E8 10 0B 59 02 FF 01 33 00\r7E9 03 7F 19 11 00 00 00 00\r7E8 21 2F 04 20 00 2F 00 00"
        val r = ElmReply.parseCan(raw, BusKind.CAN_11) as ElmReply.Messages
        assertEquals(2, r.messages.size)
        val ecm = r.messages.first { it.sender == 0x7E8L }
        assertTrue(ecm.complete)
        assertEquals(listOf(0x59, 0x02, 0xFF, 0x01, 0x33, 0x00, 0x2F, 0x04, 0x20, 0x00, 0x2F), ecm.data)
        val cut = ElmReply.parseCan("7E8 10 0B 59 02 FF 01 33 00\r7E8 22 00 00 00 00 00 00 00", BusKind.CAN_11)
        assertFalse((cut as ElmReply.Messages).messages.single().complete)
    }

    @Test
    fun `adapter failure words`() {
        assertEquals(ElmReply.NoData, ElmReply.parseCan("NO DATA", BusKind.CAN_11))
        assertEquals(ElmReply.NotUnderstood, ElmReply.parseCan("?", BusKind.CAN_11))
        assertEquals(ElmReply.Silent, ElmReply.parseCan("", BusKind.CAN_11))
        assertEquals(ElmReply.BusError("CAN ERROR"), ElmReply.parseCan("CAN ERROR", BusKind.CAN_11))
        assertEquals(BusKind.CAN_29, BusKind.fromDpn("A7"))
        assertEquals(BusKind.ISO_9141, BusKind.fromDpn("3"))
    }

    @Test
    fun `negative answers are classified with plain words`() {
        val m = CanMessage(0x7E8, listOf(0x7F, 0x19, 0x31), true)
        assertEquals(Uds.Answer.Negative(0x31), Uds.classify(m, 0x19))
        assertEquals("request out of range", Uds.nrcWords(0x31))
        assertEquals("negative response 0x99", Uds.nrcWords(0x99))
    }

    @Test
    fun `code text comes from the public table, manufacturer codes are never described`() {
        val table = DtcText.parseTable(PackFiles.codeTable())
        assertTrue(table.size > 400)
        assertTrue(table.keys.all { it.startsWith("P0") })
        val known = DtcText.describe("P0420", table, emptyMap())
        assertEquals(DtcText.TABLE_SOURCE, known.source)
        assertEquals(DtcText.MANUFACTURER, DtcText.describe("P1391", table, emptyMap()).words)
        assertEquals(DtcText.MANUFACTURER, DtcText.describe("B1A12", table, emptyMap()).words)
        assertEquals(DtcText.GENERIC_UNKNOWN, DtcText.describe("U0100", table, emptyMap()).words)
        assertEquals("from seed", DtcText.describe("P2096", table, mapOf("P2096" to ("from seed" to "x"))).title)
    }

    @Test
    fun `pack rows parse, stay unverified, and decode`() {
        val rows = DiagPacks.parse(PackFiles.text())
        assertEquals(setOf("ford", "mitsubishi"), rows.map { it.make }.toSet())
        assertTrue("no row is verified until Kevin checks it", rows.none { it.verified })
        assertTrue(DiagPacks.forMake(rows, "Jeep").isEmpty())
        val tft = rows.first { it.key == "ford_tft_1e1c" }
        assertEquals(listOf(0x22, 0x1E, 0x1C), tft.requestBytes())
        assertEquals("80.0", tft.decode(listOf(0x05, 0x00)))
        assertEquals("-1.0", tft.decode(listOf(0xFF, 0xF0)))
        assertNull(tft.decode(listOf(0x05)))
        assertEquals(listOf(0x21, 0x03), rows.first { it.key == "mitsubishi_cvt_temp_2103" }.requestBytes())
    }

    @Test
    fun `a pack row naming anything but a read service is dropped`() {
        fun row(key: String, service: String) =
            """{"make":"ford","key":"$key","label":"x","module":"7E0","service":"$service","identifier":"01",""" +
                """"applies":"made-up","source_url":"https://example.invalid"}"""
        val text = """{"readings":[${listOf(
            row("read_22", "22"), row("read_21", "21"), row("write_2e", "2E"),
            row("security_27", "27"), row("routine_31", "31"), row("session_10", "10"), row("junk", "zz"),
        ).joinToString(",")}]}"""
        assertEquals(listOf("read_22", "read_21"), DiagPacks.parse(text).map { it.key })
        assertTrue(DiagPacks.parse(PackFiles.text()).all { it.service.toInt(16) in DiagPacks.READ_SERVICES })
    }

    @Test
    fun `compare says new, gone, unchanged, and what could not be rechecked`() {
        fun report(engine: List<String>, abs: List<String>, absOutcome: Outcome) = ScanReport(
            startedAtMs = 0, finishedAtMs = 0,
            adapter = AdapterSummary("ELM327", "ELM327 v1.5", words = ""),
            bus = BusKind.CAN_11,
            engine = EngineReport(
                CodeRead(Outcome.READ, "03", engine.map { ModuleDtc(it) }),
                CodeRead(Outcome.READ, "07"),
                CodeRead(Outcome.READ, "0A"),
            ),
            modules = listOf(
                ModuleReport("760", "768", "ABS", codes = CodeRead(absOutcome, "19", abs.map { ModuleDtc(it) })),
            ),
            coverage = Coverage("quick", 1, 1, ""),
        )
        val d = ScanCompare.diff(
            report(listOf("P0420", "P0171"), listOf("C0040"), Outcome.READ),
            report(listOf("P0420", "P0300"), emptyList(), Outcome.REFUSED),
        )
        assertEquals(listOf("P0300 (engine, stored)"), d.added)
        assertEquals(listOf("P0171 (engine, stored)"), d.gone)
        assertEquals(listOf("P0420 (engine, stored)"), d.unchanged)
        assertEquals(listOf("C0040 (ABS)"), d.notRechecked)
    }

    @Test
    fun `one engine read failing is said, never no stored or pending codes`() {
        fun words(stored: CodeRead, pending: CodeRead) = ScanWords.summary(
            ScanReport(
                startedAtMs = 0, finishedAtMs = 0,
                adapter = AdapterSummary("ELM327", "ELM327 v1.5", words = ""),
                bus = BusKind.ISO_9141,
                engine = EngineReport(stored, pending, CodeRead(Outcome.READ, "0A")),
                coverage = Coverage("quick", 0, 0, ""),
            ),
        )
        val pendingLost = words(CodeRead(Outcome.READ, "03"), CodeRead(Outcome.NO_ANSWER, "07", detail = "no answer"))
        assertTrue(pendingLost, pendingLost.contains("no stored codes; pending codes not read (no answer)"))
        assertFalse(pendingLost.contains("no stored or pending codes"))
        val storedLost =
            words(CodeRead(Outcome.REFUSED, "03"), CodeRead(Outcome.READ, "07", listOf(ModuleDtc("P0133"))))
        assertTrue(storedLost, storedLost.contains("P0133; stored codes not read (refused)"))
    }

    @Test
    fun `a CAN scan with no faults still says what was not reached`() {
        val r = ScanReport(
            startedAtMs = 0, finishedAtMs = 0,
            adapter = AdapterSummary("ELM327", "ELM327 v1.5", words = ""),
            bus = BusKind.CAN_11,
            engine = EngineReport(
                CodeRead(Outcome.READ, "03"), CodeRead(Outcome.READ, "07"), CodeRead(Outcome.READ, "0A"),
            ),
            modules = listOf("7E0", "7E1", "760").map { ModuleReport(it, "", codes = CodeRead(Outcome.READ, "19")) },
            coverage = Coverage("quick", 3, 3, ""),
        )
        val words = ScanWords.summary(r)
        assertTrue(words, words.contains("3 module(s) answered; 0 with fault codes."))
        assertTrue(words, words.contains(ScanWords.UNREACHED))
        assertTrue(ScanWords.UNREACHED.contains("not known to be clear"))
    }

    @Test
    fun `report survives a JSON round trip`() {
        val r = ScanReport(
            startedAtMs = 1, finishedAtMs = 2, adapter = ElmSetup.summarize("ELM327 v2.1", null),
            bus = BusKind.J1850_VPW, coverage = Coverage("quick", 0, 0, "none"), notes = listOf("n"),
        )
        assertEquals(r, ScanReport.fromJson(r.toJson()))
        assertTrue(ElmSetup.summarize("ELM327 v1.4b", "STN1110 v4").words.startsWith("STN-based"))
    }
}
