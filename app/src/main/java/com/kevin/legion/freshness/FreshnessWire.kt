package com.kevin.legion.freshness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One feed's row from `GET /api/freshness`. Only the four fields this phone acts on are read.
 * [sentence] is the server's own words and is rendered as it is: `server/ingest/freshness.py`'s
 * rule is that surfaces never compose their own phrase from the other fields.
 */
data class FeedFreshness(
    val source: String,
    val lastOutcome: String?,
    val lastError: String?,
    val sentence: String,
) {
    val needsLogin: Boolean get() = lastOutcome == NEEDS_LOGIN

    companion object {
        /** `ingest.models.Outcome.NEEDS_LOGIN`: a saved login the upstream refused. */
        const val NEEDS_LOGIN = "needs_login"
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/**
 * `FreshnessSerializer`: the engine WRAPS the rows as `{"sources": [ ... ]}`, it does not return a
 * bare list (the same shape of mistake the games parser once made with the invite). A reply
 * without that key throws rather than reading as "no feeds", so a changed envelope cannot look
 * like a healthy day. Pinned by `FreshnessWireContractTest`.
 */
fun parseFreshness(body: String): List<FeedFreshness> {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalArgumentException("the engine's freshness reply was not an object")
    val rows = root["sources"] as? JsonArray
        ?: throw IllegalArgumentException("the engine's freshness reply carried no sources")
    return rows.filterIsInstance<JsonObject>().map { row ->
        FeedFreshness(
            source = requireNotNull(row.string("source")) { "a freshness row carried no source" },
            lastOutcome = row.string("last_outcome"),
            lastError = row.string("last_error"),
            sentence = requireNotNull(row.string("sentence")) { "a freshness row carried no sentence" },
        )
    }
}
