package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountMonthResult
import com.kevin.legion.ledger.AccountMonthSpend
import com.kevin.legion.ledger.CategorySpend
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ui.money.MoneyDrilldownRow
import com.kevin.legion.ui.money.MoneyDrilldownUiState
import com.kevin.legion.ui.money.MoneyMonthContent
import com.kevin.legion.ui.money.MoneyMonthUiState
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The new Money page at the A25's 384dp width: a normal month with two accounts, an account with
 * unverified rows, a couldn't-read account, an empty month and a category drilldown. Stateless
 * [MoneyMonthContent] with invented fixtures - same runner/capture shape as the other screenshot tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class MoneyMonthScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val month = YearMonth.of(2026, 10)

    private fun cat(name: String?, cents: Long, unverified: Long = 0L) =
        CategorySpend(name, cents, unverified, hasPendingGuess = false)

    private fun account(
        name: String,
        categories: List<CategorySpend>,
        uncategorized: CategorySpend? = null,
        disclosures: List<String> = emptyList(),
    ) = AccountMonthSpend(
        name = name,
        accountIds = setOf(name),
        entity = LedgerEntity.US,
        month = month,
        totalCents = categories.sumOf { it.cents },
        unverifiedTotalCents = categories.sumOf { it.unverifiedCents },
        categories = categories,
        uncategorized = uncategorized,
        disclosures = disclosures,
    )

    private val checking = account(
        "BofA checking",
        listOf(cat("Housing", 180_000), cat("Groceries", 21_437), cat("Utilities", 9_850), cat("Dining", 4_620)),
        disclosures = listOf(
            "Includes a Housing charge of USD 1,800.00 dated 2026-09-30, counted in October.",
            "2 transactions in Transfers (USD 450.00) excluded from spend.",
        ),
    )
    private val card = account(
        "BofA card",
        listOf(cat("Dining", 14_260), cat("Shopping", 8_999), cat("Fuel", 4_512)),
        uncategorized = cat(null, 2_310),
        disclosures = listOf(
            "USD 23.10 is not assigned to a category and is NOT counted in spend. Categorise it to have it counted.",
        ),
    )

    private val today = LocalDate.of(2026, 10, 2)

    // Defaults: card data through Oct 2, checking through Oct 1 (accounts differ, so per-account lines show).
    private fun state(
        vararg accounts: AccountMonthResult,
        newestOverall: LocalDate? = today,
        newestByAccount: Map<String, LocalDate> = mapOf(
            "BofA card" to today, "BofA checking" to today.minusDays(1),
        ),
        loading: Boolean = false,
        failed: Boolean = false,
    ) = MoneyMonthUiState(
        monthTitle = "October so far", month = month, today = today,
        newestOverall = newestOverall, newestByAccount = newestByAccount,
        loading = loading, readFailed = failed, accounts = accounts.toList(),
    )

    @Test
    fun `normal month two accounts`() = capture(
        "money-month-two-accounts.png",
        state(AccountMonthResult.Spend(card), AccountMonthResult.Spend(checking)),
    )

    @Test
    fun `account with unverified rows`() = capture(
        "money-month-unverified.png",
        state(
            AccountMonthResult.Spend(
                account(
                    "BofA card",
                    listOf(cat("Dining", 14_260, unverified = 6_200), cat("Fuel", 4_512)),
                ),
            ),
            // Every spend row of this account is from the current period.
            AccountMonthResult.Spend(
                account("BofA checking", listOf(cat("Groceries", 21_437, unverified = 21_437))),
            ),
        ),
    )

    @Test
    fun `account that could not be read`() = capture(
        "money-month-unreadable.png",
        state(AccountMonthResult.Spend(card), AccountMonthResult.Unreadable("BofA checking")),
    )

    @Test
    fun `empty month`() = capture("money-month-empty.png", state(newestOverall = null, newestByAccount = emptyMap()))

    // The phone's real state on Oct 2: newest row Sep 26 on both accounts, nothing this month.
    @Test
    fun `stale data`() {
        val sep26 = LocalDate.of(2026, 9, 26)
        capture(
            "money-month-stale.png",
            state(newestOverall = sep26, newestByAccount = mapOf("BofA card" to sep26, "BofA checking" to sep26)),
        )
    }

    @Test
    fun `category drilldown`() = capture(
        "money-month-drilldown.png",
        state(AccountMonthResult.Spend(card)).copy(
            drilldown = MoneyDrilldownUiState(
                accountName = "BofA card",
                category = "Dining",
                currency = LedgerCurrency.USD,
                loading = false,
                rows = listOf(
                    MoneyDrilldownRow("2026-10-02", "CHIPOTLE 1234 HOUSTON TX", 1_845, unverified = false),
                    MoneyDrilldownRow("2026-10-01", "BLUE BOTTLE COFFEE", 650, unverified = true),
                    MoneyDrilldownRow("2026-10-01", "TACO CABANA", 1_120, unverified = false),
                ),
            ),
        ),
    )

    private fun capture(fileName: String, state: MoneyMonthUiState) {
        composeTestRule.setContent {
            MoneyMonthContent(state, onOpenCategory = { _, _ -> }, onCloseDrilldown = {}, onOpenDetails = {})
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}
