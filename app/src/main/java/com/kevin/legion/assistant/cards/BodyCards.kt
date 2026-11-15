package com.kevin.legion.assistant.cards

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Body and food cards (2026-11-15), split from [DomainCards] to keep each object small: weight,
 * sleep, today's workout and groceries with their macros. Same rule: the tool's own rows or result,
 * never the model's words; a night with nothing logged is absent, a macro is an estimate.
 */
object BodyCards {

    private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")

    // --- Body: get_weight_trend, list_recent_sleep, get_todays_workout -----------------------------

    /**
     * `get_weight_trend`'s own result: its `recent_days` as the weighed line and the trend line,
     * the trend flagged an estimate. Null when the read failed or holds no day.
     */
    fun weightTrend(result: JSONObject): ConversationCard.Trend? {
        if (!result.optBoolean("success")) return null
        val days = result.optJSONArray("recent_days") ?: return null
        val rows = (0 until days.length()).mapNotNull { days.optJSONObject(it) }.filter { DAY.matches(it.optString("day")) }
        fun pick(key: String) = rows.mapNotNull { r ->
            if (r.isNull(key) || !r.has(key)) null else TrendPoint(r.getString("day"), BigDecimal(r.get(key).toString()))
        }
        val weighed = TrendSeries("Weighed", pick("weight_lbs"))
        val trend = TrendSeries("Trend", pick("trend_lbs"), CardFlag.ESTIMATE)
        if (weighed.points.isEmpty() && trend.points.isEmpty()) return null
        val rate = if (result.has("rate_lbs_per_week") && !result.isNull("rate_lbs_per_week")) {
            val r = BigDecimal(result.get("rate_lbs_per_week").toString())
            val way = if (r.signum() < 0) "Losing" else "Gaining"
            "$way ${r.abs().stripTrailingZeros().toPlainString()} lb a week (estimate)."
        } else {
            null
        }
        return ConversationCard.Trend(
            title = "Weight",
            unit = "lb",
            series = listOf(weighed, trend),
            period = "Last ${rows.size} days",
            note = listOfNotNull(rate, "Days with no weigh-in are left out.").joinToString(" "),
            open = CardDestination.BODY,
        )
    }

    /**
     * `list_recent_sleep`'s nights ([nights]: the log's UTC date at midnight and minutes asleep),
     * hours per night. A night with nothing logged is not in the list, so it is not a zero here.
     */
    fun sleep(nights: List<Pair<Long, Int>>): ConversationCard.Trend? {
        if (nights.isEmpty()) return null
        val points = nights.map { (dateMs, minutes) ->
            val day = Instant.ofEpochMilli(dateMs).atZone(ZoneOffset.UTC).toLocalDate()
            TrendPoint(day.toString(), BigDecimal(minutes).divide(BigDecimal(MINUTES_PER_HOUR), 1, RoundingMode.HALF_UP))
        }.sortedBy { it.at }.takeLast(ConversationCard.MAX_TREND)
        return ConversationCard.Trend(
            title = "Sleep",
            unit = "h",
            series = listOf(TrendSeries("Asleep", points)),
            period = "Last ${points.size} nights logged",
            note = "Nights with nothing logged are left out, not counted as zero.",
            open = CardDestination.BODY,
        )
    }

    /**
     * `get_todays_workout`'s own result: each exercise with its target in words and the sets
     * ticked so far, or the next session's targets on a rest day. Null when the read failed.
     */
    fun workout(result: JSONObject): ConversationCard.Readings? {
        if (!result.optBoolean("success")) return null
        val exercises = result.optJSONArray("exercises") ?: JSONArray()
        val items = (0 until exercises.length()).mapNotNull { exercises.optJSONObject(it) }.map { e ->
            val ticked = e.optString("ticked").takeIf { it.isNotBlank() }
            Reading(
                label = e.optString("exercise").ifBlank { "Next" },
                value = null,
                unit = "",
                text = e.optString("target").ifBlank { null },
                note = ticked?.let { "Done: $it." } ?: e.optString("why").ifBlank { null },
            )
        }
        val today = result.has("session_status")
        val week = if (result.has("sessions_planned_this_week")) {
            "${result.optInt("sessions_done_this_week")} of ${result.optInt("sessions_planned_this_week")} sessions done this week."
        } else {
            null
        }
        return ConversationCard.Readings(
            title = if (today) "Today's workout" else "Next workout",
            items = items.take(ConversationCard.MAX_ROWS),
            more = (items.size - ConversationCard.MAX_ROWS).coerceAtLeast(0),
            period = result.optString(if (today) "training_day" else "next_day").ifBlank { null },
            note = listOfNotNull(
                week,
                "Nothing is planned for today.".takeIf { items.isEmpty() },
            ).joinToString(" ").ifBlank { null },
            open = CardDestination.BODY,
        )
    }

    // --- Food: list_recent_groceries --------------------------------------------------------------

    /**
     * `list_recent_groceries`' rows: each item's calories, an ESTIMATE (a receipt never prints one,
     * CLAUDE.md section 4 rule 5), with the other macros (estimates too) and its price, from Long
     * cents in its receipt's own currency, in the note. No calories is said, never a zero.
     */
    fun groceries(rows: List<com.kevin.legion.data.local.PantryLineItemWithCurrency>): ConversationCard.Readings? {
        if (rows.isEmpty()) return null
        val items = rows.map { row ->
            val i = row.item
            val macros = listOfNotNull(
                DomainCards.dec(i.proteinG, 1)?.let { "protein ${it.toPlainString()} g" },
                DomainCards.dec(i.carbsG, 1)?.let { "carbs ${it.toPlainString()} g" },
                DomainCards.dec(i.fatG, 1)?.let { "fat ${it.toPlainString()} g" },
            )
            val price = PhoneCards.money(i.totalPriceCents, row.currency.name.lowercase())
            val note = (if (macros.isEmpty()) "" else macros.joinToString(", ").replaceFirstChar(Char::uppercase) + " (estimates). ") + price
            if (i.caloriesKcal == null) {
                Reading(i.name, null, "", text = "No estimate", note = note)
            } else {
                Reading(i.name, BigDecimal(i.caloriesKcal), "kcal", flag = CardFlag.ESTIMATE, note = note)
            }
        }
        return ConversationCard.Readings(
            title = "Recent groceries",
            items = items.take(ConversationCard.MAX_ROWS),
            more = (items.size - ConversationCard.MAX_ROWS).coerceAtLeast(0),
            note = "Calories and macros are estimates from the item names; prices are the receipts'.",
            open = CardDestination.PANTRY,
        )
    }

    private const val MINUTES_PER_HOUR = 60
}
