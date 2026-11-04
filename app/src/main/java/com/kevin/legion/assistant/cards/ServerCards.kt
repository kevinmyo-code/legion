package com.kevin.legion.assistant.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * The engine's own cards (`server/engine_mcp/cards.py`), read from an `ask_engine` result's
 * `_meta.card`. The engine built them from its rows, never from model text, so the phone draws them
 * as they are. The engine writes the shared format in snake_case (`card`, `all_day`,
 * `mileage_as_of`, `next_service`, `total_ms`, `list_name`) and the camelCase spelling is read too.
 * Additive fields: timeline items' `date` and the car's `parts`. A car card with no `parts` (an
 * engine from before them) read all three parts. Anything unreadable is no card.
 */
object ServerCards {

    fun parse(card: JSONObject): ConversationCard? = runCatching {
        when (card.optString("card").ifBlank { card.optString("kind") }) {
            "bars" -> bars(card)
            "timeline" -> timeline(card)
            "car" -> car(card)
            "steps" -> steps(card)
            "checklist" -> checklist(card)
            else -> null
        }
    }.getOrNull()

    private fun JSONObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null }

    private fun JSONObject.long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optLong(k) else null }

    private fun JSONObject.list(key: String): List<JSONObject> =
        (optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    private fun bars(c: JSONObject) = ConversationCard.Bars(
        title = c.getString("title"),
        unit = c.optString("unit"),
        points = c.list("points").map { p ->
            BarPoint(p.getString("label"), p.getLong("value"), CardFlag.entries.firstOrNull { it.wire == p.str("flag") })
        },
        more = c.optInt("more"),
        scope = c.str("scope"),
        period = c.str("period"),
        note = c.str("note"),
    )

    private fun timeline(c: JSONObject) = ConversationCard.Timeline(
        title = c.getString("title"),
        date = c.getString("date"),
        zone = c.getString("zone"),
        items = c.list("items").map { i ->
            TimelineItem(
                start = i.str("start"),
                end = i.str("end"),
                allDay = i.optBoolean("all_day", i.optBoolean("allDay")),
                title = i.optString("title"),
                kind = TimelineKind.entries.firstOrNull { it.wire == i.optString("kind") } ?: TimelineKind.EVENT,
                date = i.str("date"),
            )
        },
        more = c.optInt("more"),
        scope = c.str("scope"),
        period = c.str("period"),
        note = c.str("note"),
    )

    private fun car(c: JSONObject): ConversationCard.Car {
        val parts = c.optJSONObject("parts")?.let { p ->
            p.keys().asSequence().mapNotNull { k -> CarPart.of(k)?.let { it to p.optString(k) } }.toMap()
        } ?: CarPart.entries.associateWith { "" }
        // With no parts the engine's `note` covers the whole card; with parts it is their join.
        val extra = c.str("note").takeIf { c.optJSONObject("parts") == null }
        return ConversationCard.Car(
            title = c.getString("title"),
            name = c.str("name") ?: c.getString("title"),
            mileage = c.long("mileage")?.toInt(),
            mileageAsOf = c.str("mileage_as_of", "mileageAsOf"),
            nextService = (c.optJSONObject("next_service") ?: c.optJSONObject("nextService"))?.let { n ->
                NextServiceDue(
                    label = n.getString("label"),
                    dueMiles = n.long("due_miles", "dueMiles")?.toInt(),
                    dueDate = n.str("due_date", "dueDate"),
                    estimate = n.optBoolean("estimate", true),
                )
            },
            recalls = c.list("recalls").map { Recall(it.optString("summary")) },
            codes = c.list("codes").map { TroubleCode(it.optString("code"), it.str("meaning")) },
            parts = parts,
            scope = c.str("scope"),
            period = c.str("period"),
            extraNote = extra,
        )
    }

    private fun steps(c: JSONObject) = ConversationCard.Steps(
        title = c.getString("title"),
        name = c.str("name") ?: c.getString("title"),
        items = c.list("items").map { s ->
            StepItem(
                label = s.getString("label"),
                status = StepStatus.entries.firstOrNull { it.wire == s.optString("status") } ?: StepStatus.FAILED,
                ms = s.long("ms"),
            )
        },
        totalMs = c.long("total_ms", "totalMs"),
        scope = c.str("scope"),
        period = c.str("period"),
        note = c.str("note"),
    )

    private fun checklist(c: JSONObject) = ConversationCard.Checklist(
        title = c.getString("title"),
        listName = c.str("list_name", "listName") ?: c.getString("title"),
        items = c.list("items").map { ChecklistLine(it.optString("text"), it.optBoolean("done")) },
        more = c.optInt("more"),
        scope = c.str("scope"),
        period = c.str("period"),
        note = c.str("note"),
    )
}
