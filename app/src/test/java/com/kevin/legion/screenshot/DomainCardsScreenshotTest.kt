package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.BodyCards
import com.kevin.legion.assistant.cards.DomainCards
import com.kevin.legion.assistant.cards.ServerCards
import com.kevin.legion.claude.LaptopHealth
import com.kevin.legion.claude.TempPoint
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryLineItemWithCurrency
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.navigation.GuidanceSnapshot
import com.kevin.legion.ui.assistant.CARD_CONFIRM_FORGET_TAG
import com.kevin.legion.ui.assistant.CARD_FORGET_TAG
import com.kevin.legion.ui.assistant.CardForget
import com.kevin.legion.ui.assistant.CardStageSurface
import com.kevin.legion.ui.assistant.ForgetResult
import com.kevin.legion.ui.assistant.LocalCardForget
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.typeset.TypesetPage
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * Every domain card (2026-11-15) on the card stage under the shell's bar, day and night at 412x915,
 * so Kevin can look at each before anything ships. Engine cards are parsed from the engine's own
 * wire shape; phone cards come from their builders. Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DomainCardsScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("America/Chicago")

    private fun render(name: String, night: Boolean, card: ConversationCard, mustSay: String, forget: CardForget? = null) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalCardForget provides (forget ?: CardForget { ForgetResult(false, "") })) {
                LegionTheme {
                    TypesetPage {
                        Column(Modifier.fillMaxSize()) {
                            ShellStatusBar()
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                CardStageSurface(listOf(card), onClose = {}, onOpen = {}, onCloseAll = {})
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onNodeWithText(mustSay, substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("cards-$name-412x915-${if (night) "night" else "day"}.png")
    }

    private fun engine(json: String) = ServerCards.parse(JSONObject(json))!!

    private val grades get() = engine(
        """{"card":"readings","title":"Grades","at":"2026-11-15T14:20","items":[
        {"label":"Chemistry 101","value":91.5,"unit":"%","note":"Canvas says A-."},
        {"label":"History 210","value":null,"unit":"","text":"No total shown"},
        {"label":"Calculus II","value":84.25,"unit":"%","note":"Canvas says B."}],"more":0,
        "note":"Canvas's own current grades: work not yet graded does not count."}""",
    )

    private val pace get() = engine(
        """{"card":"status_list","title":"Budget pace","period":"November 2026, day 15 of 30","items":[
        {"name":"Groceries","status":"attention","note":"Over: ${'$'}520.00 of ${'$'}500.00. About ${'$'}1,040.00 by month end (estimate). Includes unverified rows."},
        {"name":"Fuel","status":"attention","note":"On pace to go over: ${'$'}60.00 of ${'$'}100.00. About ${'$'}120.00 by month end (estimate)."},
        {"name":"Dining","status":"ok","note":"On track: ${'$'}90.00 of ${'$'}200.00. About ${'$'}180.00 by month end (estimate)."}],
        "more":0,"note":"Altogether ${'$'}670.00 of ${'$'}800.00."}""",
    )

    private val alerts get() = engine(
        """{"card":"status_list","title":"Weather alerts","scope":"Home","items":[
        {"name":"Flood Watch","status":"attention","note":"Moderate, until Nov 15 6:00 PM, UTC-6, from NWS Sample Office."},
        {"name":"Wind Advisory","status":"attention","note":"Minor, until Nov 15 9:00 PM, UTC-6, from NWS Sample Office."}],
        "more":0,"note":"Read just now from the National Weather Service."}""",
    )

    private val drives get() = engine(
        """{"card":"readings","title":"Drives","items":[
        {"label":"Sat Nov 14, 07:05","value":12.3,"unit":"mi","note":"32 min."},
        {"label":"Fri Nov 13, 16:00","value":null,"unit":"mi","note":"End time not stored."}],"more":0,
        "note":"Miles as the phone logged them; no fuel economy."}""",
    )

    private val health = LaptopHealth(
        at = Instant.parse("2026-11-15T20:20:00Z"),
        cpuC = 61.5, gpuC = 44.0, fansRpm = mapOf("CPU" to 2400, "GPU" to 2100), cpuLoadPct = 12.0,
        ramUsedPct = 71.0, diskFreeGb = 180.3, onMains = true, alerts = listOf("ram_high"),
        temps24h = (0 until 24).map { h ->
            TempPoint(Instant.parse("2026-11-14T21:00:00Z").plusSeconds(h * 3_600L), 50.0 + (h % 6) * 3, 38.0 + (h % 4) * 2)
        },
    )

    private val laptop get() = DomainCards.laptop("Desk laptop", "busy", health, zone)!!
    private val temps get() = DomainCards.laptopTemps("Desk laptop", health, zone)!!

    private val trip get() = DomainCards.trip(
        "Library", 1,
        GuidanceSnapshot(1_380.0, 9_500.0, Instant.parse("2026-11-15T20:52:00Z").toEpochMilli(), null, null, "Main St", null, null),
        zone,
    )

    private val weight get() = BodyCards.weightTrend(
        JSONObject(
            """{"success":true,"rate_lbs_per_week":-0.8,"recent_days":[
            {"day":"2026-11-01","weight_lbs":186.2,"trend_lbs":186.0},{"day":"2026-11-03","weight_lbs":185.1,"trend_lbs":185.6},
            {"day":"2026-11-05","weight_lbs":185.6,"trend_lbs":185.3},{"day":"2026-11-08","weight_lbs":184.0,"trend_lbs":184.8},
            {"day":"2026-11-10","weight_lbs":null,"trend_lbs":184.5},{"day":"2026-11-12","weight_lbs":183.6,"trend_lbs":184.1},
            {"day":"2026-11-14","weight_lbs":183.9,"trend_lbs":183.9}]}""",
        ),
    )!!

    private val sleep get() = BodyCards.sleep(
        listOf(1 to 455, 2 to 410, 3 to 480, 5 to 390, 6 to 445, 7 to 470).map { (d, min) ->
            java.time.LocalDate.of(2026, 11, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() to min
        },
    )!!

    private val workout get() = BodyCards.workout(
        JSONObject(
            """{"success":true,"training_day":"Day A","session_status":"in_progress",
            "sessions_done_this_week":1,"sessions_planned_this_week":3,"exercises":[
            {"exercise":"Squat","target":"3 x 5 at 185 lb","ticked":"185 x 5, 185 x 5"},
            {"exercise":"Bench press","target":"3 x 5 at 135 lb","ticked":""},
            {"exercise":"Row","target":"3 x 8 at 95 lb","ticked":""}]}""",
        ),
    )!!

    private val groceries get() = BodyCards.groceries(
        listOf(
            PantryLineItemWithCurrency(
                PantryLineItem(receiptId = 1, name = "Rolled oats", totalPriceCents = 499, caloriesKcal = 1_500, proteinG = 50.0, carbsG = 270.0, fatG = 25.0),
                LedgerCurrency.USD,
            ),
            PantryLineItemWithCurrency(
                PantryLineItem(receiptId = 1, name = "Greek yogurt", totalPriceCents = 629, caloriesKcal = 520, proteinG = 90.0),
                LedgerCurrency.USD,
            ),
            PantryLineItemWithCurrency(PantryLineItem(receiptId = 1, name = "Paper towels", totalPriceCents = 899), LedgerCurrency.USD),
        ),
    )!!

    private val draft get() = DomainCards.draft("sam@example.com", "Dinner on Friday")

    private val memory = MemberMemory(
        id = "m-1", text = "Prefers a window seat on long flights.", kind = "preference", importance = 3,
        decayClass = "durable", source = "phone", shared = false, aboutYou = true,
        createdAtMs = Instant.parse("2026-11-02T16:00:00Z").toEpochMilli(),
    )
    private val memories get() = DomainCards.memories(
        listOf(memory, memory.copy(id = "m-2", text = "Keeps the spare key with a neighbour.", shared = true)),
        total = 2,
        fromCache = false,
    )

    private val voice get() = DomainCards.voiceService("busy")

    @Test @Config(qualifiers = A25) fun `grades day`() = render("grades", false, grades, "No total shown")
    @Test @Config(qualifiers = NIGHT) fun `grades night`() = render("grades", true, grades, "91.5%")
    @Test @Config(qualifiers = A25) fun `pace day`() = render("budget-pace", false, pace, "Altogether")
    @Test @Config(qualifiers = NIGHT) fun `pace night`() = render("budget-pace", true, pace, "On track")
    @Test @Config(qualifiers = A25) fun `alerts day`() = render("weather-alerts", false, alerts, "Flood Watch")
    @Test @Config(qualifiers = NIGHT) fun `alerts night`() = render("weather-alerts", true, alerts, "Flood Watch")
    @Test @Config(qualifiers = A25) fun `drives day`() = render("drives", false, drives, "12.3 mi")
    @Test @Config(qualifiers = NIGHT) fun `drives night`() = render("drives", true, drives, "not read")
    @Test @Config(qualifiers = A25) fun `laptop day`() = render("laptop", false, laptop, "71%")
    @Test @Config(qualifiers = NIGHT) fun `laptop night`() = render("laptop", true, laptop, "61.5 °C")
    @Test @Config(qualifiers = A25) fun `laptop temps day`() = render("laptop-temps", false, temps, "CPU: latest")
    @Test @Config(qualifiers = NIGHT) fun `laptop temps night`() = render("laptop-temps", true, temps, "GPU: latest")
    @Test @Config(qualifiers = A25) fun `voice day`() = render("voice-service", false, voice, "Needs attention")
    @Test @Config(qualifiers = NIGHT) fun `voice night`() = render("voice-service", true, voice, "Needs attention")
    @Test @Config(qualifiers = A25) fun `trip day`() = render("trip", false, trip, "14:52")
    @Test @Config(qualifiers = NIGHT) fun `trip night`() = render("trip", true, trip, "23 min")
    @Test @Config(qualifiers = A25) fun `weight day`() = render("weight", false, weight, "Trend (estimate)")
    @Test @Config(qualifiers = NIGHT) fun `weight night`() = render("weight", true, weight, "Weighed: latest")
    @Test @Config(qualifiers = A25) fun `sleep day`() = render("sleep", false, sleep, "Asleep: latest")
    @Test @Config(qualifiers = NIGHT) fun `sleep night`() = render("sleep", true, sleep, "Asleep: latest")
    @Test @Config(qualifiers = A25) fun `workout day`() = render("workout", false, workout, "Done: 185 x 5")
    @Test @Config(qualifiers = NIGHT) fun `workout night`() = render("workout", true, workout, "3 x 8 at 95 lb")
    @Test @Config(qualifiers = A25) fun `groceries day`() = render("groceries", false, groceries, "1,500 kcal (estimate)")
    @Test @Config(qualifiers = NIGHT) fun `groceries night`() = render("groceries", true, groceries, "No estimate")
    @Test @Config(qualifiers = A25) fun `draft day`() = render("email-draft", false, draft, "Not sent.")
    @Test @Config(qualifiers = NIGHT) fun `draft night`() = render("email-draft", true, draft, "sam@example.com")
    @Test @Config(qualifiers = A25) fun `memories day`() = render("memories", false, memories, "Prefers a window seat")
    @Test @Config(qualifiers = NIGHT) fun `memories night`() = render("memories", true, memories, "Keeps the spare key")

    @Test @Config(qualifiers = A25)
    fun `forget asks first, deletes by id, and says deleted only when the engine confirmed`() {
        val asked = mutableListOf<String>()
        val forget = CardForget { id ->
            asked += id
            if (id == "m-1") ForgetResult(true, "Deleted for good.") else ForgetResult(false, "The engine's reply could not be read.")
        }
        render("memories-forgotten", false, memories, "Prefers a window seat", forget)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("$CARD_FORGET_TAG-0").performClick()
        rule.onNodeWithText("Forget this for good?").assertIsDisplayed()
        rule.onNodeWithTag(CARD_CONFIRM_FORGET_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Deleted for good.").assertIsDisplayed()
        rule.onNodeWithTag("$CARD_FORGET_TAG-1").performClick()
        rule.onNodeWithTag(CARD_CONFIRM_FORGET_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("The engine's reply could not be read.").assertIsDisplayed()
        rule.onNodeWithTag("$CARD_FORGET_TAG-1").assertIsDisplayed()
        assertEquals(listOf("m-1", "m-2"), asked)
        rule.onRoot().captureRoboImage("cards-memories-after-forget-412x915-day.png")
    }

    private companion object {
        const val FRAME_MS = 800L
        const val A25 = "w412dp-h915dp"
        const val NIGHT = "w412dp-h915dp-night"
    }
}
