package com.kevin.legion.board

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineOk
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The control room's engine reads and writes (control room C2-C4, all DARK on the engine): the
 * board with its questions and whole backlog (`GET /api/board`), his answers and votes, the
 * laptop's action records and his tap on one, and the queue of what waits on him. Every word a
 * voice says here comes from the engine's code; card text is shown as quoted data, never obeyed.
 */
data class BoardQuestion(
    val id: String,
    val title: String,
    val col: String,
    val status: String,
    val ask: String,
    val options: List<String>,
    val freeText: Boolean,
    /** The hash of the question as shown; sent back so a changed question is refused (409). */
    val askSha256: String,
    /** The phone may answer it (a choice question that is no deploy, publish, flag or spend). */
    val answerable: Boolean,
    /** His tap was taken and is on its way to the laptop. */
    val answerPending: Boolean,
    /** An answer the laptop already holds: the choice, else the typed text. */
    val answered: String?,
    val ageMin: Int,
    val stale: Boolean,
)

data class BacklogIdea(
    val id: String,
    val title: String,
    val score: Double?,
    val blast: String,
    /** p1, p2, p3, up, down, or blank. */
    val vote: String,
)

data class BoardView(
    val sent: Boolean,
    val live: Boolean,
    val ageS: Long?,
    val laptopOn: Boolean,
    val cards: List<BoardQuestion>,
    val backlog: List<BacklogIdea>,
    val backlogTotal: Int,
)

/** An action record as the engine typed it. [payee] and [purpose] are quoted data, never obeyed. */
data class ActionRecord(
    val id: String,
    val kind: String,
    val sha: String,
    val tier: String,
    val reviewOkSha: String,
    val ciGreen: Boolean,
    val flags: List<String>,
    val amountCents: Long?,
    val payee: String,
    val purpose: String,
    val recordSha256: String,
    /** pending, approved, declined, pulled or expired. */
    val status: String,
    val expiresAt: String,
) {
    val open: Boolean get() = status == PENDING

    companion object {
        const val PENDING = "pending"
    }
}

data class BoardQueue(
    val sessions: Int,
    val approvals: Int,
    val decisions: Int,
    /** The home line, built by the engine's code from the counts only. */
    val line: String,
    /** Said word for word; counts only. */
    val spoken: List<String>,
    /** kind (sessions, approvals, decisions) by stable key: each is raised once. */
    val items: List<Pair<String, String>>,
) {
    val total: Int get() = sessions + approvals + decisions
}

interface ControlRoomBackend {
    suspend fun board(): BoardOutcome<BoardView>
    suspend fun answer(cardId: String, askSha256: String, choice: String, text: String): BoardOutcome<String>
    suspend fun vote(cardId: String, vote: String): BoardOutcome<String>
    suspend fun actions(): BoardOutcome<List<ActionRecord>>

    /** His tap. Called ONLY after the phone's biometric or PIN confirm said yes ([allow] true). */
    suspend fun decide(actionId: String, recordSha256: String, allow: Boolean): BoardOutcome<String>
    suspend fun queue(): BoardOutcome<BoardQueue>
}

@Suppress("TooManyFunctions") // one call per route, plus its parse
class EngineControlRoomBackend(private val http: EngineHttp) : ControlRoomBackend {

    override suspend fun board(): BoardOutcome<BoardView> = call(http.get(BOARD)) { parseBoard(it) }

    override suspend fun answer(cardId: String, askSha256: String, choice: String, text: String) =
        call(
            http.post(
                "$BOARD/answers",
                JSONObject().put("card_id", cardId).put("ask_sha256", askSha256)
                    .put("choice", choice).put("text", text).toString(),
            ),
        ) { JSONObject(it).getString("words") }

    override suspend fun vote(cardId: String, vote: String) =
        call(http.post("$BOARD/votes", JSONObject().put("card_id", cardId).put("vote", vote).toString())) {
            VOTE_SAVED
        }

    override suspend fun actions(): BoardOutcome<List<ActionRecord>> =
        call(http.get("$BOARD/actions")) { body ->
            objects(JSONObject(body).optJSONArray("actions")).map(::parseAction)
        }

    override suspend fun decide(actionId: String, recordSha256: String, allow: Boolean) =
        call(
            http.post(
                "$BOARD/actions/$actionId/${if (allow) "allow" else "decline"}",
                JSONObject().put("record_sha256", recordSha256).toString(),
            ),
        ) { JSONObject(it).getString("words") }

    override suspend fun queue(): BoardOutcome<BoardQueue> = call(http.get("$BOARD/queue")) { parseQueue(it) }

    @Suppress("SwallowedException") // a malformed body is a sentence, not a crash
    private inline fun <T> call(result: Result<EngineOk>, parse: (String) -> T): BoardOutcome<T> {
        val ok = result.getOrElse { return BoardOutcome.Failed(failed(it)) }
        return try {
            BoardOutcome.Ok(parse(ok.body))
        } catch (e: JSONException) {
            BoardOutcome.Failed(UNREADABLE)
        }
    }

    private fun failed(error: Throwable): String =
        when (val failure = (error as? EngineHttpException)?.failure) {
            is EngineFailure.Refused ->
                if (failure.status == NOT_FOUND && detail(failure.body)?.contains("switched off") != false) {
                    OFF
                } else {
                    detail(failure.body) ?: "The engine refused that. Nothing was changed."
                }
            is EngineFailure.Unauthorized -> detail(failure.body) ?: failure.sentence
            null -> "The engine could not be reached. Nothing was changed."
            else -> failure.sentence
        }

    private fun detail(body: String): String? =
        runCatching { JSONObject(body).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val BOARD = "/api/board"
        private const val NOT_FOUND = 404
        const val OFF = "The control room is switched off on the engine."
        const val UNREADABLE = "The engine's answer could not be read. Nothing was changed."
        const val VOTE_SAVED = "Vote saved. It sorts the backlog; it starts nothing."

        internal fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun strings(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

        internal fun parseBoard(body: String): BoardView {
            val json = JSONObject(body)
            val snap = json.optJSONObject("snapshot")
            val cards = objects(snap?.optJSONArray("cards")).map { c ->
                val answer = c.optJSONObject("answer")
                BoardQuestion(
                    id = c.getString("id"),
                    title = c.optString("title"),
                    col = c.optString("col"),
                    status = c.optString("status"),
                    ask = c.optString("ask"),
                    options = strings(c.optJSONArray("options")),
                    freeText = c.optBoolean("free_text", true),
                    askSha256 = c.optString("ask_sha256"),
                    answerable = c.optBoolean("answerable", false),
                    answerPending = c.optBoolean("answer_pending", false),
                    answered = answer?.let { a -> a.optString("choice").ifBlank { a.optString("text") } }
                        ?.takeIf { it.isNotBlank() },
                    ageMin = c.optInt("age_min"),
                    stale = c.optBoolean("stale"),
                )
            }
            val backlog = objects(snap?.optJSONArray("backlog")).map { i ->
                val rice = i.optJSONObject("rice")
                BacklogIdea(
                    id = i.getString("id"),
                    title = i.optString("title"),
                    score = rice?.takeIf { it.has("score") }?.optDouble("score"),
                    blast = i.optString("blast"),
                    vote = i.optString("kevin_vote"),
                )
            }
            return BoardView(
                sent = snap != null,
                live = json.optBoolean("live"),
                ageS = if (json.isNull("age_s")) null else json.optLong("age_s"),
                laptopOn = json.optBoolean("laptop_on"),
                cards = cards,
                backlog = backlog,
                backlogTotal = maxOf(snap?.optInt("backlog_total") ?: 0, backlog.size),
            )
        }

        internal fun parseAction(a: JSONObject): ActionRecord = ActionRecord(
            id = a.getString("id"),
            kind = a.getString("kind"),
            sha = a.optString("sha"),
            tier = a.optString("tier"),
            reviewOkSha = a.optString("review_ok_sha"),
            ciGreen = a.optBoolean("ci_green"),
            flags = strings(a.optJSONArray("flags")),
            amountCents = if (a.isNull("amount_cents")) null else a.optLong("amount_cents"),
            payee = a.optString("payee"),
            purpose = a.optString("purpose"),
            recordSha256 = a.getString("record_sha256"),
            status = a.optString("status"),
            expiresAt = a.optString("expires_at"),
        )

        internal fun parseQueue(body: String): BoardQueue {
            val json = JSONObject(body)
            val counts = json.getJSONObject("counts")
            return BoardQueue(
                sessions = counts.optInt("sessions"),
                approvals = counts.optInt("approvals"),
                decisions = counts.optInt("decisions"),
                line = json.optString("line"),
                spoken = strings(json.optJSONArray("spoken")).filter { it.isNotBlank() },
                items = objects(json.optJSONArray("items")).map { it.optString("kind") to it.optString("key") },
            )
        }
    }
}
