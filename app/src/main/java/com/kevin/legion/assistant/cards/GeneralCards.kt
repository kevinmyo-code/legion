package com.kevin.legion.assistant.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * The general kinds (readings, status_list, trend) on the wire, read and written. Their field
 * names are single words, so the phone writes exactly what the engine writes
 * (`server/engine_mcp/cards.py`) and `shared/cards/fixtures.json` round-trips unchanged.
 *
 * Reading is strict where the engine's `cards.check()` is: a usd value that is not whole cents,
 * a status that is not one of the five, a point with no date, a third series, or a picture field
 * that does not fit its shape ([CardPictures]) makes no card, rather than a card that draws
 * something the source did not say. Every picture field is optional: absent, the card draws as
 * it did before.
 */
object GeneralCards {

    private val AT = Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$""")

    fun readings(c: JSONObject): ConversationCard.Readings? = runCatching {
        val items = c.list("items").map(::reading)
        val lead = if (c.has("lead") && !c.isNull("lead")) c.get("lead") as Int else null
        require(lead == null || lead in items.indices) { "lead: $lead" }
        ConversationCard.Readings(
            title = c.getString("title"),
            items = items.take(ConversationCard.MAX_ROWS),
            at = at(c),
            more = c.optInt("more"),
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
            lead = lead?.takeIf { it < ConversationCard.MAX_ROWS },
        )
    }.getOrNull()

    private fun reading(i: JSONObject): Reading {
        val unit = i.getString("unit")
        val text = i.text("text")
        require(text == null || i.isNull("value")) { "a reading is a number or words, not both" }
        val scale = CardPictures.scale(i.obj("scale"), unit)
        val mark = CardPictures.mark(i.obj("mark"), unit)
        require(mark == null || scale != null) { "a mark needs a scale" }
        return Reading(
            label = i.getString("label"),
            value = i.number("value", unit),
            unit = unit,
            flag = flag(i),
            was = i.number("was", unit),
            note = i.text("note"),
            text = text,
            scale = scale,
            mark = mark,
            split = CardPictures.split(i.obj("split")),
            attention = if (i.has("attention")) i.get("attention") as Boolean else false,
        )
    }

    fun statusList(c: JSONObject): ConversationCard.StatusList? = runCatching {
        val items = c.list("items").map { i ->
            StatusRow(
                name = i.getString("name"),
                status = requireNotNull(RowStatus.of(i.optString("status"))) { "status" },
                note = i.text("note"),
                meter = CardPictures.meter(i.obj("meter")),
                level = CardPictures.level(i.obj("level")),
            )
        }
        ConversationCard.StatusList(
            title = c.getString("title"),
            items = items.take(ConversationCard.MAX_ROWS),
            at = at(c),
            more = c.optInt("more"),
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
        )
    }.getOrNull()

    fun trend(c: JSONObject): ConversationCard.Trend? = runCatching {
        val unit = c.getString("unit")
        val series = c.list("series").map { s ->
            TrendSeries(
                name = s.getString("name"),
                points = s.list("points").map { p ->
                    val at = p.getString("at").also { require(AT.matches(it)) { "at: $it" } }
                    TrendPoint(at, p.number("value", unit)!!)
                }.takeLast(ConversationCard.MAX_TREND),
                flag = flag(s),
            )
        }
        require(series.size in 1..ConversationCard.MAX_SERIES) { "series: ${series.size}" }
        val style = requireNotNull(TrendStyle.of(c.text("style"))) { "style" }
        // Bars draw one series: a second would be silently left out.
        require(style == TrendStyle.LINE || series.size == 1) { "bars take one series" }
        ConversationCard.Trend(
            title = c.getString("title"),
            unit = unit,
            series = series,
            scope = c.text("scope"),
            period = c.text("period"),
            note = c.text("note"),
            guide = CardPictures.mark(c.obj("guide"), unit),
            style = style,
        )
    }.getOrNull()

    fun write(out: JSONObject, card: ConversationCard.Readings): JSONObject = out.apply {
        card.at?.let { put("at", it) }
        card.lead?.let { put("lead", it) }
        put("items", JSONArray(card.items.map { r ->
            JSONObject().put("label", r.label).put("value", r.value ?: JSONObject.NULL).put("unit", r.unit).apply {
                r.flag?.let { put("flag", it.wire) }
                r.was?.let { put("was", it) }
                r.text?.let { put("text", it) }
                r.note?.let { put("note", it) }
                CardPictures.put(this, r)
            }
        }))
        put("more", card.more)
    }

    fun write(out: JSONObject, card: ConversationCard.StatusList): JSONObject = out.apply {
        card.at?.let { put("at", it) }
        put("items", JSONArray(card.items.map { r ->
            JSONObject().put("name", r.name).put("status", r.status.wire).apply {
                r.note?.let { put("note", it) }
                CardPictures.put(this, r)
            }
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
        card.guide?.let { put("guide", CardPictures.mark(it)) }
        if (card.style != TrendStyle.LINE) put("style", card.style.wire)
    }

    private fun at(c: JSONObject): String? = c.text("at")?.also { require(AT.matches(it)) { "at: $it" } }

    private fun flag(o: JSONObject): CardFlag? =
        o.text("flag")?.let { w -> CardFlag.entries.firstOrNull { it.wire == w } }
}
