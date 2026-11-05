package com.kevin.legion.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The engine's household-finance bodies (ADR 0073, tickets 07 and 08), read off the serializers in
 * `server/finance/figure_views.py` and `server/finance/views.py`, field for field. Dates stay ISO
 * strings here and are parsed where they are said in words, so a body this build cannot fully
 * understand still decodes. Money is `Long` cents (CLAUDE.md section 4 rule 3).
 */
internal val financeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** `GET /api/finance/safe-to-spend`. `available = false` carries only [reason]. */
@Serializable
data class SafeToSpendWire(
    val available: Boolean,
    val reason: String? = null,
    val currency: String? = null,
    @SerialName("safe_to_spend_cents") val safeCents: Long? = null,
    /** The next expected income date, or 30 days out when that is sooner ([untilReason]). */
    val until: String? = null,
    /** `next_income` or `30_days`. */
    @SerialName("until_reason") val untilReason: String? = null,
    @SerialName("next_income_on") val nextIncomeOn: String? = null,
    @SerialName("until_is_estimate") val untilIsEstimate: Boolean = false,
    val parts: SafePartsWire? = null,
    @SerialName("top_up_needed_cents") val topUpNeededCents: Long = 0,
    @SerialName("top_up_by") val topUpBy: String? = null,
    @SerialName("top_up_reason") val topUpReason: String? = null,
)

@Serializable
data class SafePartsWire(
    @SerialName("checking_cents") val checkingCents: Long = 0,
    @SerialName("checking_as_of") val checkingAsOf: String? = null,
    @SerialName("checking_pending_cents") val checkingPendingCents: Long? = null,
    /** Current balance plus pending charges. */
    @SerialName("card_owed_cents") val cardOwedCents: Long = 0,
    @SerialName("card_as_of") val cardAsOf: String? = null,
    @SerialName("bills_before_until_cents") val billsCents: Long = 0,
    val bills: List<BillWire> = emptyList(),
    /** True when the bills were worked out from recurring merchants: an estimate. */
    @SerialName("bills_detected") val billsDetected: Boolean = false,
)

@Serializable
data class BillWire(
    val label: String,
    @SerialName("due_on") val dueOn: String,
    @SerialName("amount_cents") val amountCents: Long,
)

/** `GET /api/finance/budgets?month=`. */
@Serializable
data class BudgetsWire(
    val month: String,
    val currency: String = "USD",
    @SerialName("days_in_month") val daysInMonth: Int = 0,
    @SerialName("days_elapsed") val daysElapsed: Int = 0,
    @SerialName("projection_from_day") val projectionFromDay: Int = 0,
    val categories: List<BudgetLineWire> = emptyList(),
    val total: BudgetTotalWire = BudgetTotalWire(),
    @SerialName("uncategorised_cents") val uncategorisedCents: Long = 0,
    val fixed: List<FixedWire> = emptyList(),
    @SerialName("new_subscriptions") val newSubscriptions: List<String> = emptyList(),
)

@Serializable
data class BudgetLineWire(
    val category: String,
    /** Pending rows included. */
    @SerialName("spent_cents") val spentCents: Long = 0,
    @SerialName("cap_cents") val capCents: Long? = null,
    val percent: Int? = null,
    /** cap x days elapsed / days in month: the pace marker. */
    @SerialName("pace_cents") val paceCents: Long? = null,
    /** An estimate; null before day 10. */
    @SerialName("projection_cents") val projectionCents: Long? = null,
    @SerialName("projection_is_estimate") val projectionIsEstimate: Boolean = false,
    /** `ok`, `near` (80%), `over`, or `no cap`. */
    val status: String = "ok",
    @SerialName("pending_cents") val pendingCents: Long = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    val unverified: Boolean = false,
)

@Serializable
data class BudgetTotalWire(
    @SerialName("spent_cents") val spentCents: Long = 0,
    @SerialName("cap_cents") val capCents: Long = 0,
    val percent: Int? = null,
    @SerialName("pace_cents") val paceCents: Long? = null,
)

@Serializable
data class FixedWire(
    val category: String,
    val paid: Boolean = false,
    @SerialName("paid_cents") val paidCents: Long = 0,
)

@Serializable
data class RateWire(
    /** 1 USD = rate_micros / 1e6 SGD. */
    @SerialName("rate_micros") val rateMicros: Long,
    @SerialName("rate_date") val rateDate: String,
    val source: String = "",
)

/** `GET /api/finance/networth?ccy=`. */
@Serializable
data class NetWorthWire(
    val currency: String = "USD",
    @SerialName("as_of") val asOf: String? = null,
    val rate: RateWire? = null,
    val layers: List<NetWorthLayerWire> = emptyList(),
    @SerialName("total_cents") val totalCents: Long = 0,
    @SerialName("total_is_estimate") val totalIsEstimate: Boolean = false,
    @SerialName("runway_cents") val runwayCents: Long = 0,
    /** Accounts with no balance on file, in no total. */
    val missing: List<String> = emptyList(),
    /** Accounts left out for want of a rate. */
    val unconverted: List<String> = emptyList(),
)

@Serializable
data class NetWorthLayerWire(
    /** liquid | investments | cpf | property | other. */
    val layer: String,
    @SerialName("total_cents") val totalCents: Long = 0,
    @SerialName("is_estimate") val isEstimate: Boolean = false,
    val accounts: List<NetWorthLineWire> = emptyList(),
)

@Serializable
data class NetWorthLineWire(
    val account: String,
    val label: String = "",
    val currency: String = "USD",
    /** In the account's currency; debt negative. */
    @SerialName("amount_cents") val amountCents: Long = 0,
    @SerialName("as_of") val asOf: String? = null,
    val source: String = "",
    @SerialName("is_estimate") val isEstimate: Boolean = false,
    /** Older than 45 days: say "as of" the day. */
    val stale: Boolean = false,
    @SerialName("pending_cents") val pendingCents: Long? = null,
    @SerialName("converted_cents") val convertedCents: Long? = null,
)

/** `GET /api/finance/cashflow?month=`, in USD. A 409 means no USD/SGD rate is stored. */
@Serializable
data class CashFlowWire(
    val month: String,
    val currency: String = "USD",
    val rate: RateWire? = null,
    /** From the income plan: an estimate. */
    @SerialName("income_planned_cents") val incomePlannedCents: Long = 0,
    @SerialName("income_planned_is_estimate") val incomePlannedIsEstimate: Boolean = true,
    @SerialName("income_on_file_cents") val incomeOnFileCents: Long = 0,
    @SerialName("spend_cents") val spendCents: Long = 0,
    @SerialName("mortgage_cents") val mortgageCents: Long = 0,
    /** Planned income - spend - mortgage. */
    @SerialName("net_cents") val netCents: Long = 0,
    @SerialName("test_a_passes") val testAPasses: Boolean = true,
    /** Liquid accounts with no ledger rows this month: their spend is not in it. */
    @SerialName("accounts_without_rows") val accountsWithoutRows: List<String> = emptyList(),
)

/** One row of `GET /api/finance/accounts`. No balance: that comes from [NetWorthWire]. */
@Serializable
data class FinanceAccountWire(
    val key: String,
    val label: String = "",
    val owner: String? = null,
    val institution: String = "",
    val country: String = "US",
    val currency: String = "USD",
    val kind: String = "",
    val layer: String = "",
    /** plaid | statement | ibkr_flex | computed | manual. */
    val source: String = "",
    val last4: String? = null,
    @SerialName("is_debt") val isDebt: Boolean = false,
    val active: Boolean = true,
)
