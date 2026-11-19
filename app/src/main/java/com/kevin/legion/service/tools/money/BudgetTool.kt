package com.kevin.legion.service.tools.money

import android.content.Context
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.finance.BudgetVoice
import com.kevin.legion.finance.FinanceController
import com.kevin.legion.service.SpendMonth
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * `get_monthly_spend`'s budget views (2026-11-19): `left`, `category`, `top`. Reads the one
 * [FinanceController.budgets] HOME's "left for <month>" reads, with its saved-copy fallback offline,
 * and hands back [BudgetVoice]'s structured fields plus its sentence for the phone to speak itself.
 * The `total` view stays `LiveToolbox.getMonthlySpend` (the whole month's spend, which this body does
 * not carry).
 */
internal object BudgetTool {

    suspend fun answer(context: Context, view: String, args: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val current = BudgetVoice.currentMonth(now, ZoneId.systemDefault())
        val (month, monthNote) = SpendMonth.resolve(args.optString("month"), current)
        val at = BudgetVoice.At(month = month, current = current, nowMs = now)
        val read = FinanceController.forApp(context).budgets(month)
        val answer = when (view) {
            "left" -> BudgetVoice.left(read, at)
            "top" -> BudgetVoice.top(read, at)
            else -> {
                val dao = CarDatabase.getDatabase(context).categoryDao()
                BudgetVoice.category(read, args.optString("category"), dao.allNames(), dao.notSpendingNames().toSet(), at)
            }
        }
        return json(answer).apply { monthNote?.let { put("month_note", it) } }
    }

    private fun json(a: BudgetVoice.Answer): JSONObject {
        val out = JSONObject()
            .put("success", a.ok)
            .put("view", a.view)
            .put("month", a.month.toString())
            .put("period_start", a.periodStart)
            .put("period_end", a.periodEnd)
            .put("currency", a.currency)
            .put("stale", a.stale)
            .put("saved_copy", a.saved)
        a.asOf?.let { out.put("as_of", it) }
        a.category?.let { out.put("category", it) }
        a.amountCents?.let { out.put("amount_cents", it).put("amount", a.amount) }
        a.capCents?.let { out.put("cap_cents", it).put("cap", BudgetVoice.decimal(it)) }
        a.leftCents?.let { out.put("left_cents", it).put("left", BudgetVoice.decimal(it)) }
        if (a.pendingCents > 0) out.put("pending_cents", a.pendingCents)
        if (a.top.isNotEmpty()) {
            out.put("top", JSONArray(a.top.map { JSONObject().put("category", it.category).put("amount", it.amount) }))
        }
        if (a.validCategories.isNotEmpty() && a.speech == null) out.put("valid_categories", JSONArray(a.validCategories))
        if (a.matches.isNotEmpty()) out.put("matches", JSONArray(a.matches))
        a.note?.let { out.put("note", it) }
        a.speech?.let {
            VerbatimSpeech.mark(out, it, VerbatimSpeech.BUDGET)
            out.put("rule", com.kevin.legion.finance.NetCashVoice.RULE)
        }
        return out
    }
}
