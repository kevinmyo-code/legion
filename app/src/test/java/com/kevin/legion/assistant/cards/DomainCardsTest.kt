package com.kevin.legion.assistant.cards

import com.kevin.legion.claude.LaptopHealth
import com.kevin.legion.claude.LaptopLimits
import com.kevin.legion.claude.TempPoint
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryLineItemWithCurrency
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.navigation.GuidanceSnapshot
import com.kevin.legion.navigation.UnitSystem
import com.kevin.legion.service.EngineToolAnswer
import com.kevin.legion.ui.assistant.CardGeneralWords
import com.kevin.legion.ui.assistant.CardPictureWords
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The domain cards (2026-11-15): an engine card through `ask_engine` names the screen that shows
 * the same data by hand (ADR 0035), and each phone builder says its values in words from the rows
 * or result its tool answered from. Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
class DomainCardsTest {

    private val zone = ZoneId.of("America/Chicago")

    private fun words(c: ConversationCard.Readings) = c.items.map(CardGeneralWords::reading)

    private fun answer(tool: String, card: String, structured: JSONObject? = null) =
        EngineToolAnswer(
            tool = tool, isError = false, structured = structured, card = JSONObject(card), roundTripMs = 40L,
        )

    @Test
    fun `canvas grades open the Grades screen, a hidden total is words, a percent is Canvas's`() {
        val card = EngineCards.fromAnswer(
            answer(
                "canvas_grades",
                """{"card":"readings","title":"Grades","at":"2026-11-15T14:20","items":[
                {"label":"Chemistry 101","value":91.5,"unit":"%","note":"Canvas says A-."},
                {"label":"History 210","value":null,"unit":"","text":"No total shown"}],"more":0,
                "note":"Canvas's own current grades: work not yet graded does not count."}""",
            ),
        ) as ConversationCard.Readings
        assertEquals(CardDestination.GRADES, card.open)
        assertEquals(listOf("91.5%", "No total shown"), words(card))
    }

    @Test
    fun `a tool with no screen of its own leaves the card without an Open link`() {
        val card = EngineCards.fromAnswer(
            answer(
                "read_records",
                """{"card":"readings","title":"x","items":[],"more":0}""",
                JSONObject("""{"table":"places"}"""),
            ),
        ) as ConversationCard.Readings
        assertNull(card.open)
    }

    @Test
    fun `budget pace from the engine opens Money and says its status in words`() {
        val card = EngineCards.fromAnswer(
            answer(
                "budgets",
                """{"card":"status_list","title":"Budget pace","items":[
                {"name":"Groceries","status":"attention","note":"Over: 520.00 of 500.00."}],"more":0}""",
            ),
        ) as ConversationCard.StatusList
        assertEquals(CardDestination.MONEY, card.open)
        assertEquals("Needs attention", card.items.single().status.words)
    }

    @Test
    fun `stored drives from read_records open Car`() {
        val card = EngineCards.fromAnswer(
            answer(
                "read_records",
                """{"card":"readings","title":"Drives","items":[],"more":0}""",
                JSONObject("""{"table":"drives"}"""),
            ),
        ) as ConversationCard.Readings
        assertEquals(CardDestination.CAR, card.open)
    }

    @Test
    fun `laptop health is readings with memory as a percent and unread as not read`() {
        val h = LaptopHealth(
            at = Instant.parse("2026-11-15T20:20:00Z"),
            cpuC = 61.46, gpuC = null, fansRpm = mapOf("CPU" to 2400), cpuLoadPct = 12.4,
            ramUsedPct = 70.6, diskFreeGb = 180.25, onMains = true, rebootPending = true, alerts = listOf("hot"),
        )
        val card = DomainCards.laptop("Desk laptop", "busy", h, zone)!!
        assertEquals("2026-11-15T14:20", card.at)
        assertEquals(
            listOf("Busy", "61.5 °C", "not read", "12%", "71%", "180.3 GB", "2,400 rpm", "On mains"),
            words(card),
        )
        assertTrue(card.note!!, card.note!!.contains("A Windows restart is pending."))
        assertEquals(CardDestination.LAPTOP, card.open)
        assertNull(DomainCards.laptop("Desk laptop", "offline", null, zone))
        // The headline is the state; load and memory are bars on 0 to 100.
        assertEquals("Busy", CardPictureWords.headline(card)!!.value)
        assertEquals(BigDecimal(100), card.items.first { it.label == "Memory used" }.scale!!.max)
        assertNull(card.items.first { it.label == "Memory used" }.mark)
    }

    @Test
    fun `the engine's thresholds mark memory and pick out the hot reading`() {
        val h = LaptopHealth(
            at = null, cpuC = 93.0, gpuC = 70.0, ramUsedPct = 96.0, diskFreeGb = 9.0,
            alerts = listOf("hot", "ram_high", "disk_low"), limits = LaptopLimits(90.0, 95.0, 15.0),
        )
        val card = DomainCards.laptop("Desk laptop", "online", h, zone)!!
        val by = card.items.associateBy { it.label }
        assertTrue(by.getValue("CPU").attention)
        assertFalse(by.getValue("GPU").attention)
        assertEquals("Alert at 95%", by.getValue("Memory used").mark!!.label)
        assertEquals("96% (needs attention)", CardGeneralWords.reading(by.getValue("Memory used")))
        assertTrue(by.getValue("Disk C: free").attention)
        val temps = DomainCards.laptopTemps(
            "Desk laptop",
            h.copy(temps24h = listOf(TempPoint(Instant.parse("2026-11-15T10:00:00Z"), 55.0, 40.0))),
            zone,
        )!!
        assertEquals("Alert at 90 °C", temps.guide!!.label)
    }

    @Test
    fun `laptop temperatures are two lines over the day, or no card with none`() {
        val h = LaptopHealth(
            at = null,
            temps24h = listOf(
                TempPoint(Instant.parse("2026-11-15T10:00:00Z"), 55.0, 40.0),
                TempPoint(Instant.parse("2026-11-15T10:10:00Z"), 58.0, null),
            ),
        )
        val trend = DomainCards.laptopTemps("Desk laptop", h, zone)!!
        assertEquals(listOf(2, 1), trend.series.map { it.points.size })
        assertNull(DomainCards.laptopTemps("Desk laptop", LaptopHealth(at = null), zone))
    }

    @Test
    fun `the voice service state is a word, never a colour`() {
        assertEquals("Not reached", DomainCards.voiceService("unreachable").items.single().status.words)
        assertEquals("OK", DomainCards.voiceService("ready").items.single().status.words)
        assertEquals("Failed", DomainCards.voiceService("unauthorized").items.single().status.words)
    }

    @Test
    fun `a trip says arrival in 24-hour time and a value the map lacks as not read`() {
        val g = GuidanceSnapshot(
            durationLeftS = 1_380.0, distanceLeftM = null,
            arrivalAtMs = Instant.parse("2026-11-15T20:52:00Z").toEpochMilli(),
            turn = null, then = null, road = "Main St", speedLimit = null, traffic = null,
        )
        val card = DomainCards.trip("Library", 1, g, zone)
        assertEquals("To Library", card.title)
        assertEquals(listOf("14:52", "23 min", "not read", "1", "Main St"), words(card))
        assertEquals(CardDestination.NAVIGATION, card.open)
        assertEquals("14:52", CardPictureWords.headline(card)!!.value)
    }

    @Test
    fun `with the route's length the distance left is a bar on it, in the map's units`() {
        val g = GuidanceSnapshot(
            durationLeftS = 1_380.0, distanceLeftM = 8_046.72, arrivalAtMs = null,
            turn = null, then = null, road = null, speedLimit = null, traffic = null,
        )
        val card = DomainCards.trip("Library", 0, g, zone, routeDistanceM = 32_186.88, units = UnitSystem.IMPERIAL)
        val left = card.items.first { it.label == "Distance left" }
        assertEquals("5 mi", CardGeneralWords.reading(left))
        assertEquals(0, BigDecimal(20).compareTo(left.scale!!.max))
        assertTrue(card.note!!.contains("The bar is the distance left"))
        val metric = DomainCards.trip("Library", 0, g, zone, routeDistanceM = 32_186.88, units = UnitSystem.METRIC)
        assertEquals("8 km", CardGeneralWords.reading(metric.items.first { it.label == "Distance left" }))
        // A route length shorter than what is left (a stale figure) draws no bar.
        val stale = DomainCards.trip("Library", 0, g, zone, routeDistanceM = 100.0, units = UnitSystem.IMPERIAL)
        assertNull(stale.items.first { it.label == "Distance left" }.scale)
    }

    @Test
    fun `weight is the tool's own recent days, the trend line an estimate, gaps left out`() {
        val result = JSONObject(
            """{"success":true,"rate_lbs_per_week":-0.8,"recent_days":[
            {"day":"2026-11-01","weight_lbs":186.2,"trend_lbs":186.0},
            {"day":"2026-11-02","weight_lbs":null,"trend_lbs":185.8}]}""",
        )
        val card = BodyCards.weightTrend(result)!!
        assertEquals(listOf(1, 2), card.series.map { it.points.size })
        assertEquals(CardFlag.ESTIMATE, card.series[1].flag)
        assertEquals("Losing 0.8 lb a week (estimate). Days with no weigh-in are left out.", card.note)
        assertNull(card.guide)
        assertNull(BodyCards.weightTrend(JSONObject("""{"success":false}""")))
        val goal = BodyCards.weightTrend(
            JSONObject(
                """{"success":true,"milestone":{"target_lbs":175.0},"recent_days":[
                {"day":"2026-11-01","weight_lbs":186.2,"trend_lbs":186.0}]}""",
            ),
        )!!
        assertEquals("Goal 175 lb", goal.guide!!.label)
    }

    @Test
    fun `sleep is hours per logged night, an unlogged night absent rather than zero`() {
        fun day(d: Int) = LocalDate.of(2026, 11, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val card = BodyCards.sleep(listOf(day(3) to 420, day(1) to 455))!!
        assertEquals(listOf("2026-11-01", "2026-11-03"), card.series.single().points.map { it.at })
        assertEquals(
            "Asleep: latest 7 h on Nov 3, low 7 h, high 7.6 h.",
            CardGeneralWords.series(card.series.single(), card.unit),
        )
        assertNull(BodyCards.sleep(emptyList()))
        assertEquals(TrendStyle.BARS, card.style)
        assertNull(card.guide)
        assertEquals("Target 7.5 h", BodyCards.sleep(listOf(day(1) to 455), targetMinutes = 450)!!.guide!!.label)
    }

    @Test
    fun `today's workout is each exercise's target in words and what is ticked`() {
        val result = JSONObject(
            """{"success":true,"training_day":"Day A","session_status":"in_progress",
            "sessions_done_this_week":1,"sessions_planned_this_week":3,
            "exercises":[{"exercise":"Squat","target":"3 x 5 at 185 lb","ticked":"185 x 5"},
            {"exercise":"Row","target":"3 x 8","ticked":""}]}""",
        )
        val card = BodyCards.workout(result)!!
        assertEquals("Today's workout", card.title)
        assertEquals(listOf("1 of 3", "3 x 5 at 185 lb", "3 x 8"), words(card))
        assertEquals("Done: 185 x 5.", card.items[1].note)
        assertNull(card.items[2].note)
        // The week's sessions lead the card, a bar of done on planned.
        assertEquals(0, card.lead)
        assertEquals("Sessions this week", card.items[0].label)
        assertEquals(BigDecimal(3), card.items[0].scale!!.max)
        assertNull(card.note)
    }

    @Test
    fun `grocery macros are estimates in words and prices are Long cents in their own currency`() {
        val rows = listOf(
            PantryLineItemWithCurrency(
                PantryLineItem(
                    receiptId = 1, name = "Oats", totalPriceCents = 499, caloriesKcal = 1_500, proteinG = 50.0,
                ),
                LedgerCurrency.USD,
            ),
            PantryLineItemWithCurrency(
                PantryLineItem(receiptId = 2, name = "Tea", totalPriceCents = 350),
                LedgerCurrency.SGD,
            ),
        )
        val card = BodyCards.groceries(rows)!!
        assertEquals(listOf("1,500 kcal (estimate)", "No estimate"), words(card))
        assertEquals("Protein 50 g (estimates). \$4.99", card.items[0].note)
        assertEquals("SGD 3.50", card.items[1].note)
        assertEquals(CardDestination.PANTRY, card.open)
        assertNull(card.items[0].split)
    }

    @Test
    fun `all three macros make a split bar of estimates, the note keeps the price`() {
        val rows = listOf(
            PantryLineItemWithCurrency(
                PantryLineItem(
                    receiptId = 1, name = "Oats", totalPriceCents = 499, caloriesKcal = 1_500,
                    proteinG = 50.0, carbsG = 270.0, fatG = 25.0,
                ),
                LedgerCurrency.USD,
            ),
        )
        val item = BodyCards.groceries(rows)!!.items.single()
        assertEquals("Protein 50 g, Carbs 270 g, Fat 25 g (estimate)", CardPictureWords.split(item))
        assertEquals("\$4.99", item.note)
    }

    @Test
    fun `a draft shows to and subject, never a body, and hides a reply's subject`() {
        val card = DomainCards.draft("sam@example.com", "Dinner on Friday")
        assertEquals(listOf("sam@example.com", "Dinner on Friday"), words(card))
        assertTrue(card.note!!.contains("Not sent."))
        val reply = DomainCards.draft("sam@example.com", "Re: the lease")
        assertEquals(listOf("To"), reply.items.map { it.label })
        assertFalse(CardJson.write(reply).toString().contains("lease"))
    }

    @Test
    fun `memories offer Forget by id, and the offline copy is read only`() {
        val m = MemberMemory(
            id = "m-1", text = "Prefers window seats.", kind = "preference", importance = 3,
            decayClass = "durable", source = "phone", shared = false, aboutYou = true,
            createdAtMs = Instant.parse("2026-11-02T16:00:00Z").toEpochMilli(),
        )
        val card = DomainCards.memories(listOf(m), total = 25, fromCache = false)
        assertEquals(listOf("Prefers window seats."), words(card))
        assertEquals(listOf("m-1"), card.forgetIds)
        assertEquals(24, card.more)
        assertEquals(CardDestination.MEMORY, card.open)
        val offline = DomainCards.memories(listOf(m), total = 1, fromCache = true)
        assertNull(offline.forgetIds)
        assertNotNull(offline.note)
    }
}
