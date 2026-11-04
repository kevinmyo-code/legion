package com.kevin.legion.backend.engine

import com.kevin.legion.claude.ClaudeCodeSession
import com.kevin.legion.claude.ClaudeCreated
import com.kevin.legion.claude.ClaudeFileRequest
import com.kevin.legion.claude.ClaudeMachine
import com.kevin.legion.claude.ClaudeMachineRef
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudePage
import com.kevin.legion.claude.ClaudeSessionBackend
import com.kevin.legion.claude.ClaudeStatus
import com.kevin.legion.claude.ClaudeStep
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeTaskFile
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val CLAUDE_ROOT = "/api/claude/"

private val claudeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

@Serializable
private data class FileRow(
    val id: Int,
    val name: String,
    val size: Long? = null,
    val uploaded: Boolean = false,
    val requested: Boolean = false,
)

@Serializable
private data class ArtifactRow(
    val id: Int,
    val filename: String,
    val title: String = "",
    val size: Long? = null,
    val url: String,
)

@Serializable
private data class StepRow(val at: String? = null, val text: String = "")

@Serializable
private data class TaskRow(
    val id: Int,
    val request: String,
    val machine: String? = null,
    val status: String,
    val summary: String? = null,
    val question: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val files: List<FileRow> = emptyList(),
    val parent: Int? = null,
    val origin: String = ClaudeTaskDraft.ORIGIN_NAMED,
    val deliver: String? = null,
    val confirms: Boolean = false,
    val progress: String? = null,
    @SerialName("progress_at") val progressAt: String? = null,
    val artifacts: List<ArtifactRow> = emptyList(),
    val steps: List<StepRow> = emptyList(),
) {
    /** [resolve] turns the engine's relative page link into one the browser can open. */
    fun toTask(resolve: (String) -> String = { it }) = ClaudeTask(
        id = id,
        request = request,
        machine = machine,
        status = status,
        summary = summary,
        question = question,
        createdAt = instant(createdAt),
        finishedAt = instant(finishedAt),
        files = files.map { ClaudeTaskFile(it.id, it.name, it.size, it.uploaded, it.requested) },
        parent = parent,
        origin = origin,
        deliver = deliver,
        confirms = confirms,
        progress = progress,
        progressAt = instant(progressAt),
        pages = artifacts.map {
            ClaudePage(it.id, it.filename, it.title.ifBlank { it.filename }, it.size, resolve(it.url))
        },
        steps = steps.filter { it.text.isNotBlank() }.map { ClaudeStep(instant(it.at), it.text) },
    )
}

@Serializable
private data class ProgressRow(val task: Int, val progress: String? = null)

@Serializable
private data class MachineRow(
    val name: String,
    val scope: String = "full",
    val state: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val running: List<Int> = emptyList(),
    val progress: List<ProgressRow> = emptyList(),
)

@Serializable
private data class SessionRow(
    val machine: String,
    val project: String = "",
    val state: String,
    @SerialName("last_prompt_summary") val lastPromptSummary: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
private data class StatusBody(
    val machines: List<MachineRow> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
)

@Serializable
private data class MachineRefRow(
    val name: String,
    val state: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
private data class CreatedBody(val id: Int, val status: String, val machine: MachineRefRow? = null) {
    fun toCreated() =
        ClaudeCreated(id, status, machine?.let { ClaudeMachineRef(it.name, it.state, instant(it.lastSeenAt)) })
}

@Serializable
private data class CreateWrite(
    val request: String,
    val machine: String?,
    val parent: Int?,
    val origin: String,
    val deliver: String?,
    val confirms: Boolean,
)

@Serializable
private data class AssignWrite(val machine: String)

@Serializable
private data class SendFileWrite(@SerialName("file_id") val fileId: Int)

@Serializable
private data class SendFileBody(
    @SerialName("file_id") val fileId: Int,
    val requested: Boolean = false,
    val uploaded: Boolean = false,
    val detail: String = "",
)

/** DRF writes `2026-11-01T14:02:11.123456Z` or with an offset; both parse as an offset date-time. */
private fun instant(text: String?): Instant? = text?.let { OffsetDateTime.parse(it).toInstant() }

/**
 * [ClaudeSessionBackend] over the engine's `/api/claude/` routes (`server/claude_session/`,
 * claude-session ticket 03, ADR 0067), with the device token [EngineHttp] attaches.
 *
 * **This class is where an [EngineHttp] failure becomes one of [ClaudeOutcome]'s four honest
 * states**, the same posture [DjangoPurchasesBackend] takes: unreachable stays unreachable, the
 * engine's 4xx is a refusal in its own words with its status kept, and a 2xx that does not decode is
 * unconfirmed (a task may have been created). Every route keeps its trailing slash, as
 * `server/claude_session/urls.py` declares them.
 *
 * **A 403 arrives as [EngineFailure.Unauthorized]** ([EngineHttp] reads 401 and 403 alike). The one
 * 403 this API sends on purpose is send-file for a `listed` machine, which the controller never
 * asks for, so a 403 here does mean the token.
 */
class DjangoClaudeSessionBackend(private val http: EngineHttp) : ClaudeSessionBackend {

    override suspend fun status(): ClaudeOutcome<ClaudeStatus> =
        outcome(http.get(CLAUDE_ROOT + "status/")) { body ->
            val parsed = claudeJson.decodeFromString(StatusBody.serializer(), body)
            ClaudeStatus(
                machines = parsed.machines.map { m ->
                    ClaudeMachine(
                        name = m.name,
                        scope = m.scope,
                        state = m.state,
                        lastSeenAt = instant(m.lastSeenAt),
                        running = m.running,
                        progress = m.progress.mapNotNull { p -> p.progress?.let { p.task to it } }.toMap(),
                    )
                },
                sessions = parsed.sessions.map {
                    ClaudeCodeSession(it.machine, it.project, it.state, it.lastPromptSummary, instant(it.lastSeenAt))
                },
            )
        }

    override suspend fun tasks(limit: Int): ClaudeOutcome<List<ClaudeTask>> =
        outcome(http.get(CLAUDE_ROOT + "tasks/", mapOf("limit" to limit.toString()))) { body ->
            claudeJson.decodeFromString(ListSerializer(TaskRow.serializer()), body).map { it.toTask(::absolute) }
        }

    override suspend fun task(id: Int): ClaudeOutcome<ClaudeTask> =
        outcome(http.get(CLAUDE_ROOT + "tasks/$id/")) {
            claudeJson.decodeFromString(TaskRow.serializer(), it).toTask(::absolute)
        }

    /** The engine serves page links relative to itself, like the recruiter assets. */
    private fun absolute(url: String): String =
        if (url.startsWith("https://") || url.startsWith("http://")) url else http.absoluteUrl(url) ?: url

    override suspend fun create(draft: ClaudeTaskDraft): ClaudeOutcome<ClaudeCreated> {
        val body = claudeJson.encodeToString(
            CreateWrite.serializer(),
            CreateWrite(draft.request, draft.machine, draft.parent, draft.origin, draft.deliver, draft.confirms),
        )
        return outcome(http.post(CLAUDE_ROOT + "tasks/", body)) {
            claudeJson.decodeFromString(CreatedBody.serializer(), it).toCreated()
        }
    }

    override suspend fun assign(taskId: Int, machine: String): ClaudeOutcome<ClaudeCreated> {
        val body = claudeJson.encodeToString(AssignWrite.serializer(), AssignWrite(machine))
        return outcome(http.post(CLAUDE_ROOT + "tasks/$taskId/assign/", body)) {
            claudeJson.decodeFromString(CreatedBody.serializer(), it).toCreated()
        }
    }

    override suspend fun sendFile(taskId: Int, fileId: Int): ClaudeOutcome<ClaudeFileRequest> {
        val body = claudeJson.encodeToString(SendFileWrite.serializer(), SendFileWrite(fileId))
        return outcome(http.post(CLAUDE_ROOT + "tasks/$taskId/send-file/", body)) {
            val parsed = claudeJson.decodeFromString(SendFileBody.serializer(), it)
            ClaudeFileRequest(parsed.fileId, parsed.requested, parsed.uploaded, parsed.detail)
        }
    }

    override suspend fun download(taskId: Int, fileId: Int): ClaudeOutcome<ByteArray> {
        val result = http.getBytes(CLAUDE_ROOT + "tasks/$taskId/files/$fileId/")
        return if (result.isSuccess) {
            ClaudeOutcome.Ok(result.getOrThrow().bytes)
        } else {
            failed(result.exceptionOrNull())
        }
    }

    private fun <T> outcome(result: Result<EngineOk>, decode: (String) -> T): ClaudeOutcome<T> {
        if (result.isFailure) return failed(result.exceptionOrNull())
        return try {
            ClaudeOutcome.Ok(decode(result.getOrThrow().body))
        } catch (e: SerializationException) {
            ClaudeOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        } catch (e: DateTimeParseException) {
            ClaudeOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ClaudeOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        }
    }

    private fun failed(error: Throwable?): ClaudeOutcome<Nothing> =
        when (val failure = (error as? EngineHttpException)?.failure) {
            is EngineFailure.Unreachable -> ClaudeOutcome.Unreachable(failure.message)
            is EngineFailure.Unauthorized -> ClaudeOutcome.Refused(failure.sentence)
            is EngineFailure.Refused -> ClaudeOutcome.Refused(engineRefusalSentence(failure.body), failure.status)
            is EngineFailure.Malformed -> ClaudeOutcome.Unconfirmed(failure.sentence)
            null -> ClaudeOutcome.Unconfirmed(error?.message ?: "unknown error")
        }
}
