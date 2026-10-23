package com.kevin.legion.brief

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One source link under a News bullet, as Gemini's grounding returned it. */
data class BriefLink(val title: String, val url: String)

/**
 * One sentence the engine composed (`server/brief/compose.py`). [text] is shown as it is: a surface
 * never builds its own phrase from the facts in it. [unverified] is true when a figure rests on bank
 * rows no statement gate checked; the sentence already says so, and a surface also marks it in words.
 */
data class BriefLine(val text: String, val unverified: Boolean, val links: List<BriefLink>)

/**
 * One server section. [key] and [status] are kept as the strings the engine sent, so a section key
 * or status the engine adds later still renders instead of blanking the brief. [note] is a sentence
 * about the section itself (Canvas freshness, why News failed).
 */
data class BriefSection(
    val key: String,
    val title: String,
    val status: String,
    val lines: List<BriefLine>,
    val note: String?,
) {
    val failed: Boolean get() = status == STATUS_FAILED

    companion object {
        /** `brief.models.SECTION_STATUSES`. */
        const val STATUS_OK = "ok"
        const val STATUS_EMPTY = "empty"
        const val STATUS_FAILED = "failed"
    }
}

/** `BriefSerializer`, the body of `GET /api/brief/latest` (200) and `POST /api/brief/refresh` (201). */
data class ServerBrief(
    val id: String,
    val briefDate: LocalDate,
    val builtAtMs: Long,
    val trigger: String,
    val ageSentence: String,
    val newsOk: Boolean,
    val notes: List<String>,
    val sections: List<BriefSection>,
) {
    companion object {
        /** `brief.models.Trigger`. */
        const val TRIGGER_SCHEDULED = "scheduled"
        const val TRIGGER_REFRESH = "refresh"
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

private fun JsonObject.requireString(key: String, what: String): String =
    requireNotNull(string(key)) { "$what carried no $key" }

private fun JsonObject.requireArray(key: String, what: String): JsonArray =
    requireNotNull(this[key] as? JsonArray) { "$what carried no $key list" }

/**
 * The engine's brief, read field by field. **The brief is the object itself, not wrapped**: a reply
 * shaped `{"brief": {...}}` or a bare list of sections throws rather than reading as an empty brief,
 * the same rule `parseFreshness` holds after the phone misread two wrapped replies. Any missing
 * required field throws [IllegalArgumentException], which `translatingEngineCall` turns into a
 * worded Malformed failure. Pinned by `BriefWireContractTest`.
 */
fun parseBrief(body: String): ServerBrief {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalArgumentException("the engine's brief reply was not an object")
    val what = "the engine's brief"
    val sections = root.requireArray("sections", what).map { element ->
        val section = element as? JsonObject ?: throw IllegalArgumentException("a brief section was not an object")
        parseSection(section)
    }
    return ServerBrief(
        id = root.requireString("id", what),
        briefDate = LocalDate.parse(root.requireString("brief_date", what)),
        builtAtMs = OffsetDateTime.parse(root.requireString("built_at", what)).toInstant().toEpochMilli(),
        trigger = root.requireString("trigger", what),
        ageSentence = root.requireString("age_sentence", what),
        newsOk = requireNotNull((root["news_ok"] as? JsonPrimitive)?.booleanOrNull) { "$what carried no news_ok" },
        notes = root.requireArray("notes", what).map {
            requireNotNull((it as? JsonPrimitive)?.contentOrNull) { "a brief note was not a sentence" }
        },
        sections = sections,
    )
}

private fun parseSection(section: JsonObject): BriefSection {
    val what = "a brief section"
    return BriefSection(
        key = section.requireString("key", what),
        title = section.requireString("title", what),
        status = section.requireString("status", what),
        lines = section.requireArray("lines", what).map { element ->
            val line = element as? JsonObject ?: throw IllegalArgumentException("a brief line was not an object")
            BriefLine(
                text = line.requireString("text", "a brief line"),
                unverified = (line["unverified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                links = (line["links"] as? JsonArray).orEmpty().map { link ->
                    val obj = link as? JsonObject ?: throw IllegalArgumentException("a brief link was not an object")
                    BriefLink(obj.requireString("title", "a brief link"), obj.requireString("url", "a brief link"))
                },
            )
        },
        note = section.string("note"),
    )
}

/**
 * The sentence inside a DRF `{"detail": "..."}` reply (404 no brief, 409 locked, 503 failed, 429
 * throttled), or null when the body is not that shape. Never reworded.
 */
fun briefDetail(body: String): String? = runCatching {
    ((Json.parseToJsonElement(body) as? JsonObject)?.get("detail") as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
