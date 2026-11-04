package com.kevin.legion.assistant.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes a [ConversationCard] in the format shared with the web assistant's cards. Nothing on the
 * phone reads it back today; it exists so the phone's cards are checkable against that format
 * (`CardJsonTest`) and so a log or a future sync carries the same shape. A null [ConversationCard.Car]
 * list is written as `[]`, and the not-looked-up fact is already in words in its `note`.
 */
object CardJson {
    fun write(card: ConversationCard): JSONObject {
        val out = JSONObject().put("kind", card.kind).put("title", card.title)
        card.scope?.let { out.put("scope", it) }
        card.period?.let { out.put("period", it) }
        card.note?.let { out.put("note", it) }
        when (card) {
            is ConversationCard.Bars -> bars(out, card)
            is ConversationCard.Timeline -> timeline(out, card)
            is ConversationCard.Car -> car(out, card)
            is ConversationCard.Steps -> steps(out, card)
            is ConversationCard.Checklist -> out
                .put("listName", card.listName)
                .put("items", JSONArray(card.items.map { JSONObject().put("text", it.text).put("done", it.done) }))
                .put("more", card.more)
        }
        return out
    }

    private fun orNull(value: Any?): Any = value ?: JSONObject.NULL

    private fun bars(out: JSONObject, card: ConversationCard.Bars) = out
        .put("unit", card.unit)
        .put("points", JSONArray(card.points.map { p ->
            JSONObject().put("label", p.label).put("value", p.value).put("flag", orNull(p.flag?.wire))
        }))
        .put("more", card.more)

    private fun timeline(out: JSONObject, card: ConversationCard.Timeline) = out
        .put("date", card.date)
        .put("zone", card.zone)
        .put("items", JSONArray(card.items.map { i ->
            JSONObject().put("start", orNull(i.start)).put("end", orNull(i.end))
                .put("allDay", i.allDay).put("title", i.title).put("kind", i.kind.wire)
        }))
        .put("more", card.more)

    private fun car(out: JSONObject, card: ConversationCard.Car) = out
        .put("name", card.name)
        .put("mileage", orNull(card.mileage))
        .put("mileageAsOf", orNull(card.mileageAsOf))
        .put("nextService", orNull(card.nextService?.let { n ->
            JSONObject().put("label", n.label).put("estimate", n.estimate).apply {
                n.dueMiles?.let { put("dueMiles", it) }
                n.dueDate?.let { put("dueDate", it) }
            }
        }))
        .put("recalls", JSONArray(card.recalls.orEmpty().map { JSONObject().put("summary", it.summary) }))
        .put("codes", JSONArray(card.codes.orEmpty().map { c ->
            JSONObject().put("code", c.code).put("meaning", orNull(c.meaning))
        }))

    private fun steps(out: JSONObject, card: ConversationCard.Steps) = out
        .put("name", card.name)
        .put("items", JSONArray(card.items.map { s ->
            JSONObject().put("label", s.label).put("status", s.status.wire).put("ms", orNull(s.ms))
        }))
        .put("totalMs", orNull(card.totalMs))
}
