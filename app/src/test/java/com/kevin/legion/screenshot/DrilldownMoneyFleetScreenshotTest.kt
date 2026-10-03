package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ui.LedgerContent
import com.kevin.legion.ui.LedgerUiState
import com.kevin.legion.ui.PantryContent
import com.kevin.legion.ui.PantryUiState
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Device-walk fixes (2026-10-03): the Fleet, Money details and Pantry pages need the same back-arrow
 * header, 16dp side padding and sentence-case stamps as the other soft drill-downs, and the Pantry
 * spend chart must not paint a white block. Fixed epochs, so the goldens do not drift daily.
 * Fleet has no golden here: FleetContent mounts the Room-backed goals panel, whose connection leaks
 * into later Robolectric tests and fails them (seen: AppFoldersScreenshotTest). It was rendered and
 * read once by hand during the walk fix instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class DrilldownMoneyFleetScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `money details loaded with balances`() {
        composeTestRule.setContent {
            SoftTheme {
                LedgerContent(
                    LedgerUiState(
                        loading = false,
                        balances = listOf(
                            AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
                            AccountBalance(accountId = "7823", currency = LedgerCurrency.USD, balanceCents = null),
                        ),
                    ),
                    onOpenGroceries = {}, onNominateAccount = {}, onPrevPnlMonth = {}, onNextPnlMonth = {},
                    onOpenCategorize = {}, onOpenQuarantine = {}, onOpenBudget = {}, onOpenBalances = {}, onOpenTrend = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("drill-money-details.png")
    }

    @Test
    fun `pantry with an unverified receipt and the spend chart`() {
        val receipt = PantryReceipt(
            id = 1, store = "Walmart", purchaseDate = now, currency = LedgerCurrency.USD,
            totalCents = 2443, sourceImagePath = "", unaccountedCents = 151,
        )
        val items = listOf(PantryLineItem(receiptId = 1, name = "MILK", totalPriceCents = 649))
        composeTestRule.setContent {
            SoftTheme {
                PantryContent(
                    PantryUiState(
                        loading = false,
                        receipts = listOf(receipt to items),
                        currencyTotals = listOf(PantryCurrencyTotal(LedgerCurrency.USD, 17_670, hasUnreconciled = true)),
                        allReceiptSummaries = listOf(
                            PantryReceiptSummary(now - 60 * day, 5_000, LedgerCurrency.USD),
                            PantryReceiptSummary(now - 30 * day, 10_227, LedgerCurrency.USD),
                            PantryReceiptSummary(now, 2_443, LedgerCurrency.USD),
                        ),
                    ),
                    onOpenImport = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("drill-pantry.png")
    }
}
