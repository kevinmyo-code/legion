package com.kevin.legion.backend.engine

import com.kevin.legion.memory.ChatSummary
import com.kevin.legion.memory.ChatTurn
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.RecentChatsSource
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val SUMMARIES_PATH = "/api/member-memories/summaries"
private const val TURNS_PATH = "/api/member-memories/turns"

/** One row as `member_memory/summaries.as_dict` renders it (issue #451). */
@Serializable
internal data class SummaryRow(
    val session: String,
    val text: String,
    @SerialName("ended_at") val endedAt: String,
)

@Serializable
internal data class SummaryList(val results: List<SummaryRow> = emptyList())

/** One row as `member_memory/turns.as_dict` renders it (issue #450). */
@Serializable
internal data class TurnRow(
    val session: String,
    val turn: Int,
    @SerialName("user_text") val userText: String = "",
    @SerialName("assistant_text") val assistantText: String = "",
    val tools: List<String> = emptyList(),
    @SerialName("spoken_at") val spokenAt: String,
)

@Serializable
internal data class TurnList(val results: List<TurnRow> = emptyList())

private fun ms(stamp: String): Long = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

/** `GET /api/member-memories/summaries` decoded. Throws on a body that is not that shape. */
internal fun decodeSummaries(body: String): List<ChatSummary> =
    memoryJson.decodeFromString(SummaryList.serializer(), body).results
        .map { ChatSummary(it.session, ms(it.endedAt), it.text) }

/** `GET /api/member-memories/turns` decoded. Throws on a body that is not that shape. */
internal fun decodeTurns(body: String): List<ChatTurn> =
    memoryJson.decodeFromString(TurnList.serializer(), body).results.map {
        ChatTurn(it.session, it.turn, it.userText, it.assistantText, it.tools.isNotEmpty(), ms(it.spokenAt))
    }

/**
 * [RecentChatsSource] over the engine's own summary and turn lists (issues #450 and #451), with the
 * phone's sign-in. Both routes read through `visible()` and the caller's own rows only, so another
 * member's chats never reach this phone. Any failure is null: the note is then left out, and the
 * chat starts without it.
 */
class DjangoRecentChatsSource(private val http: EngineHttp) : RecentChatsSource {
    override suspend fun summaries(limit: Int): List<ChatSummary>? =
        (memoryOutcome(http.get(SUMMARIES_PATH, mapOf("limit" to limit.toString())), ::decodeSummaries)
            as? MemoryOutcome.Ok)?.value

    override suspend fun turns(limit: Int): List<ChatTurn>? =
        (memoryOutcome(http.get(TURNS_PATH, mapOf("limit" to limit.toString())), ::decodeTurns)
            as? MemoryOutcome.Ok)?.value
}
