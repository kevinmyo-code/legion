package com.kevin.legion.finance

import com.kevin.legion.ui.home.HomeMoneyUiState
import java.time.LocalDate

/**
 * Synthetic finance bodies in the shape of the approved prototype. **Every figure is made up**
 * (this repo is public, household-finance map): none is a household figure.
 */
object FinanceFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 5)

    fun line(
        category: String,
        spent: Long,
        cap: Long?,
        pace: Long? = cap?.let { it * 5 / 30 },
        status: String? = null,
        pending: Long = 0,
    ) = BudgetLineWire(
        category = category,
        spentCents = spent,
        capCents = cap,
        percent = cap?.let { ((spent * 100 + it / 2) / it).toInt() },
        paceCents = pace,
        status = status ?: when {
            cap == null -> "no cap"
            spent > cap -> "over"
            spent * 100 >= cap * 80 -> "near"
            else -> "ok"
        },
        pendingCents = pending,
        pendingCount = if (pending > 0) 1 else 0,
    )

    val safe = SafeToSpendWire(
        available = true,
        currency = "USD",
        safeCents = -165_700,
        until = "2026-11-20",
        untilReason = "next_income",
        nextIncomeOn = "2026-11-20",
        untilIsEstimate = true,
        parts = SafePartsWire(
            checkingCents = 8_850, checkingAsOf = "2026-11-05", checkingPendingCents = -1_200,
            cardOwedCents = 174_574, cardAsOf = "2026-11-05", billsCents = 0, billsDetected = true,
        ),
        topUpNeededCents = 200_000,
        topUpBy = "2026-11-11",
        topUpReason = "below zero",
    )

    val budgets = BudgetsWire(
        month = "2026-11",
        daysInMonth = 30,
        daysElapsed = 5,
        categories = listOf(
            line("Groceries", 9_600, 62_000, pending = 3_210),
            line("Subscriptions", 11_900, 17_000),
            line("Shopping", 6_100, 13_000),
            line("Dining", 15_400, 14_000),
            line("Pets", 1_200, 9_000),
            line("Transport", 3_000, 25_000),
            line("Health", 2_500, 10_000),
            line("Household", 8_400, 10_000),
            line("Gifts", 4_000, null),
        ),
        // The capped lines' own sums, as the engine adds them: fixed categories are in neither.
        total = BudgetTotalWire(spentCents = 58_100, capCents = 160_000, percent = 36, paceCents = 26_667),
        fixed = listOf(
            FixedWire("Housing", paid = true, paidCents = 175_000),
            FixedWire("Insurance", paid = true, paidCents = 21_000),
            FixedWire("Utilities", paid = false),
        ),
    )

    val rate = RateWire(rateMicros = 1_294_600, rateDate = "2026-11-05", source = "test")

    val netWorth = NetWorthWire(
        currency = "USD",
        asOf = "2026-11-05",
        rate = rate,
        layers = listOf(
            NetWorthLayerWire(
                "liquid", -165_724, false,
                listOf(
                    NetWorthLineWire("us_checking", "Checking", "USD", 8_850, "2026-11-05", "plaid"),
                    NetWorthLineWire(
                        "us_card",
                        "Cash card",
                        "USD",
                        -174_574,
                        "2026-11-05",
                        "plaid",
                        pendingCents = -3_210,
                    ),
                ),
            ),
            NetWorthLayerWire("investments", 0, false, emptyList()),
            NetWorthLayerWire("cpf", 0, false, emptyList()),
            NetWorthLayerWire(
                "property", 15_100_000, true,
                listOf(NetWorthLineWire(
                    "flat",
                    "Flat",
                    "SGD",
                    19_500_000,
                    "2026-11-01",
                    "computed",
                    isEstimate = true,
                    convertedCents = 15_100_000,
                )),
            ),
            NetWorthLayerWire(
                "other", 350_000, false,
                listOf(NetWorthLineWire("car", "Car", "USD", 350_000, "2026-10-01", "manual")),
            ),
        ),
        totalCents = 14_820_000,
        totalIsEstimate = true,
        missing = listOf("sg_savings", "spouse_checking", "cpf_a", "cpf_b"),
    )

    val accounts = listOf(
        FinanceAccountWire("us_checking", "Everyday checking", source = "plaid", last4 = "0001", layer = "liquid"),
        FinanceAccountWire(
            "us_card",
            "A credit card with a very long marketing name indeed",
            source = "plaid",
            last4 = "0002",
            layer = "liquid",
            isDebt = true,
        ),
        FinanceAccountWire("spouse_checking", "Spouse checking", source = "statement", layer = "liquid"),
        FinanceAccountWire(
            "sg_savings",
            "Singapore savings",
            country = "SG",
            currency = "SGD",
            source = "statement",
            layer = "liquid",
        ),
        FinanceAccountWire("flat", "Flat", country = "SG", currency = "SGD", source = "computed", layer = "property"),
        FinanceAccountWire("car", "Car", source = "manual", layer = "other"),
    )

    val cashFlow = CashFlowWire(
        month = "2026-11",
        rate = rate,
        incomePlannedCents = 261_000,
        incomeOnFileCents = 120_000,
        spendCents = 140_200,
        mortgageCents = 75_700,
        netCents = 45_100,
        accountsWithoutRows = listOf("spouse_checking"),
    )

    val homeFresh = HomeMoneyUiState(
        loading = false,
        today = today,
        budgets = FinanceRead.Fresh(budgets),
    )
}
