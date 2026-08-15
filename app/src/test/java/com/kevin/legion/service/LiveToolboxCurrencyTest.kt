package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 2026-08-07 currency audit: pins that every money-carrying tool response actually carries a
 * `currency` key, closing the traced bug where `get_balance` emitted a bare `balance` figure with
 * no currency at all and the English-butler persona filled the silence with pounds sterling
 * (CLAUDE.md §4). Same Robolectric-plus-Room shape as [LiveToolboxVehicleScopingTest].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxCurrencyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seedTransaction(accountId: String, currency: LedgerCurrency, balanceCents: Long?) {
        CarDatabase.getDatabase(context).ledgerTransactionDao().insertAll(
            listOf(
                LedgerTransaction(
                    sourceFile = "eStmt.pdf",
                    accountId = accountId,
                    currency = currency,
                    txnDate = System.currentTimeMillis(),
                    description = "TEST MERCHANT",
                    amountCents = -1_000L,
                    balanceCents = balanceCents,
                    lineRef = "1",
                    ingestMethod = IngestMethod.DETERMINISTIC,
                ),
            ),
        )
    }

    @Test
    fun `get_balance tags every account with its own currency`() = runBlocking {
        seedTransaction("SGD-ACCOUNT", LedgerCurrency.SGD, 100_000L)
        seedTransaction("USD-ACCOUNT", LedgerCurrency.USD, 50_000L)

        val result = LiveToolbox.dispatch(context, "get_balance", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        val balances = result.getJSONObject("balances")
        assertEquals("SGD", balances.getJSONObject("SGD-ACCOUNT").getString("currency"))
        assertEquals("USD", balances.getJSONObject("USD-ACCOUNT").getString("currency"))
    }

    @Test
    fun `set_budget states the amount with its currency code, never a bare number`() = runBlocking {
        // A freshly built (not migrated) test Room database is created directly at the CURRENT
        // schema version. It never runs MIGRATION_5_6's seed INSERTs, but CarDatabase.getDatabase's
        // RoomDatabase.Callback.onCreate seeds CategorySeed.starter (including "Groceries") on
        // first creation - the fresh-install seeding fix, Kevin 2026-08-07 (CLAUDE.md §2
        // clone-and-run). No manual seed INSERT needed here anymore; touching the database once is
        // enough to trigger onCreate before this test's own dispatch call needs "Groceries" to exist.
        CarDatabase.getDatabase(context).openHelper.writableDatabase

        val result = LiveToolbox.dispatch(
            context, "set_budget",
            JSONObject().put("category", "Groceries").put("amount", 500.0),
        )!!

        assertTrue(result.getBoolean("success"))
        // The ledger's US entity budgets in USD (LedgerEntity.US.currency) - the reply must say
        // so explicitly rather than leaving the model to guess a currency from its own persona.
        assertTrue(result.getString("message").contains("USD"))
    }

    @Test
    fun `list_recent_transactions tags every row with its own currency`() = runBlocking {
        seedTransaction("SGD-ACCOUNT", LedgerCurrency.SGD, 100_000L)

        val result = LiveToolbox.dispatch(context, "list_recent_transactions", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        val txns = result.getJSONArray("transactions")
        assertEquals(1, txns.length())
        assertEquals("SGD", txns.getJSONObject(0).getString("currency"))
    }

    /** [com.kevin.legion.vehicle.BuildSheetController] spend has no currency column at all - never invent one. */
    @Test
    fun `get_spend reports currency as explicitly null with a note, never a guess`() = runBlocking {
        val result = LiveToolbox.dispatch(context, "get_spend", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertTrue(result.isNull("currency"))
        assertTrue(result.has("currency_note"))
    }
}
