package com.kevin.legion.board

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import org.json.JSONObject

/**
 * The factory board's engine copy as the phone reads it (backlog intake 1122): `GET /api/board`'s
 * short "where are we" ([BoardBrief]), and backlog intake (`/api/board/ideas`: draft, confirm,
 * drop). The engine writes every word a voice says here; the phone only speaks them.
 */
data class BoardCardRow(val title: String, val col: String, val detail: String)

data class BoardBrief(
    /** Lines spoken word for word (code-made on the engine, card titles included). */
    val lines: List<String>,
    /** Freshness and counts only, no card text: what the model may read. */
    val forModel: String,
    val cards: List<BoardCardRow>,
)

data class IdeaDraft(val id: String, val title: String, val readback: String)

/** An engine answer, or the sentence saying what did not happen. */
sealed interface BoardOutcome<out T> {
    data class Ok<T>(val value: T) : BoardOutcome<T>
    data class Failed(val sentence: String) : BoardOutcome<Nothing>
}

interface BoardBackend {
    suspend fun brief(): BoardOutcome<BoardBrief>
    suspend fun draftIdea(text: String): BoardOutcome<IdeaDraft>

    /** His yes: the engine's own "saved" words. */
    suspend fun confirmIdea(id: String): BoardOutcome<String>

    /** His no: the engine's own "dropped" words. */
    suspend fun dropIdea(id: String): BoardOutcome<String>
}

class EngineBoardBackend(private val http: EngineHttp) : BoardBackend {

    override suspend fun brief(): BoardOutcome<BoardBrief> = call(http.get(BOARD)) { body ->
        val json = JSONObject(body)
        val lines = json.optJSONArray("brief")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?.filter { it.isNotBlank() }.orEmpty()
        val cards = json.optJSONObject("snapshot")?.optJSONArray("cards")?.let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                val detail = c.optString("ask").ifBlank { c.optString("status") }
                BoardCardRow(c.optString("title"), c.optString("col"), detail)
            }
        }.orEmpty()
        BoardBrief(lines, json.optString("brief_for_model"), cards)
    }

    override suspend fun draftIdea(text: String): BoardOutcome<IdeaDraft> =
        call(http.post(IDEAS, JSONObject().put("text", text).toString())) { body ->
            val json = JSONObject(body)
            IdeaDraft(json.getString("id"), json.optString("title"), json.getString("readback"))
        }

    override suspend fun confirmIdea(id: String): BoardOutcome<String> =
        call(http.post("$IDEAS/$id/confirm", "{}")) { JSONObject(it).getString("words") }

    override suspend fun dropIdea(id: String): BoardOutcome<String> =
        call(http.delete("$IDEAS/$id")) { JSONObject(it).getString("words") }

    @Suppress("SwallowedException") // a malformed body is a sentence, not a crash
    private inline fun <T> call(
        result: Result<com.kevin.legion.backend.engine.EngineOk>,
        parse: (String) -> T,
    ): BoardOutcome<T> {
        val ok = result.getOrElse { return BoardOutcome.Failed(failed(it)) }
        return try {
            BoardOutcome.Ok(parse(ok.body))
        } catch (e: org.json.JSONException) {
            BoardOutcome.Failed("The engine's answer could not be read. Nothing was changed.")
        }
    }

    private fun failed(error: Throwable): String {
        val failure = (error as? EngineHttpException)?.failure
        return when (failure) {
            is EngineFailure.Refused -> when (failure.status) {
                NOT_FOUND -> OFF
                else -> detail(failure.body) ?: "The engine refused that. Nothing was changed."
            }
            is EngineFailure.Unauthorized -> detail(failure.body) ?: failure.sentence
            null -> "The engine could not be reached. Nothing was changed."
            else -> failure.sentence
        }
    }

    private fun detail(body: String): String? =
        runCatching { JSONObject(body).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val BOARD = "/api/board"
        private const val IDEAS = "/api/board/ideas"
        private const val NOT_FOUND = 404
        const val OFF = "The factory board is switched off on the engine, so I can't read it or add to it."
    }
}
