package com.kevin.legion.claude

import java.time.Instant

/** A machine's spoken name and what the engine says about it (`GET status/`, ADR 0067). */
data class ClaudeMachine(
    val name: String,
    /** `full` (the Alienware) or `listed` (the work laptop: summaries only, never files). */
    val scope: String,
    /** `idle`, `busy` or `offline` (no heartbeat for 90 s), computed by the engine at read time. */
    val state: String,
    val lastSeenAt: Instant?,
    val running: List<Int>,
    /** The helper's live step for each running task, keyed by task id (status extras). */
    val progress: Map<Int, String>,
) {
    val isOnline: Boolean get() = state != STATE_OFFLINE
    val summariesOnly: Boolean get() = scope == SCOPE_LISTED

    companion object {
        const val STATE_OFFLINE = "offline"
        const val STATE_BUSY = "busy"
        const val SCOPE_LISTED = "listed"
    }
}

/** One interactive Claude Code session a machine reported (ruling 11). */
data class ClaudeCodeSession(
    val machine: String,
    val project: String,
    /** `waiting` means it is waiting on Kevin; the engine sorts those first. */
    val state: String,
    /** Null on the work laptop: its prompts never reach the engine (ruling 5). */
    val lastPromptSummary: String?,
    val lastSeenAt: Instant?,
) {
    val waitingOnYou: Boolean get() = state == STATE_WAITING

    companion object {
        const val STATE_WAITING = "waiting"
    }
}

data class ClaudeStatus(val machines: List<ClaudeMachine>, val sessions: List<ClaudeCodeSession>)

/** A file a task found on the laptop. Listed, not uploaded, until Kevin says yes (ruling 8). */
data class ClaudeTaskFile(
    val id: Int,
    val name: String,
    val size: Long?,
    val uploaded: Boolean,
    val requested: Boolean,
)

/** One task as `GET tasks/` and `GET tasks/{id}/` serve it. [machine] is the machine's name. */
data class ClaudeTask(
    val id: Int,
    val request: String,
    val machine: String?,
    val status: String,
    val summary: String?,
    val question: String?,
    val createdAt: Instant?,
    val finishedAt: Instant?,
    val files: List<ClaudeTaskFile>,
    val parent: Int?,
    val origin: String,
    val deliver: String?,
    val confirms: Boolean,
    val progress: String?,
    val progressAt: Instant?,
) {
    val isFinished: Boolean get() = status in FINISHED
    val needsConfirmation: Boolean get() = status == NEEDS_CONFIRMATION

    companion object {
        const val QUEUED = "queued"
        const val NEEDS_MACHINE = "needs_machine"
        const val RUNNING = "running"
        const val CANCEL_REQUESTED = "cancel_requested"
        const val DONE = "done"
        const val FAILED = "failed"
        const val NEEDS_CONFIRMATION = "needs_confirmation"
        const val CANCELLED = "cancelled"

        /** States after which nothing more happens to the task. A yes to a question is a NEW task. */
        val FINISHED = setOf(DONE, FAILED, NEEDS_CONFIRMATION, CANCELLED)
    }
}

/** What `POST tasks/` and `POST tasks/{id}/assign/` answer. [machine] null means unaddressed. */
data class ClaudeCreated(val id: Int, val status: String, val machine: ClaudeMachineRef?)

data class ClaudeMachineRef(val name: String, val state: String, val lastSeenAt: Instant?)

/** What `POST tasks/` takes. [deliver] is `email_self` only when Kevin named his own email. */
data class ClaudeTaskDraft(
    val request: String,
    val machine: String?,
    val parent: Int?,
    val origin: String,
    val deliver: String?,
    val confirms: Boolean,
) {
    companion object {
        const val ORIGIN_NAMED = "named"
        const val ORIGIN_OFFERED = "offered"
        const val DELIVER_EMAIL_SELF = "email_self"
    }
}

/** What `POST tasks/{id}/send-file/` answers. */
data class ClaudeFileRequest(val fileId: Int, val requested: Boolean, val uploaded: Boolean, val detail: String)

/**
 * What a call to the Claude session API did. Four states, because four different sentences follow
 * (the bought log's shape, `purchases/PurchasesBackend.kt`):
 *
 * - [Ok]: a 2xx came back and decoded. The ONLY state a send may be reported from.
 * - [Unreachable]: nothing was sent.
 * - [Refused]: the engine said no, in its own words; [status] tells a 404 from a 409.
 * - [Unconfirmed]: a 2xx whose body did not decode. A send MAY have landed.
 */
sealed interface ClaudeOutcome<out T> {
    data class Ok<T>(val value: T) : ClaudeOutcome<T>
    data class Unreachable(val sentence: String) : ClaudeOutcome<Nothing>
    data class Refused(val sentence: String, val status: Int = 0) : ClaudeOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : ClaudeOutcome<Nothing>
}

/** A failed outcome re-typed for a caller with a different success type. Never called on [ClaudeOutcome.Ok]. */
fun ClaudeOutcome<*>.asFailure(): ClaudeOutcome<Nothing> = when (this) {
    is ClaudeOutcome.Unreachable -> this
    is ClaudeOutcome.Refused -> this
    is ClaudeOutcome.Unconfirmed -> this
    is ClaudeOutcome.Ok -> error("asFailure called on an Ok outcome")
}

/**
 * The seam [ClaudeSessionController] talks through. The one implementation is
 * [com.kevin.legion.backend.engine.DjangoClaudeSessionBackend]; the interface exists because the
 * tests need a fake that is unreachable, refusing or ok on demand.
 */
interface ClaudeSessionBackend {
    suspend fun status(): ClaudeOutcome<ClaudeStatus>
    suspend fun tasks(limit: Int): ClaudeOutcome<List<ClaudeTask>>
    suspend fun task(id: Int): ClaudeOutcome<ClaudeTask>
    suspend fun create(draft: ClaudeTaskDraft): ClaudeOutcome<ClaudeCreated>
    suspend fun assign(taskId: Int, machine: String): ClaudeOutcome<ClaudeCreated>
    suspend fun sendFile(taskId: Int, fileId: Int): ClaudeOutcome<ClaudeFileRequest>

    /** `GET tasks/{id}/files/{file_id}/`: the bytes of a file the laptop uploaded. */
    suspend fun download(taskId: Int, fileId: Int): ClaudeOutcome<ByteArray>
}
