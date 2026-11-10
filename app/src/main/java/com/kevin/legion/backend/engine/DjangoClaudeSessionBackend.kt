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
import com.kevin.legion.claude.ClaudeWorkflow
import com.kevin.legion.claude.ClaudeWorkflowEvery
import com.kevin.legion.claude.ClaudeWorkflowName
import com.kevin.legion.claude.ClaudeWorkflowParam
import com.kevin.legion.claude.ClaudeWorkflowRun
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val CLAUDE_ROOT = "/api/claude/"

private val claudeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

@Serializable
private data class FileRow(
    val id: Int,
    val name: String,
    val size: Long? = null,
    val uploaded: Boolean = false,
    val requested: Boolean = false,
    @SerialName("content_type") val contentType: String = "",
    val received: Boolean = false,
    val gone: Boolean = false,
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
    val kind: String = ClaudeTask.KIND_ASK,
    val workflow: String? = null,
    val params: String? = null,
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
        files = files.map {
            ClaudeTaskFile(it.id, it.name, it.size, it.uploaded, it.requested, it.contentType, it.received, it.gone)
        },
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
        kind = kind,
        workflow = workflow,
        params = params,
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
    val workflows: List<WorkflowNameRow> = emptyList(),
)

@Serializable
private data class WorkflowNameRow(val name: String, val says: String = "")

@Serializable
private data class WorkflowParamRow(val name: String, val says: String = "", val required: Boolean = false)

@Serializable
private data class WorkflowEveryRow(
    val hours: Int,
    @SerialName("idle_minutes") val idleMinutes: Int? = null,
    @SerialName("force_after_hours") val forceAfterHours: Int? = null,
)

@Serializable
private data class WorkflowRunRow(
    val task: Int,
    val status: String,
    val origin: String = ClaudeTaskDraft.ORIGIN_NAMED,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/** One row of `GET workflows/` (worker-workflows ticket 03). */
@Serializable
private data class WorkflowRow(
    val machine: String,
    @SerialName("machine_state") val machineState: String = ClaudeMachine.STATE_OFFLINE,
    val name: String,
    val spoken: String = "",
    val says: String = "",
    val params: List<WorkflowParamRow> = emptyList(),
    val asks: List<String> = emptyList(),
    val deliver: String? = null,
    val every: WorkflowEveryRow? = null,
    @SerialName("last_run") val lastRun: WorkflowRunRow? = null,
    val waiting: Boolean = false,
    @SerialName("schedule_refused") val scheduleRefused: String? = null,
    val due: Boolean = false,
) {
    fun toWorkflow() = ClaudeWorkflow(
        machine = machine,
        machineState = machineState,
        name = name,
        spoken = spoken.ifBlank { name.replace("-", " ") },
        says = says,
        params = params.map { ClaudeWorkflowParam(it.name, it.says, it.required) },
        asks = asks,
        deliver = deliver,
        every = every?.let { ClaudeWorkflowEvery(it.hours, it.idleMinutes, it.forceAfterHours) },
        lastRun = lastRun?.let {
            ClaudeWorkflowRun(it.task, it.status, it.origin, instant(it.createdAt), instant(it.finishedAt))
        },
        waiting = waiting,
        scheduleRefused = scheduleRefused,
        due = due,
    )
}

@Serializable
private data class WorkflowRunWrite(val machine: String?, val name: String, val params: String?)

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
private data class CreatedBody(
    val id: Int,
    val status: String,
    val machine: MachineRefRow? = null,
    val workflow: String? = null,
    val asks: List<String> = emptyList(),
) {
    fun toCreated() = ClaudeCreated(
        id,
        status,
        machine?.let { ClaudeMachineRef(it.name, it.state, instant(it.lastSeenAt)) },
        workflow,
        asks,
    )
}

@Serializable
private data class CreateWrite(
    val request: String,
    val machine: String?,
    val parent: Int?,
    val origin: String,
    val deliver: String?,
    val confirms: Boolean,
    val workflow: String? = null,
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
                        workflows = m.workflows.map { ClaudeWorkflowName(it.name, it.says) },
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
            CreateWrite(
                draft.request, draft.machine, draft.parent, draft.origin, draft.deliver, draft.confirms,
                draft.workflow,
            ),
        )
        return outcome(http.post(CLAUDE_ROOT + "tasks/", body)) {
            claudeJson.decodeFromString(CreatedBody.serializer(), it).toCreated()
        }
    }

    override suspend fun tasksWhere(origin: String, status: String, limit: Int): ClaudeOutcome<List<ClaudeTask>> =
        outcome(
            http.get(
                CLAUDE_ROOT + "tasks/",
                mapOf("origin" to origin, "status" to status, "limit" to limit.toString()),
            ),
        ) { body ->
            claudeJson.decodeFromString(ListSerializer(TaskRow.serializer()), body).map { it.toTask(::absolute) }
        }

    override suspend fun workflows(): ClaudeOutcome<List<ClaudeWorkflow>> =
        outcome(http.get(CLAUDE_ROOT + "workflows/")) { body ->
            claudeJson.decodeFromString(ListSerializer(WorkflowRow.serializer()), body).map { it.toWorkflow() }
        }

    override suspend fun runWorkflow(machine: String?, name: String, params: String?): ClaudeOutcome<ClaudeCreated> {
        val body = claudeJson.encodeToString(WorkflowRunWrite.serializer(), WorkflowRunWrite(machine, name, params))
        return outcome(http.post(CLAUDE_ROOT + "workflows/run/", body)) {
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

    override suspend fun received(taskId: Int, fileId: Int): ClaudeOutcome<Unit> =
        outcome(http.post(CLAUDE_ROOT + "tasks/$taskId/files/$fileId/received/", "{}")) { }

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
            is EngineFailure.Refused -> ClaudeOutcome.Refused(refusal(failure.body), failure.status)
            is EngineFailure.Malformed -> ClaudeOutcome.Unconfirmed(failure.sentence)
            null -> ClaudeOutcome.Unconfirmed(error?.message ?: "unknown error")
        }

    /**
     * The engine's refusal in its own words. A workflow miss is `{detail, workflows}` and its detail
     * already names the workflows there are, so the detail alone is said, not the names twice.
     */
    private fun refusal(body: String): String =
        runCatching {
            ((claudeJson.parseToJsonElement(body) as? JsonObject)?.get("detail") as? JsonPrimitive)?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: engineRefusalSentence(body)
}
