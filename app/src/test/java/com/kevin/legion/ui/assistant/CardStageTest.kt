package com.kevin.legion.ui.assistant

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.CarPart
import com.kevin.legion.assistant.cards.CardFlag
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.NextServiceDue
import com.kevin.legion.assistant.cards.Recall
import com.kevin.legion.assistant.cards.StepItem
import com.kevin.legion.assistant.cards.StepStatus
import com.kevin.legion.assistant.cards.TimelineItem
import com.kevin.legion.assistant.cards.TimelineKind
import com.kevin.legion.assistant.cards.TroubleCode
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The card stage (Kevin, 2026-11-10: cards fill the screen, as on the Mid-Continent phone): every
 * card is drawn whole with no tap to expand, status in words, one Close per card, an Open link where
 * a screen sits behind it, and a stack of several pages newest first with "1 of 3" in words.
 * Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardStageTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        vararg cards: ConversationCard,
        onClose: (ConversationCard) -> Unit = {},
        onOpen: ((CardDestination) -> Unit)? = {},
        onCloseAll: (() -> Unit)? = null,
    ) {
        rule.setContent {
            SoftTheme { CardStage(cards.toList(), onClose, onOpen = onOpen, onCloseAll = onCloseAll) }
        }
    }

    @Test
    fun `bars are drawn whole, money from cents, flags in words, the note under them`() {
        show(
            ConversationCard.Bars(
                "Spending, \$1,004.99 in all", "usd",
                listOf(BarPoint("Gas", 99_999L, CardFlag.UNVERIFIED), BarPoint("Food", 500L, CardFlag.ESTIMATE)),
                period = "November 2026", scope = "US", note = "USD 42.00 is not assigned to a category.",
            ),
        )
        rule.onNodeWithText("Spending, \$1,004.99 in all").assertExists()
        rule.onNodeWithText("November 2026, US").assertExists()
        rule.onNodeWithText("\$999.99 (unverified)").assertExists()
        rule.onNodeWithText("\$5.00 (estimate)").assertExists()
        rule.onNodeWithText("USD 42.00 is not assigned to a category.").assertExists()
        rule.onNodeWithText("Tap to show all").assertDoesNotExist()
    }

    @Test
    fun `a timeline groups days under headings, times in their own column, kinds in words`() {
        show(
            ConversationCard.Timeline(
                "Calendar", "2026-11-02", "UTC-06:00",
                listOf(
                    TimelineItem("09:30", "10:45", false, "Class", TimelineKind.EVENT, date = "2026-11-02"),
                    TimelineItem(null, null, true, "Election Day", TimelineKind.EVENT, date = "2026-11-03"),
                    TimelineItem("23:59", null, false, "Quiz due", TimelineKind.TODO, date = "2026-11-03"),
                ),
                more = 2, period = "Mon Nov 2 to Sun Nov 8",
            ),
        )
        rule.onNodeWithText("MON NOV 2").assertExists()
        rule.onNodeWithText("TUE NOV 3").assertExists()
        rule.onNodeWithText("09:30 to 10:45").assertExists()
        rule.onNodeWithText("Class").assertExists()
        rule.onNodeWithText("All day").assertExists()
        rule.onNodeWithText("Quiz due (to-do)").assertExists()
        rule.onNodeWithText("And 2 more.").assertExists()
    }

    @Test
    fun `car says what was not checked, and the service is an estimate`() {
        show(
            ConversationCard.Car(
                "Test car", "Test car", 200_050, "2026-11-01",
                NextServiceDue("Oil change", dueMiles = 201_250, dueDate = "2026-12-04"),
                recalls = listOf(Recall("Airbag inflator may rupture")), codes = emptyList(),
                parts = mapOf(CarPart.SERVICE to "", CarPart.RECALLS to ""),
            ),
        )
        rule.onNodeWithText("Mileage: 200,050 mi (last reading 2026-11-01)").assertExists()
        rule.onNodeWithText("Next service: Oil change at 201,250 mi or by 2026-12-04 (estimate)").assertExists()
        rule.onNodeWithText("Recalls (1): Airbag inflator may rupture").assertExists()
        rule.onNodeWithText("Trouble codes: not checked").assertExists()
    }

    @Test
    fun `car codes with an unknown meaning say so`() {
        show(
            ConversationCard.Car(
                "Test car", "Test car", null, null, null, emptyList(), listOf(TroubleCode("P1234", null)),
                parts = mapOf(CarPart.CODES to ""),
            ),
        )
        rule.onNodeWithText("Trouble codes: P1234, meaning unknown").assertExists()
    }

    @Test
    fun `steps say each status in words and when a time was not reported, with no Open link`() {
        show(
            ConversationCard.Steps(
                "Ran car check", "car_check",
                listOf(
                    StepItem("Service (next_service)", StepStatus.OK, null),
                    StepItem("Recalls (vehicle_recalls)", StepStatus.FAILED, 3_000L),
                    StepItem("Say", StepStatus.SKIPPED, null),
                ),
                totalMs = 3_500L,
            ),
        )
        rule.onNodeWithText("Done: Service (next_service), time not reported").assertExists()
        rule.onNodeWithText("Failed: Recalls (vehicle_recalls), 3.0 s").assertExists()
        rule.onNodeWithText("Skipped: Say").assertExists()
        rule.onNodeWithText("Total: 3.5 s").assertExists()
        rule.onNodeWithText("OPEN", substring = true).assertDoesNotExist()
    }

    @Test
    fun `checklist says done and to do in words`() {
        show(
            ConversationCard.Checklist(
                "Groceries", "Groceries",
                listOf(ChecklistLine("Milk", true), ChecklistLine("Eggs", false)),
                note = "1 of 2 not done.",
            ),
        )
        rule.onNodeWithText("Done: Milk").assertExists()
        rule.onNodeWithText("To do: Eggs").assertExists()
        rule.onNodeWithText("1 of 2 not done.").assertExists()
    }

    @Test
    fun `close hands back that card`() {
        val card = ConversationCard.Checklist("Groceries", "Groceries", emptyList(), note = "Nothing on this list.")
        val closed = mutableListOf<ConversationCard>()
        show(card, onClose = { closed += it })
        rule.onNodeWithContentDescription("Close card").performClick()
        assertEquals(1, closed.size)
        assertTrue(closed.single() === card)
    }

    @Test
    fun `the Open link names the screen behind and hands it back`() {
        val opened = mutableListOf<CardDestination>()
        show(ConversationCard.Bars("Spending", "usd", listOf(BarPoint("Gas", 100L))), onOpen = { opened += it })
        rule.onNodeWithText("OPEN MONEY").performClick()
        assertEquals(listOf(CardDestination.MONEY), opened)
    }

    @Test
    fun `no Open link where the surface offers none`() {
        show(ConversationCard.Bars("Spending", "usd", listOf(BarPoint("Gas", 100L))), onOpen = null)
        rule.onNodeWithText("OPEN MONEY").assertDoesNotExist()
    }

    @Test
    fun `three cards page newest first, with where you are in words`() {
        val a = ConversationCard.Checklist("First asked", "First asked", emptyList(), note = "a")
        val b = ConversationCard.Checklist("Second asked", "Second asked", emptyList(), note = "b")
        val c = ConversationCard.Checklist("Third asked", "Third asked", emptyList(), note = "c")
        var closedAll = 0
        show(a, b, c, onCloseAll = { closedAll++ })
        rule.onNodeWithText("1 of 3").assertExists()
        rule.onNodeWithText("Third asked").assertIsDisplayed()
        rule.onNodeWithText("Newer").assertDoesNotExist()
        rule.onNodeWithText("Older").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 of 3").assertExists()
        rule.onNodeWithText("Second asked").assertIsDisplayed()
        rule.onNodeWithText("Newer").assertExists()
        rule.onNodeWithText("Close all").performClick()
        assertEquals(1, closedAll)
    }

    @Test
    fun `one card has no page line and no close all`() {
        show(ConversationCard.Checklist("Only", "Only", emptyList(), note = "x"), onCloseAll = {})
        rule.onNodeWithText("1 of 1").assertDoesNotExist()
        rule.onNodeWithText("Close all").assertDoesNotExist()
    }
}
