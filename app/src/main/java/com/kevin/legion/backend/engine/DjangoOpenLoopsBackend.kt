package com.kevin.legion.backend.engine

import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.openloops.Listing
import com.kevin.legion.openloops.OpenLoopsBrief
import com.kevin.legion.openloops.OpenLoopsBackend
import com.kevin.legion.openloops.Proposal
import com.kevin.legion.openloops.Tracked
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

private const val PATH = "/api/open-loops/"

/** `OpenLoopProposalSerializer` in `server/open_loops/views.py`. No time field exists on the wire. */
@Serializable
private data class ProposalRow(
    val id: String,
    val title: String,
    @SerialName("due_date") val dueDate: String? = null,
)

/** `OpenLoopListingSerializer`. */
@Serializable
private data class ListingRow(
    val on: Boolean = false,
    val proposing: Boolean = true,
    val proposals: List<ProposalRow> = emptyList(),
) {
    fun toListing() = Listing(
        on = on,
        proposing = proposing,
        proposals = proposals.map { Proposal(it.id, it.title, it.dueDate?.let(LocalDate::parse)) },
    )
}

@Serializable
private data class TrackedRow(@SerialName("task_kind") val taskKind: String = "", val words: String = "")

@Serializable
private data class WordedRow(val words: String = "")

/** [OpenLoopsBackend] over `/api/open-loops/` (ADR 0085). Failures become [MemoryOutcome]s as memory's do. */
class DjangoOpenLoopsBackend(private val http: EngineHttp) : OpenLoopsBackend {

    override suspend fun list(): MemoryOutcome<Listing> =
        loopsOutcome(http.get(PATH)) { memoryJson.decodeFromString(ListingRow.serializer(), it).toListing() }

    override suspend fun offer(): MemoryOutcome<Listing> =
        loopsOutcome(http.post(PATH + "offer", "{}")) {
            memoryJson.decodeFromString(ListingRow.serializer(), it).toListing()
        }

    override suspend fun track(id: String, dueDate: LocalDate?): MemoryOutcome<Tracked> {
        val body = buildJsonObject { dueDate?.let { put("due_date", it.toString()) } }.toString()
        return loopsOutcome(http.post("$PATH$id/track", body)) {
            memoryJson.decodeFromString(TrackedRow.serializer(), it).let { row -> Tracked(row.taskKind, row.words) }
        }
    }

    override suspend fun dismiss(id: String): MemoryOutcome<String> =
        loopsOutcome(http.post("$PATH$id/dismiss", "{}")) {
            memoryJson.decodeFromString(WordedRow.serializer(), it).words
        }

    override suspend fun setProposing(on: Boolean): MemoryOutcome<String> =
        loopsOutcome(http.put(PATH + "settings", buildJsonObject { put("proposing", on) }.toString())) {
            memoryJson.decodeFromString(WordedRow.serializer(), it).words
        }
}

/**
 * [memoryOutcome], except that an engine from before open loops (no `/api/open-loops/` route, so
 * Django's own 404 page) is said in words: [OpenLoopsBrief.NOT_ON_ENGINE], never its HTML.
 */
private fun <T> loopsOutcome(result: Result<EngineOk>, decode: (String) -> T): MemoryOutcome<T> =
    if (engineRouteMissing(result)) {
        MemoryOutcome.Refused(OpenLoopsBrief.NOT_ON_ENGINE, HTTP_NOT_FOUND)
    } else {
        memoryOutcome(result, decode)
    }
