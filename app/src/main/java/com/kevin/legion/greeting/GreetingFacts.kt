package com.kevin.legion.greeting

import org.json.JSONArray
import org.json.JSONObject

/**
 * The facts pack a greeting is built from (ADR 0091, alfred-greets ticket 01). The engine composes
 * five sources (`server/greeting/facts.py`); the phone adds a sixth, [GreetingPlace.SOURCE], because
 * only the phone holds a location fix. This file is the shapes and the parse; it holds no rule.
 *
 * **Three states, three different sentences** (CLAUDE.md section 1): [SourceState.FACTS] has items,
 * [SourceState.EMPTY] was read and nothing is on file, [SourceState.UNREADABLE] could not be read.
 * An unreadable source is never rendered as an empty one, here or in [GreetingBlock].
 */
enum class SourceState { FACTS, EMPTY, UNREADABLE }

/** One thing a greeting may mention. [topic] is the engine's stable key, or the phone's own for place. */
data class GreetingItem(val topic: String, val says: String, val raised: Boolean = false, val kind: String? = null)

data class GreetingSource(
    val name: String,
    val state: SourceState,
    /** The source's own sentence for its state: the engine's words, or the phone's for place. */
    val says: String,
    val reason: String? = null,
    val note: String? = null,
    val items: List<GreetingItem> = emptyList(),
)

data class FactsPack(
    val today: String,
    val utcOffset: String,
    val zoneNote: String?,
    val sources: List<GreetingSource>,
    /** Topic keys already raised: never ask again. */
    val said: Set<String>,
    /** Topic keys answered or silenced (the engine's, place keys included). */
    val closed: Set<String>,
    /** False when the engine could not read which topics were already raised: nothing was left out. */
    val topicsRead: Boolean,
    val topicsReason: String?,
)

/** How an engine call ended, the [com.kevin.legion.grades.GradesOutcome] shape. */
sealed interface GreetingOutcome<out T> {
    data class Ok<T>(val value: T) : GreetingOutcome<T>
    data class Unreachable(val sentence: String) : GreetingOutcome<Nothing>

    /** A 4xx, 403 recruiter and a 404 from an engine without the route included. */
    data class Refused(val sentence: String, val status: Int = 0) : GreetingOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : GreetingOutcome<Nothing>
}

/** The engine's `/api/greeting/` routes behind a seam, so every state is tested with a fake. */
interface GreetingBackend {
    suspend fun facts(): GreetingOutcome<FactsPack>

    /** Records [state] (`raised`, `answered` or `silenced`) for [topicKey]. True when the engine took it. */
    suspend fun recordTopic(topicKey: String, state: String): Boolean
}

object FactsParser {
    val ENGINE_SOURCES = listOf("ended_events", "today_events", "due_soon", "body", "money")

    /**
     * Parses `GET /api/greeting/facts`. A source the reply lacks, or one whose state is not one of
     * the three, becomes [SourceState.UNREADABLE]: a pack that does not say is not a clear day.
     */
    fun parse(json: String): FactsPack {
        val root = JSONObject(json)
        val sources = root.optJSONObject("sources") ?: JSONObject()
        val topics = root.optJSONObject("topics")
        return FactsPack(
            today = root.optString("today"),
            utcOffset = root.optString("utc_offset"),
            zoneNote = root.optStringOrNull("zone_note"),
            sources = ENGINE_SOURCES.map { name -> source(name, sources.optJSONObject(name)) },
            said = strings(root.optJSONArray("said")),
            closed = strings(root.optJSONArray("closed")),
            topicsRead = topics?.optString("state") == "read",
            topicsReason = topics?.optStringOrNull("reason"),
        )
    }

    private fun source(name: String, o: JSONObject?): GreetingSource {
        if (o == null) {
            val why = "The engine's reply did not include $name, so it could not be checked."
            return GreetingSource(name, SourceState.UNREADABLE, why, reason = why)
        }
        val state = when (o.optString("state")) {
            "facts" -> SourceState.FACTS
            "empty" -> SourceState.EMPTY
            else -> SourceState.UNREADABLE
        }
        val items = o.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::item) }
        }.orEmpty()
        val says = o.optString("says")
        // A "facts" source with no readable item says nothing mentionable: unreadable, not empty.
        val effective = if (state == SourceState.FACTS && items.isEmpty()) SourceState.UNREADABLE else state
        return GreetingSource(
            name = name,
            state = effective,
            says = says,
            reason = o.optStringOrNull("reason")
                ?: if (effective != state) "The engine said this source had facts but sent none." else null,
            note = o.optStringOrNull("note"),
            items = items,
        )
    }

    private fun item(o: JSONObject): GreetingItem? {
        val topic = o.optStringOrNull("topic")
        val says = o.optStringOrNull("says")
        return if (topic == null || says == null) {
            null
        } else {
            GreetingItem(topic, says, o.optBoolean("raised", false), o.optStringOrNull("kind"))
        }
    }

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
