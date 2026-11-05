package com.kevin.legion.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.service.VoiceModalController
import com.kevin.legion.service.VoiceModalTarget
import java.time.LocalDate
import java.time.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Household-finance 13: HOME's Money panel. The [Calendar] button opens the same sheet Alfred's
 * `show_agenda_modal` opens (both are `VoiceModalController.show(AGENDA)`; the tool's side is
 * `LiveSessionController`'s `show_agenda_modal` branch), a bar opens its category, the total bar
 * opens Money, and the day's agenda under the panel is still there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp")
class HomeMoneyComposeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 11, 5)
    private var moneyOpened = 0
    private val categories = mutableListOf<String>()

    @After
    fun tearDown() = VoiceModalController.dismiss()

    private fun show() {
        val calendar = HomeCalendarUiState(
            today = today,
            month = YearMonth.of(2026, 11),
            selectedDay = today,
            cells = buildHomeCells(YearMonth.of(2026, 11), today, today, emptyMap()),
            dayRows = listOf(
                AgendaRowUi("e1", AgendaKind.EVENT, "10:00 AM", "Lecture", "Event"),
            ),
            loading = false,
        )
        rule.setContent {
            HomeContent(
                calendar = calendar,
                calendarCallbacks = HomeCalendarCallbacks(),
                nowPlaying = null,
                onOpenMedia = {},
                tileCallbacks = HomeCallbacks({}, {}, { moneyOpened++ }, {}, {}, {}, {}, {}, {}, {}, {}),
                money = FinanceFixtures.homeFresh,
                onOpenMoneyCategory = { categories += it },
            )
        }
    }

    @Test
    fun `the Calendar button opens the agenda sheet, the same one the voice tool opens`() {
        show()
        rule.onNodeWithText("Calendar").performClick()
        assertEquals(VoiceModalTarget.AGENDA, VoiceModalController.current.value?.target)
    }

    @Test
    fun `the day's agenda stays under the panel`() {
        show()
        rule.onNodeWithText("Lecture").assertExists()
        rule.onNodeWithText("Today · Thu, Nov 5").assertExists()
    }

    private fun described(prefix: String) = SemanticsMatcher(prefix) { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    @Test
    fun `tapping the total bar opens Money, tapping a category bar opens its drilldown`() {
        show()
        rule.onNode(described("November: $581 spent, $1,019 left of $1,600")).performClick()
        assertEquals(1, moneyOpened)
        rule.onNode(described("Dining,")).performClick()
        assertEquals(listOf("Dining"), categories)
    }

    @Test
    fun `the panel is the budget only, over the cap said in words, fixed last`() {
        show()
        rule.onNodeWithText("over by $14").assertExists()
        rule.onNodeWithText("Fixed").assertExists()
        rule.onNodeWithText("not paid yet").assertExists()
        rule.onNodeWithText("Safe to spend").assertDoesNotExist()
        rule.onNodeWithText("Net worth").assertDoesNotExist()
    }

    @Test
    fun `the More card shows the total bar and the three closest to their cap, and opens Money`() {
        var opened = 0
        rule.setContent {
            com.kevin.legion.ui.theme.soft.SoftTheme {
                MoneyGlanceCard(homeMoneyPanel(FinanceFixtures.homeFresh)) { opened++ }
            }
        }
        rule.onNodeWithText("$581 of $1,600", substring = true).assertExists()
        rule.onNodeWithText("Dining", substring = true).assertExists()
        rule.onNodeWithText("Household", substring = true).assertExists()
        rule.onNodeWithText("Subscriptions", substring = true).assertExists()
        rule.onNodeWithText("Shopping", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Money").performClick()
        assertEquals(1, opened)
    }
}
