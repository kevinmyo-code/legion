package com.kevin.legion.assistant.cards

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.vehicle.VehicleController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each phone builder makes its card from the tool's own data: cents, flags in words, offset zones. */
class PhoneCardsTest {

    private val month = YearMonth.of(2026, 11)
    private val chicago = ZoneId.of("America/Chicago")

    private fun line(category: String, cents: Long, provisional: Boolean = false, guessed: Boolean = false) =
        BudgetLine(category, PlanGap(0L, cents, -cents, TrustTier.PROVEN), provisional, guessed)

    private fun budget(
        lines: List<BudgetLine>,
        uncategorizedCents: Long = 0L,
        complete: Boolean = true,
    ) = BudgetVsActual(
        entity = LedgerEntity.US,
        month = month,
        lines = lines,
        uncategorized = UncategorizedSpend(uncategorizedCents, false),
        coverage = listOf(AccountCoverage("checking", complete, null, null)),
        excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
    )

    @Test
    fun `spend bars are Long cents per category, biggest first, with flags`() {
        val card = PhoneCards.monthlySpend(
            budget(
                listOf(
                    line("Food", 12_345L),
                    line("Gas", 99_999L, provisional = true),
                    line("Fun", 500L, guessed = true),
                ),
            ),
            month,
        )
        assertEquals("usd", card.unit)
        assertEquals(listOf("Gas", "Food", "Fun"), card.points.map { it.label })
        assertEquals(listOf(99_999L, 12_345L, 500L), card.points.map { it.value })
        assertEquals(listOf(CardFlag.UNVERIFIED, null, CardFlag.ESTIMATE), card.points.map { it.flag })
        assertEquals("Spending, $1,128.44 in all", card.title)
        assertEquals("November 2026", card.period)
        assertEquals("US", card.scope)
    }

    @Test
    fun `uncategorised spend and missing coverage are said in the note`() {
        val card = PhoneCards.monthlySpend(
            budget(listOf(line("Food", 100L)), uncategorizedCents = 4_200L, complete = false),
            month,
        )
        val note = card.note!!
        assertTrue(note, note.contains("unverified"))
        assertTrue(note, note.contains("USD 42.00 is not assigned to a category"))
    }

    @Test
    fun `more than eight categories leave the rest as more`() {
        val card = PhoneCards.monthlySpend(budget((1..11).map { line("C$it", it * 100L) }), month)
        assertEquals(8, card.points.size)
        assertEquals(3, card.more)
    }

    @Test
    fun `money is formatted from cents`() {
        assertEquals("$0.05", PhoneCards.money(5L, "usd"))
        assertEquals("-$1,000.00", PhoneCards.money(-100_000L, "usd"))
        assertEquals("SGD 3.10", PhoneCards.money(310L, "sgd"))
        assertEquals("7", PhoneCards.money(7L, ""))
    }

    @Test
    fun `zone is a UTC offset, never an IANA id`() {
        assertEquals("UTC-06:00", PhoneCards.zoneLabel(ZoneOffset.ofHours(-6)))
        assertEquals("UTC+05:30", PhoneCards.zoneLabel(ZoneOffset.ofHoursMinutes(5, 30)))
        assertEquals("UTC+00:00", PhoneCards.zoneLabel(ZoneOffset.UTC))
    }

    private fun event(
        title: String,
        start: Long?,
        end: Long? = null,
        allDay: Boolean = false,
        kind: String = EventKind.EVENT,
    ) = Event(
        serverId = null, title = title, startsAt = start, endsAt = end, allDay = allDay,
        source = "test", updatedAtMs = 0L, kind = kind,
    )

    private fun utcMidnight(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(chicago).toInstant().toEpochMilli()

    @Test
    fun `calendar is one local day with HH MM times, a todo kind and an offset zone`() {
        val events = listOf(
            event("Class", at(2026, 11, 4, 9, 30), at(2026, 11, 4, 10, 45)),
            event("Quiz due", at(2026, 11, 4, 23, 59), kind = EventKind.TASK),
            event("Holiday", utcMidnight(LocalDate.of(2026, 11, 4)), allDay = true),
        )
        val card = PhoneCards.calendar(events, chicago, at(2026, 11, 4, 0, 0))
        assertEquals("2026-11-04", card.date)
        assertEquals("UTC-06:00", card.zone)
        assertFalse(card.zone.contains("/"))
        assertEquals(listOf("Holiday", "Class", "Quiz due"), card.items.map { it.title })
        assertTrue(card.items[0].allDay)
        assertNull(card.items[0].start)
        assertEquals("09:30", card.items[1].start)
        assertEquals("10:45", card.items[1].end)
        assertEquals(TimelineKind.TODO, card.items[2].kind)
        assertEquals(listOf(null, null, null), card.items.map { it.date })
        assertEquals(0, card.more)
        assertEquals("Wednesday, November 4", card.period)
    }

    @Test
    fun `a window over several days dates each item and caps at twelve`() {
        val events = (0 until 14).map { i -> event("Item $i", at(2026, 11, 2 + i / 2, 8 + i % 2, 0)) } +
            event("Holiday", utcMidnight(LocalDate.of(2026, 11, 3)), allDay = true)
        val card = PhoneCards.calendar(events, chicago, at(2026, 11, 2, 0, 0))
        assertEquals("2026-11-02", card.date)
        assertEquals(12, card.items.size)
        assertEquals(3, card.more)
        assertEquals("2026-11-02", card.items.first().date)
        assertEquals("Holiday", card.items[2].title)
        assertEquals("2026-11-03", card.items[2].date)
        assertEquals("Mon Nov 2 to Sun Nov 8", card.period)
        assertTrue(card.note!!, card.note!!.contains("3 more not shown"))
    }

    @Test
    fun `an empty window is a card that says nothing is on`() {
        val card = PhoneCards.calendar(emptyList(), chicago, at(2026, 11, 4, 0, 0))
        assertEquals("2026-11-04", card.date)
        assertTrue(card.items.isEmpty())
        assertTrue(card.note!!.contains("Nothing on the calendar"))
    }

    private val now = at(2026, 11, 4, 12, 0)

    private fun jeep(baselineAt: Long = now - 3 * 86_400_000L, trip: Double = 0.0) = Vehicle(
        obdMac = "AA:BB", name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998,
        personaPrompt = "", odometerBaseline = 200_000, odometerBaselineAt = baselineAt, tripMilesSinceBaseline = trip,
    )

    @Test
    fun `car next service is an estimate with absolute due miles and date, recalls and codes not checked`() {
        val next = VehicleController.NextService(
            byMiles = VehicleController.ServiceCandidate("Oil change", 1_200L, VehicleController.ScheduleUnit.MILES),
            byTime = VehicleController.ServiceCandidate("Oil change", 30L, VehicleController.ScheduleUnit.DAYS),
            unknownCount = 0, unknownNames = emptyList(), odometerUnset = false, allDue = false,
        )
        val card = CarCards.service("Jeep", jeep(trip = 50.4), next, chicago, now)
        assertEquals(200_050, card.mileage)
        assertEquals("2026-11-01", card.mileageAsOf)
        assertEquals(
            NextServiceDue("Oil change", dueMiles = 201_250, dueDate = "2026-12-04", estimate = true),
            card.nextService,
        )
        assertEquals(setOf(CarPart.SERVICE), card.parts.keys)
        assertTrue(card.note!!, card.note!!.contains("estimate"))
        assertTrue(card.note!!, card.note!!.contains("Mileage is estimated"))
    }

    @Test
    fun `no schedule is said in words, not shown as a due item`() {
        val card = CarCards.service("Jeep", jeep(), null, chicago, now)
        assertNull(card.nextService)
        assertTrue(card.note!!.contains("No maintenance schedule"))
    }

    @Test
    fun `recalls checked and none found is an empty list, said in words`() {
        val card = CarCards.recalls("Jeep", jeep(), emptyList(), chicago)
        assertEquals(emptyList<Recall>(), card.recalls)
        assertEquals(setOf(CarPart.RECALLS), card.parts.keys)
        assertTrue(card.note!!.contains("no recalls"))
    }

    @Test
    fun `codes carry their meaning, null when unknown`() {
        val card = CarCards.codes("Jeep", jeep(), listOf("P0420" to "Catalyst efficiency", "P1234" to null), chicago)
        assertEquals(listOf(TroubleCode("P0420", "Catalyst efficiency"), TroubleCode("P1234", null)), card.codes)
        assertEquals(setOf(CarPart.CODES), card.parts.keys)
    }

    private fun codeEvent(date: String, cleared: Boolean, vararg codes: Pair<String, String?>) =
        org.json.JSONObject().put("date", date).put("cleared", cleared).put(
            "codes",
            org.json.JSONArray(
                codes.map { (c, m) -> org.json.JSONObject().put("code", c).apply { m?.let { put("meaning", it) } } },
            ),
        )

    @Test
    fun `code history shows codes not cleared since, each once, and counts cleared events`() {
        val events = org.json.JSONArray(
            listOf(
                codeEvent("Aug 25, 2026", false, "P1282" to null, "P0700" to "Transmission control system"),
                codeEvent("Aug 20, 2026", false, "P0700" to "Transmission control system"),
                codeEvent("Jul 1, 2026", true, "P0420" to "Catalyst efficiency"),
            ),
        )
        val card = CarCards.codeHistory("1998 Jeep Cherokee", jeep(), events, chicago)
        assertEquals(
            listOf(TroubleCode("P1282", null), TroubleCode("P0700", "Transmission control system")),
            card.codes,
        )
        assertEquals(setOf(CarPart.CODES), card.parts.keys)
        val note = card.parts.getValue(CarPart.CODES)
        assertTrue(note, note.contains("newest Aug 25, 2026"))
        assertTrue(note, note.contains("1 logged event was cleared since"))
    }

    @Test
    fun `no code events is a codes part that says none are recorded`() {
        val card = CarCards.codeHistory("Jeep", jeep(), org.json.JSONArray(), chicago)
        assertTrue(card.codes.isEmpty())
        assertTrue(card.parts.getValue(CarPart.CODES).contains("No trouble-code events"))
    }

    @Test
    fun `checklist caps at eight and counts what is open`() {
        val lines = (1..10).map { ChecklistLine("item $it", done = it <= 4) }
        val card = PhoneCards.checklist("Groceries", lines)
        assertEquals(8, card.items.size)
        assertEquals(2, card.more)
        assertEquals("6 of 10 not done.", card.note)
    }
}
