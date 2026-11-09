package com.kevin.legion.backend.engine

import com.kevin.legion.memory.LegacyMemoryRow
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.MemberMemoryBackend
import com.kevin.legion.memory.MemoryEdit
import com.kevin.legion.memory.MemoryOutcome
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MEMORIES_PATH = "/api/member-memories/"
private const val RECALL_PATH = "/api/member-memories/recall"
private const val IMPORT_PATH = "/api/member-memories/import"

private val memoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private fun parseMs(stamp: String): Long = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

/** One row as `member_memory/service.as_dict` renders it (`server/member_memory/views.py`). */
@Serializable
private data class MemberMemoryRow(
    val id: String,
    val text: String,
    val kind: String = "fact",
    val importance: Int = 5,
    @SerialName("decay_class") val decayClass: String = "durable",
    val source: String = "",
    val shared: Boolean = false,
    @SerialName("about_you") val aboutYou: Boolean = true,
    @SerialName("last_recalled_at") val lastRecalledAt: String? = null,
    @SerialName("from_conversation") val fromConversation: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    fun toMemory() = MemberMemory(
        id = id,
        text = text,
        kind = kind,
        importance = importance,
        decayClass = decayClass,
        source = source,
        shared = shared,
        aboutYou = aboutYou,
        createdAtMs = parseMs(createdAt),
        lastRecalledAtMs = lastRecalledAt?.let(::parseMs),
        fromConversation = fromConversation,
    )
}

@Serializable
private data class MemoryList(val results: List<MemberMemoryRow> = emptyList())

@Serializable
private data class CreateBody(val text: String, val shared: Boolean, val source: String)

@Serializable
private data class RecallBody(val query: String, val limit: Int)

/** `ImportRowSerializer` in `server/member_memory/views.py`. */
@Serializable
private data class ImportRow(
    val store: String,
    @SerialName("origin_guid") val originGuid: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int? = null,
    @SerialName("logged_at") val loggedAt: String,
)

@Serializable
private data class ImportBody(val rows: List<ImportRow>)

@Serializable
private data class ImportResult(val imported: Int)

/**
 * [MemberMemoryBackend] over the engine's `/api/member-memories/` routes (memory v2, ADR 0075), with
 * the device token [EngineHttp] attaches. **This class is where an [EngineHttp] failure becomes one
 * of [MemoryOutcome]'s four states**, the shape [DjangoPurchasesBackend] set: unreachable stays
 * unreachable, a 4xx is the engine's own sentence with its status, a 2xx that does not decode is
 * unconfirmed.
 *
 * A 403 reaches [EngineHttp] as [EngineFailure.Unauthorized], whose sentence says "sign in again";
 * memory's 403s are refusals in words (a recruiter visit, the work laptop's token), so the engine's
 * own `detail` is read back out of [EngineFailure.Unauthorized.body] when there is one.
 *
 * Paths: the collection root keeps its trailing slash, `recall` and `<id>` do not, matching
 * `server/member_memory/urls.py`; a wrong slash is a 404, not a redirect.
 */
class DjangoMemberMemoryBackend(private val http: EngineHttp) : MemberMemoryBackend {

    override suspend fun list(limit: Int): MemoryOutcome<List<MemberMemory>> =
        outcome(http.get(MEMORIES_PATH, mapOf("limit" to limit.toString()))) { body ->
            memoryJson.decodeFromString(MemoryList.serializer(), body).results.map { it.toMemory() }
        }

    override suspend fun recall(query: String, limit: Int): MemoryOutcome<List<MemberMemory>> {
        val body = memoryJson.encodeToString(RecallBody.serializer(), RecallBody(query, limit))
        return outcome(http.post(RECALL_PATH, body)) { reply ->
            memoryJson.decodeFromString(MemoryList.serializer(), reply).results.map { it.toMemory() }
        }
    }

    override suspend fun create(text: String, shared: Boolean, source: String): MemoryOutcome<MemberMemory> {
        val body = memoryJson.encodeToString(CreateBody.serializer(), CreateBody(text, shared, source))
        return outcome(http.post(MEMORIES_PATH, body)) {
            memoryJson.decodeFromString(MemberMemoryRow.serializer(), it).toMemory()
        }
    }

    override suspend fun update(id: String, edit: MemoryEdit): MemoryOutcome<MemberMemory> {
        val fields = buildMap {
            edit.text?.let { put("text", JsonPrimitive(it)) }
            edit.shared?.let { put("shared", JsonPrimitive(it)) }
        }
        return outcome(http.patch(MEMORIES_PATH + id, JsonObject(fields).toString())) {
            memoryJson.decodeFromString(MemberMemoryRow.serializer(), it).toMemory()
        }
    }

    override suspend fun delete(id: String): MemoryOutcome<Unit> {
        val result = http.delete(MEMORIES_PATH + id)
        // "Deleted for good" is said only on the 204 the engine answers a hard delete with
        // (`MemoryDetailView.delete`). Any other 2xx is a reply this class does not recognise.
        val status = result.getOrNull()?.status
        if (result.isSuccess && status != HTTP_NO_CONTENT) {
            return MemoryOutcome.Unconfirmed("The engine answered HTTP $status, not the 204 a delete gives.")
        }
        return outcome(result) { }
    }

    override suspend fun importLegacy(rows: List<LegacyMemoryRow>): MemoryOutcome<Int> {
        val body = ImportBody(
            rows.map {
                ImportRow(
                    store = it.store,
                    originGuid = it.originGuid,
                    text = it.text,
                    category = it.category,
                    source = it.source,
                    importance = it.importance,
                    loggedAt = Instant.ofEpochMilli(it.loggedAtMs).toString(),
                )
            },
        )
        return outcome(http.post(IMPORT_PATH, memoryJson.encodeToString(ImportBody.serializer(), body))) {
            memoryJson.decodeFromString(ImportResult.serializer(), it).imported
        }
    }

    private fun <T> outcome(result: Result<EngineOk>, decode: (String) -> T): MemoryOutcome<T> {
        val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
        return when {
            result.isSuccess -> try {
                MemoryOutcome.Ok(decode(result.getOrThrow().body))
            } catch (e: SerializationException) {
                MemoryOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            } catch (e: DateTimeParseException) {
                MemoryOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            } catch (e: IllegalArgumentException) {
                MemoryOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            }
            failure is EngineFailure.Unreachable -> MemoryOutcome.Unreachable(failure.message)
            failure is EngineFailure.Unauthorized -> MemoryOutcome.Refused(
                failure.body.takeIf { failure.status == HTTP_FORBIDDEN && it.isNotBlank() }
                    ?.let(::engineRefusalSentence) ?: failure.sentence,
                failure.status,
            )
            failure is EngineFailure.Refused ->
                MemoryOutcome.Refused(engineRefusalSentence(failure.body), failure.status)
            failure is EngineFailure.Malformed -> MemoryOutcome.Unconfirmed(failure.sentence)
            else -> MemoryOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
        }
    }

    private companion object {
        const val HTTP_NO_CONTENT = 204
        const val HTTP_FORBIDDEN = 403
    }
}
