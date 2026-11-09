package com.kevin.legion.backend.engine

import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryReviewBackend
import com.kevin.legion.memory.ReviewBefore
import com.kevin.legion.memory.ReviewChange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val REVIEW_PATH = "/api/member-memories/review/"
private const val LIST_PATH = "/api/member-memories/"

/** `ReviewBeforeSerializer` in `server/member_memory/review_views.py`. */
@Serializable
private data class ReviewBeforeRow(val id: String, val text: String? = null)

/** `ReviewChangeSerializer` in `server/member_memory/review_views.py`. */
@Serializable
private data class ReviewChangeRow(
    val id: Long,
    val action: String,
    val status: String,
    val cluster: String,
    val before: List<ReviewBeforeRow> = emptyList(),
    @SerialName("after_words") val afterWords: String = "",
    val reason: String = "",
    @SerialName("action_words") val actionWords: String = "",
    @SerialName("evidence_date") val evidenceDate: String? = null,
) {
    fun toChange() = ReviewChange(
        id = id,
        action = action,
        status = status,
        cluster = cluster,
        before = before.map { ReviewBefore(it.id, it.text) },
        afterWords = afterWords,
        reason = reason,
        actionWords = actionWords,
        evidenceDate = evidenceDate,
    )
}

@Serializable
private data class ReviewChangeList(val results: List<ReviewChangeRow> = emptyList())

/** Every decision answers with `words` (`ReviewDecisionSerializer`, `ReviewClusterSerializer`). */
@Serializable
private data class Worded(val words: String = "")

/**
 * [MemoryReviewBackend] over `/api/member-memories/review/` (memory-review ticket 08). Failures
 * become [MemoryOutcome]s exactly as the memory routes' do ([memoryOutcome]); a 409 ("those
 * memories changed since the review") is a [MemoryOutcome.Refused] with its status kept.
 */
class DjangoMemoryReviewBackend(private val http: EngineHttp) : MemoryReviewBackend {

    override suspend fun pending(): MemoryOutcome<List<ReviewChange>> =
        memoryOutcome(http.get(REVIEW_PATH + "changes", mapOf("status" to "pending"))) { body ->
            memoryJson.decodeFromString(ReviewChangeList.serializer(), body).results.map { it.toChange() }
        }

    override suspend fun accept(id: Long): MemoryOutcome<String> = decide("changes/$id/accept")

    override suspend fun dismiss(id: Long): MemoryOutcome<String> = decide("changes/$id/dismiss")

    override suspend fun revert(id: Long): MemoryOutcome<String> = decide("changes/$id/revert")

    override suspend fun acceptCluster(cluster: String): MemoryOutcome<String> =
        decide("clusters/$cluster/accept")

    override suspend fun retired(limit: Int): MemoryOutcome<List<MemberMemory>> =
        memoryOutcome(
            http.get(LIST_PATH, mapOf("limit" to limit.toString(), "include_superseded" to "true")),
        ) { body ->
            memoryJson.decodeFromString(MemoryList.serializer(), body).results
                .filter { it.retiredReason != null }
                .map { it.toMemory() }
        }

    private suspend fun decide(path: String): MemoryOutcome<String> =
        memoryOutcome(http.post(REVIEW_PATH + path, "{}")) { body ->
            memoryJson.decodeFromString(Worded.serializer(), body).words
        }
}
