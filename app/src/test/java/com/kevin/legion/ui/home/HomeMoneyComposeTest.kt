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
 * `LiveSessionController`'s `show_agenda_modal` branch), a bar opens its category, safe to spend
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

    @Test
    fun `tapping safe to spend opens Money, tapping a bar opens its category`() {
        show()
        rule.onNodeWithText("-$1,657").performClick()
        assertEquals(1, moneyOpened)
        rule.onNode(SemanticsMatcher("Dining bar") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Dining,") } == true
        }).performClick()
        assertEquals(listOf("Dining"), categories)
    }

    @Test
    fun `over the cap is said in words on the panel`() {
        show()
        rule.onNodeWithText("over by $14").assertExists()
        rule.onNodeWithText("Top up ~$2,000 by Nov 11: below zero.").assertExists()
    }
}
