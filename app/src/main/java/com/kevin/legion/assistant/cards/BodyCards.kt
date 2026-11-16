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
        val days = result.optJSONArray("recent_days")?.takeIf { result.optBoolean("success") } ?: return null
        val rows = (0 until days.length()).mapNotNull { days.optJSONObject(it) }
            .filter { DAY.matches(it.optString("day")) }
        fun pick(key: String) = rows.mapNotNull { r -> number(r, key)?.let { TrendPoint(r.getString("day"), it) } }
        val weighed = TrendSeries("Weighed", pick("weight_lbs"))
        val trend = TrendSeries("Trend", pick("trend_lbs"), CardFlag.ESTIMATE)
        val rate = number(result, "rate_lbs_per_week")?.let { r ->
            val way = if (r.signum() < 0) "Losing" else "Gaining"
            "$way ${r.abs().stripTrailingZeros().toPlainString()} lb a week (estimate)."
        }
        // The goal on file, as the result states it (its milestone), is the guide line.
        val goal = result.optJSONObject("milestone")?.let { number(it, "target_lbs") }
        val empty = weighed.points.isEmpty() && trend.points.isEmpty()
        return if (empty) null else ConversationCard.Trend(
            title = "Weight",
            unit = "lb",
            series = listOf(weighed, trend),
            period = "Last ${rows.size} days",
            note = listOfNotNull(rate, "Days with no weigh-in are left out.").joinToString(" "),
            open = CardDestination.BODY,
            guide = goal?.let { CardMark(it, "Goal ${it.stripTrailingZeros().toPlainString()} lb") },
        )
    }

    /**
     * `list_recent_sleep`'s nights ([nights]: the log's UTC date at midnight and minutes asleep),
     * hours per night, a column a night. A night with nothing logged is not in the list, so it is an
     * empty slot, never a zero. [targetMinutes] is the sleep target on file, drawn as the guide.
     */
    fun sleep(nights: List<Pair<Long, Int>>, targetMinutes: Int? = null): ConversationCard.Trend? {
        if (nights.isEmpty()) return null
        val points = nights.map { (dateMs, minutes) ->
            val day = Instant.ofEpochMilli(dateMs).atZone(ZoneOffset.UTC).toLocalDate()
            val hours = BigDecimal(minutes).divide(BigDecimal(MINUTES_PER_HOUR), 1, RoundingMode.HALF_UP)
            TrendPoint(day.toString(), hours)
        }.sortedBy { it.at }.takeLast(ConversationCard.MAX_TREND)
        val target = targetMinutes?.takeIf { it > 0 }?.let {
            BigDecimal(it).divide(BigDecimal(MINUTES_PER_HOUR), 1, RoundingMode.HALF_UP).stripTrailingZeros()
        }
        return ConversationCard.Trend(
            title = "Sleep",
            unit = "h",
            series = listOf(TrendSeries("Asleep", points)),
            period = "Last ${points.size} nights logged",
            note = "Nights with nothing logged are left out, not counted as zero.",
            open = CardDestination.BODY,
            guide = target?.let { CardMark(it, "Target ${it.toPlainString()} h") },
            style = TrendStyle.BARS,
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
        // The week's sessions done of planned, as the result counts them, lead the card as a bar.
        val planned = result.optInt("sessions_planned_this_week").takeIf { result.has("sessions_planned_this_week") }
        val week = planned?.takeIf { it > 0 }?.let { of ->
            val done = result.optInt("sessions_done_this_week")
            Reading(
                "Sessions this week",
                BigDecimal(done),
                "of $of",
                scale = CardScale(BigDecimal.ZERO, BigDecimal(maxOf(of, done))),
            )
        }
        val rows = listOfNotNull(week) + items
        return ConversationCard.Readings(
            title = if (today) "Today's workout" else "Next workout",
            items = rows.take(ConversationCard.MAX_ROWS),
            more = (rows.size - ConversationCard.MAX_ROWS).coerceAtLeast(0),
            period = result.optString(if (today) "training_day" else "next_day").ifBlank { null },
            note = listOfNotNull(
                "No sessions are planned this week.".takeIf { planned == 0 },
                "Nothing is planned for today.".takeIf { items.isEmpty() },
            ).joinToString(" ").ifBlank { null },
            open = CardDestination.BODY,
            lead = if (week != null) 0 else null,
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
            // A split bar only with all three macros: a stack missing one would look complete.
            val parts = listOf("Protein" to i.proteinG, "Carbs" to i.carbsG, "Fat" to i.fatG)
            val split = if (parts.all { it.second != null }) {
                CardSplit("g", parts.map { (label, g) -> SplitPart(label, DomainCards.dec(g, 1)!!) })
            } else {
                null
            }
            // With the split bar, its words say the macros; the note keeps the price.
            val words = if (macros.isEmpty() || split != null) {
                ""
            } else {
                macros.joinToString(", ").replaceFirstChar(Char::uppercase) + " (estimates). "
            }
            val note = words + price
            if (i.caloriesKcal == null) {
                Reading(i.name, null, "", text = "No estimate", note = note)
            } else {
                Reading(
                    i.name, BigDecimal(i.caloriesKcal), "kcal",
                    flag = CardFlag.ESTIMATE, note = note, split = split,
                )
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

    /** The number under [key] exactly as the result gave it; null when absent or null. */
    private fun number(o: JSONObject, key: String): BigDecimal? =
        if (o.has(key) && !o.isNull(key)) BigDecimal(o.get(key).toString()) else null

    private const val MINUTES_PER_HOUR = 60
}
