package com.kevin.legion.assistant.cards

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * The general kinds (readings, status_list, trend) on the wire, read and written. Their field
 * names are single words, so the phone writes exactly what the engine writes
 * (`server/engine_mcp/cards.py`) and `shared/cards/fixtures.json` round-trips unchanged.
 *
 * Reading is strict where the engine's `cards.check()` is: a usd value that is not whole cents,
 * a status that is not one of the five, a point with no date, or a third series makes no card,
 * rather than a card that draws something the source did not say.
 */
object GeneralCards {

    private val AT = Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$""")

    fun readings(c: JSONObject): ConversationCard.Readings? {
        val items = c.list("items").map { i ->
            val unit = i.getString("unit")
            val text = i.text("text")
            require(text == null || i.isNull("value")) { "a reading is a number or words, not both" }
            Reading(
                label = i.getString("label"),
                value = i.number("value", unit),
                unit = unit,
                flag = flag(i),
                was = i.number("was", unit),
                note = i.text("note"),
                text = text,
            )
        }
        return ConversationCard.Readings(
            title = c.getString("title"),
            items = items.take(ConversationCard.MAX_ROWS),
            at = at(c),
            more = c.optInt("more"),
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
        )
    }

    fun statusList(c: JSONObject): ConversationCard.StatusList? {
        val items = c.list("items").map { i ->
            StatusRow(
                name = i.getString("name"),
                status = RowStatus.of(i.optString("status")) ?: return null,
                note = i.text("note"),
            )
        }
        return ConversationCard.StatusList(
            title = c.getString("title"),
            items = items.take(ConversationCard.MAX_ROWS),
            at = at(c),
            more = c.optInt("more"),
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
        )
    }

    fun trend(c: JSONObject): ConversationCard.Trend? {
        val unit = c.getString("unit")
        val series = c.list("series").map { s ->
            TrendSeries(
                name = s.getString("name"),
                points = s.list("points").map { p ->
                    val at = p.getString("at").takeIf { AT.matches(it) } ?: return null
                    TrendPoint(at, p.number("value", unit) ?: return null)
                }.takeLast(ConversationCard.MAX_TREND),
                flag = flag(s),
            )
        }
        if (series.isEmpty() || series.size > ConversationCard.MAX_SERIES) return null
        return ConversationCard.Trend(
            title = c.getString("title"),
            unit = unit,
            series = series,
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
        )
    }

    fun write(out: JSONObject, card: ConversationCard.Readings): JSONObject = out.apply {
        card.at?.let { put("at", it) }
        put("items", JSONArray(card.items.map { r ->
            JSONObject().put("label", r.label).put("value", r.value ?: JSONObject.NULL).put("unit", r.unit).apply {
                r.flag?.let { put("flag", it.wire) }
                r.was?.let { put("was", it) }
                r.text?.let { put("text", it) }
                r.note?.let { put("note", it) }
            }
        }))
        put("more", card.more)
    }

    fun write(out: JSONObject, card: ConversationCard.StatusList): JSONObject = out.apply {
        card.at?.let { put("at", it) }
        put("items", JSONArray(card.items.map { r ->
            JSONObject().put("name", r.name).put("status", r.status.wire).apply { r.note?.let { put("note", it) } }
        }))
        put("more", card.more)
    }

    fun write(out: JSONObject, card: ConversationCard.Trend): JSONObject = out.apply {
        put("unit", card.unit)
        put("series", JSONArray(card.series.map { s ->
            JSONObject().put("name", s.name)
                .put("points", JSONArray(s.points.map { JSONObject().put("at", it.at).put("value", it.value) }))
                .apply { s.flag?.let { put("flag", it.wire) } }
        }))
    }

    private fun at(c: JSONObject): String? = c.text("at")?.also { require(AT.matches(it)) { "at: $it" } }

    private fun flag(o: JSONObject): CardFlag? = o.text("flag")?.let { w -> CardFlag.entries.firstOrNull { it.wire == w } }

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.list(key: String): List<JSONObject> =
        (optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    /** The number under [key] exactly as sent; null when absent or null. Usd must be whole cents. */
    private fun JSONObject.number(key: String, unit: String): BigDecimal? {
        if (!has(key) || isNull(key)) return null
        val raw = get(key)
        require(raw is Number) { "$key is not a number" }
        val value = BigDecimal(raw.toString())
        if (unit == "usd") require(value.stripTrailingZeros().scale() <= 0) { "$key: usd is whole cents" }
        return value
    }
}
