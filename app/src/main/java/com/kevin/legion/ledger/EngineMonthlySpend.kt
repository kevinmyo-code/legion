package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency
import org.json.JSONArray
import org.json.JSONObject

/**
 * `get_monthly_spend` asks the engine first (2026-10-24, Kevin: "yeah do it"). The engine's
 * `GET /api/ledger/spend` is the one place the spend rules run for every client - the web page,
 * the head unit's `monthly_spend` MCP tool, and now the phone's voice. The phone's own
 * [LedgerController.budgetVsActual] is only the offline fallback, and a fallback answer says so.
 *
 * This maps the engine body onto the keys the tool already hands the model, so its description
 * holds for both sources. Two things the engine does not send, stated rather than invented:
 * transaction COUNTS for the exclusions (only amounts), and one note per moved Housing charge
 * (only the moved totals). Those fields are null or summarised here.
 *
 * Pure over its input so it is unit-tested without a device or a server.
 */
object EngineMonthlySpend {
    const val PATH = "/api/ledger/spend"

    fun toToolJson(body: JSONObject): JSONObject {
        val currency = runCatching { LedgerCurrency.valueOf(body.getString("currency")) }.getOrDefault(LedgerCurrency.USD)
        val accounts = body.optJSONArray("accounts") ?: JSONArray()
        var total = 0L
        var anyUnverified = body.optBoolean("uncategorised_unverified")
        for (i in 0 until accounts.length()) {
            val a = accounts.getJSONObject(i)
            total += a.optLong("spend_cents")
            if (a.optBoolean("unverified")) anyUnverified = true
        }
        val complete = body.optBoolean("complete")
        val excluded = body.optJSONObject("excluded") ?: JSONObject()
        val ownCents = excluded.optLong("own_account_moves_cents")
        val notSpendingCents = excluded.optLong("not_spending_cents")
        val notSpendingCats = excluded.optJSONArray("not_spending_categories")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        val uncategorised = body.optLong("uncategorised_cents")
        val here = excluded.optLong("early_charges_counted_here_cents")
        val next = excluded.optLong("early_charges_counted_next_month_cents")

        val housing = JSONArray()
        if (here != 0L) {
            housing.put("Includes ${formatMoney(here, currency)} of $EARLY_CHARGE_CATEGORY charges dated last month, counted in this one.")
        }
        if (next != 0L) {
            housing.put("${formatMoney(next, currency)} of $EARLY_CHARGE_CATEGORY charges dated this month count in next month, not here.")
        }

        return JSONObject()
            .put("success", true)
            .put("source", "engine")
            .put("currency", currency.name)
            .put("month", body.optString("month"))
            .put("total_spent_cents", total)
            .put("verified", !anyUnverified && complete)
            .put("excluded_own_account_movements_count", JSONObject.NULL)
            .put("excluded_own_account_movements_cents", ownCents)
            .put("uncategorized_cents", uncategorised)
            .put(
                "uncategorized_note",
                if (uncategorised == 0L) "Nothing uncategorised this month - spend counts every operating expense."
                else "${formatMoney(uncategorised, currency)} is not assigned to a category and is NOT counted in spend. " +
                    "Categorise it to have it counted.",
            )
            .put(
                "note",
                if (ownCents == 0L) "No own-account movements excluded from spend this month."
                else "${formatMoney(ownCents, currency)} moving money to your own accounts excluded from spend.",
            )
            .put("excluded_not_spending_count", JSONObject.NULL)
            .put("excluded_not_spending_cents", notSpendingCents)
            .put(
                "not_spending_note",
                if (notSpendingCents == 0L) "Nothing filed under a not-spending category this month."
                else "${formatMoney(notSpendingCents, currency)} in ${notSpendingCats.joinToString(", ")} excluded from spend.",
            )
            .put("early_housing_notes", housing)
    }

    /** Said beside a figure from the phone's own copy: which source, and why not the engine. */
    fun fallbackNote(reason: String): String =
        "The engine could not be asked ($reason), so this figure is from the phone's own copy of " +
            "the ledger as of its last sync, and may be behind."
}
