package com.kevin.legion.assistant.cards

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * The optional picture fields of the general kinds on the wire (2026-11-15): a reading's `scale`,
 * `mark`, `split` and `attention`, a status row's `meter` and `level`, a trend's `guide`. Read as
 * strictly as `server/engine_mcp/cards.py` checks them: a field present but malformed throws, and
 * [GeneralCards] then makes no card. Absent, each reads as null and the card draws as before.
 */
internal object CardPictures {

    fun scale(o: JSONObject?, unit: String): CardScale? = o?.let { s ->
        CardScale(s.number("min", unit)!!, s.number("max", unit)!!).also { require(it.min < it.max) { "scale" } }
    }

    fun mark(o: JSONObject?, unit: String): CardMark? =
        o?.let { CardMark(it.number("value", unit)!!, it.text("label")!!) }

    fun split(o: JSONObject?): CardSplit? = o?.let { s ->
        val parts = s.list("parts").map { p ->
            SplitPart(p.getString("label"), p.number("value", "")!!.also { require(it.signum() >= 0) { "part" } })
        }
        require(parts.size in SPLIT_MIN..SPLIT_MAX) { "split parts: ${parts.size}" }
        CardSplit(s.getString("unit"), parts)
    }

    fun meter(o: JSONObject?): StatusMeter? = o?.let { m ->
        val unit = m.getString("unit")
        StatusMeter(m.number("value", unit)!!, m.number("max", unit)!!, unit, mark(m.obj("mark"), unit)).also {
            require(it.value.signum() >= 0 && it.max.signum() > 0) { "meter" }
        }
    }

    fun level(o: JSONObject?): StatusLevel? = o?.let { l ->
        StatusLevel(l.get("value") as Int, l.get("of") as Int).also {
            require(it.value in 1..it.of && it.of <= MAX_LEVEL) { "level" }
        }
    }

    /** A reading's picture fields onto its wire object. */
    fun put(out: JSONObject, r: Reading) {
        r.scale?.let { out.put("scale", JSONObject().put("min", it.min).put("max", it.max)) }
        r.mark?.let { out.put("mark", mark(it)) }
        r.split?.let { s ->
            val parts = JSONArray(s.parts.map { JSONObject().put("label", it.label).put("value", it.value) })
            out.put("split", JSONObject().put("unit", s.unit).put("parts", parts))
        }
        if (r.attention) out.put("attention", true)
    }

    /** A status row's picture fields onto its wire object. */
    fun put(out: JSONObject, r: StatusRow) {
        r.meter?.let { m ->
            val meter = JSONObject().put("value", m.value).put("max", m.max).put("unit", m.unit)
            m.mark?.let { meter.put("mark", mark(it)) }
            out.put("meter", meter)
        }
        r.level?.let { out.put("level", JSONObject().put("value", it.value).put("of", it.of)) }
    }

    fun mark(m: CardMark): JSONObject = JSONObject().put("value", m.value).put("label", m.label)

    private const val SPLIT_MIN = 2
    private const val SPLIT_MAX = 4
    private const val MAX_LEVEL = 5
}

internal fun JSONObject.text(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

internal fun JSONObject.obj(key: String): JSONObject? = if (has(key) && !isNull(key)) getJSONObject(key) else null

internal fun JSONObject.list(key: String): List<JSONObject> =
    (optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

/** The number under [key] exactly as sent; null when absent or null. Usd must be whole cents. */
internal fun JSONObject.number(key: String, unit: String): BigDecimal? {
    if (!has(key) || isNull(key)) return null
    val raw = get(key)
    require(raw is Number) { "$key is not a number" }
    val value = BigDecimal(raw.toString())
    if (unit == "usd") require(value.stripTrailingZeros().scale() <= 0) { "$key: usd is whole cents" }
    return value
}
