package com.kevin.legion.claude

import java.time.Instant

/** A [ClaudeSessionBackend] that answers from fields and records every write. */
class FakeClaudeBackend : ClaudeSessionBackend {
    var statusOut: ClaudeOutcome<ClaudeStatus> = ClaudeOutcome.Ok(ClaudeStatus(emptyList(), emptyList()))
    var tasksOut: ClaudeOutcome<List<ClaudeTask>> = ClaudeOutcome.Ok(emptyList())
    val taskById = mutableMapOf<Int, ClaudeTask>()
    var createOut: (ClaudeTaskDraft) -> ClaudeOutcome<ClaudeCreated> = { d ->
        ClaudeOutcome.Ok(ClaudeCreated(41, ClaudeTask.QUEUED, d.machine?.let { ClaudeMachineRef(it, "idle", NOW) }))
    }
    var downloadOut: ClaudeOutcome<ByteArray> = ClaudeOutcome.Ok(byteArrayOf(1, 2, 3))

    val created = mutableListOf<ClaudeTaskDraft>()
    val assigned = mutableListOf<Pair<Int, String>>()
    val fileRequests = mutableListOf<Pair<Int, Int>>()
    val downloads = mutableListOf<Pair<Int, Int>>()

    override suspend fun status() = statusOut
    override suspend fun tasks(limit: Int) = tasksOut
    override suspend fun task(id: Int): ClaudeOutcome<ClaudeTask> =
        taskById[id]?.let { ClaudeOutcome.Ok(it) } ?: ClaudeOutcome.Refused("There is no task $id.", 404)

    override suspend fun create(draft: ClaudeTaskDraft): ClaudeOutcome<ClaudeCreated> {
        created += draft
        return createOut(draft)
    }

    override suspend fun assign(taskId: Int, machine: String): ClaudeOutcome<ClaudeCreated> {
        assigned += taskId to machine
        return ClaudeOutcome.Ok(ClaudeCreated(taskId, ClaudeTask.QUEUED, ClaudeMachineRef(machine, "idle", NOW)))
    }

    override suspend fun sendFile(taskId: Int, fileId: Int): ClaudeOutcome<ClaudeFileRequest> {
        fileRequests += taskId to fileId
        return ClaudeOutcome.Ok(ClaudeFileRequest(fileId, requested = true, uploaded = false, detail = "Requested."))
    }

    override suspend fun download(taskId: Int, fileId: Int): ClaudeOutcome<ByteArray> {
        downloads += taskId to fileId
        return downloadOut
    }

    fun machines(vararg m: ClaudeMachine) {
        statusOut = ClaudeOutcome.Ok(ClaudeStatus(m.toList(), emptyList()))
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-11-01T20:00:00Z")

        fun machine(
            name: String,
            state: String = "idle",
            scope: String = "full",
            lastSeen: Instant? = NOW,
            running: List<Int> = emptyList(),
            progress: Map<Int, String> = emptyMap(),
        ) = ClaudeMachine(name, scope, state, lastSeen, running, progress)

        fun task(
            id: Int,
            status: String,
            machine: String? = "Alienware",
            summary: String? = null,
            question: String? = null,
            files: List<ClaudeTaskFile> = emptyList(),
            progress: String? = null,
            request: String = "find the AW4 wiring PDF in my downloads",
        ) = ClaudeTask(
            id = id, request = request, machine = machine, status = status, summary = summary,
            question = question, createdAt = NOW, finishedAt = null, files = files, parent = null,
            origin = "named", deliver = null, confirms = false, progress = progress, progressAt = null,
        )
    }
}

/** An in-memory [ClaudeWatch]. */
class FakeWatch : ClaudeWatch {
    val tasks = mutableSetOf<Int>()
    val files = mutableSetOf<WatchedFile>()
    var quiet = false
    override fun watchedTasks() = tasks.toSet()
    override fun watchTask(id: Int) { tasks += id }
    override fun forgetTask(id: Int) { tasks -= id }
    override fun watchedFiles() = files.toSet()
    override fun watchFile(taskId: Int, fileId: Int, name: String) { files += WatchedFile(taskId, fileId, name) }
    override fun forgetFile(file: WatchedFile) { files -= file }
    override fun silenced() = quiet
    override fun setSilenced(on: Boolean) { quiet = on }
}
