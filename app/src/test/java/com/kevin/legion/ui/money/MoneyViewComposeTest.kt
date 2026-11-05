package com.kevin.legion.ui.money

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Household-finance 14's verification: pager bounds, a page change reloads, read-only accounts, the bar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp")
class MoneyViewComposeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sep = YearMonth.of(2026, 9)
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)
    private val paged = mutableListOf<YearMonth>()
    private val opened = mutableListOf<String?>()

    private val state = MoneyViewUiState(
        today = FinanceFixtures.today,
        safe = FinanceRead.Fresh(FinanceFixtures.safe),
        netWorth = FinanceRead.Fresh(FinanceFixtures.netWorth),
        accounts = FinanceRead.Fresh(FinanceFixtures.accounts),
        pages = mapOf(
            nov to MoneyMonthPage(
                FinanceRead.Fresh(FinanceFixtures.budgets),
                FinanceRead.Fresh(FinanceFixtures.cashFlow),
                emptyList(),
            ),
        ),
    )

    private fun show(picked: YearMonth) {
        rule.setContent {
            SoftTheme {
                MoneyViewContent(
                    state = state,
                    host = MoneyViewHost(months = listOf(sep, oct, nov), pickedMonth = picked),
                    callbacks = MoneyViewCallbacks(onPageMonth = { paged += it }, onOpenCategory = { opened += it }),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `opens on the picked month and never pages past the newest`() {
        show(nov)
        assertEquals(nov, paged.last())
        rule.onNodeWithContentDescription("Next month, none").assert(hasNoClickAction())
        rule.onNodeWithText("November 2026").assertExists()
    }

    @Test
    fun `never pages before the oldest month with data`() {
        show(sep)
        rule.onNodeWithContentDescription("Previous month, none").assert(hasNoClickAction())
    }

    @Test
    fun `a page change hands the new month back, so that month reloads`() {
        show(nov)
        rule.onNodeWithContentDescription("Previous month").performClick()
        rule.waitForIdle()
        assertEquals(oct, paged.last())
        rule.onNodeWithText("October 2026").assertExists()
    }

    @Test
    fun `account rows are not clickable and their text is one line`() {
        // The accounts section is hidden for now (MoneySections); this holds again when it is back.
        Assume.assumeTrue(MoneySections.SHOW_HOUSEHOLD)
        show(nov)
        val name = rule.onNodeWithText("A credit card with a very long marketing name indeed 0002")
        name.assert(hasNoClickAction())
        val layouts = mutableListOf<TextLayoutResult>()
        name.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        rule.onNodeWithText("live, as of today, -$32 pending").assert(hasNoClickAction())
    }

    @Test
    fun `tapping a budget bar opens that category`() {
        show(nov)
        rule.onNode(SemanticsMatcher("bar") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                ?.any { it.startsWith("Subscriptions, 119 / 170") } == true
        }).performScrollTo().performClick()
        assertEquals(listOf<String?>("Subscriptions"), opened)
    }

    @Test
    fun `each month is the budget first, the household sections hidden`() {
        show(nov)
        rule.onNode(SemanticsMatcher("total") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                ?.any { it.startsWith("November: $581 spent, $1,019 left of $1,600") } == true
        }).assertExists()
        rule.onNodeWithText("Transactions").performScrollTo().assertExists()
        listOf("Safe to spend", "This month", "Net worth today", "Accounts", "Statements").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
    }
}
