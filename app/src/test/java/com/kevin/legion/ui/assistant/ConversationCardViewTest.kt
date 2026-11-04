package com.kevin.legion.ui.assistant

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Each card kind renders its text, collapsed and expanded, with status in words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationCardViewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(card: ConversationCard, onDismiss: () -> Unit = {}) {
        rule.setContent { SoftTheme { ConversationCardView(card, onDismiss) } }
    }

    private fun expand() = rule.onNodeWithTag(CONVERSATION_CARD_TAG).performClick()

    @Test
    fun `bars show money from cents and flags in words`() {
        show(
            ConversationCard.Bars(
                "Spending, $1,004.99 in all", "usd",
                listOf(BarPoint("Gas", 99_999L, CardFlag.UNVERIFIED), BarPoint("Food", 500L, CardFlag.ESTIMATE)),
                period = "November 2026", scope = "US", note = "USD 42.00 is not assigned to a category.",
            ),
        )
        rule.onNodeWithText("Spending, $1,004.99 in all").assertExists()
        rule.onNodeWithText("November 2026, US").assertExists()
        rule.onNodeWithText("Gas $999.99 (unverified), and 1 more").assertExists()
        expand()
        rule.onNodeWithText("$999.99 (unverified)").assertExists()
        rule.onNodeWithText("$5.00 (estimate)").assertExists()
        rule.onNodeWithText("USD 42.00 is not assigned to a category.").assertExists()
        rule.onNodeWithText("Show less").assertExists()
    }

    @Test
    fun `timeline shows times, all day and to-do in words`() {
        show(
            ConversationCard.Timeline(
                "Calendar", "2026-11-04", "UTC-06:00",
                listOf(
                    TimelineItem("09:30", "10:45", false, "Class", TimelineKind.EVENT),
                    TimelineItem(null, null, true, "Holiday", TimelineKind.EVENT),
                    TimelineItem("23:59", null, false, "Quiz due", TimelineKind.TODO),
                ),
                more = 2, period = "Wednesday, November 4",
            ),
        )
        rule.onNodeWithText("09:30 to 10:45: Class, and 4 more").assertExists()
        expand()
        rule.onNodeWithText("All day: Holiday").assertExists()
        rule.onNodeWithText("23:59: Quiz due (to-do)").assertExists()
        rule.onNodeWithText("And 2 more.").assertExists()
    }

    @Test
    fun `a multi-day timeline groups its items under day headings`() {
        show(
            ConversationCard.Timeline(
                "Calendar", "2026-11-02", "UTC-06:00",
                listOf(
                    TimelineItem("09:30", null, false, "Class", TimelineKind.EVENT, date = "2026-11-02"),
                    TimelineItem(null, null, true, "Election Day", TimelineKind.EVENT, date = "2026-11-03"),
                ),
                period = "Mon Nov 2 to Sun Nov 8",
            ),
        )
        expand()
        rule.onNodeWithText("Mon Nov 2").assertExists()
        rule.onNodeWithText("Tue Nov 3").assertExists()
        rule.onNodeWithText("All day: Election Day").assertExists()
    }

    @Test
    fun `car says what was not checked, and the service is an estimate`() {
        show(
            ConversationCard.Car(
                "Jeep", "Jeep", 200_050, "2026-11-01",
                NextServiceDue("Oil change", dueMiles = 201_250, dueDate = "2026-12-04"),
                recalls = listOf(Recall("Airbag inflator may rupture")), codes = emptyList(),
                parts = mapOf(CarPart.SERVICE to "", CarPart.RECALLS to ""),
            ),
        )
        rule.onNodeWithText("Recalls (1): Airbag inflator may rupture").assertExists()
        expand()
        rule.onNodeWithText("Mileage: 200,050 mi (last reading 2026-11-01)").assertExists()
        rule.onNodeWithText("Next service: Oil change at 201,250 mi or by 2026-12-04 (estimate)").assertExists()
        rule.onNodeWithText("Trouble codes: not checked").assertExists()
    }

    @Test
    fun `car codes with an unknown meaning say so`() {
        show(
            ConversationCard.Car(
                "Jeep", "Jeep", null, null, null, emptyList(), listOf(TroubleCode("P1234", null)),
                parts = mapOf(CarPart.CODES to ""),
            ),
        )
        rule.onNodeWithText("Trouble codes: P1234, meaning unknown").assertExists()
    }

    @Test
    fun `steps say each status in words and when a time was not reported`() {
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
        rule.onNodeWithText("1 of 3 steps done, 1 failed").assertExists()
        expand()
        rule.onNodeWithText("Done: Service (next_service), time not reported").assertExists()
        rule.onNodeWithText("Failed: Recalls (vehicle_recalls), 3.0 s").assertExists()
        rule.onNodeWithText("Skipped: Say").assertExists()
        rule.onNodeWithText("Total: 3.5 s").assertExists()
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
        rule.onNodeWithText("1 of 2 not done.").assertExists()
        expand()
        rule.onNodeWithText("Done: Milk").assertExists()
        rule.onNodeWithText("To do: Eggs").assertExists()
    }

    @Test
    fun `close dismisses`() {
        var dismissed = 0
        show(ConversationCard.Checklist("Groceries", "Groceries", emptyList(), note = "Nothing on this list.")) {
            dismissed++
        }
        rule.onNodeWithContentDescription("Close card").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `the strip draws the card above the talk pill when one is given`() {
        rule.setContent {
            SoftTheme {
                AssistantStripContent(
                    AssistantStripResolver.resolve(
                        Phase.LISTENING, "go ahead", null, micGranted = true, silenced = false,
                    ),
                    onTap = {},
                    card = ConversationCard.Checklist(
                        "Packing", "Packing", emptyList(), note = "Nothing on this list.",
                    ),
                )
            }
        }
        rule.onNodeWithText("Packing").assertExists()
        rule.onNodeWithText("Nothing on this list.").assertExists()
    }
}
