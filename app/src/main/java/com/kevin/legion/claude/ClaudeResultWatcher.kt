package com.kevin.legion.claude

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of one announcement. [RETRY] leaves the task watched for the next poll. */
enum class ClaudeDelivery { DELIVERED, RETRY }

/**
 * Watches the tasks this phone sent and says each result once (claude-session ticket 05,
 * "Results"), and fetches a file Kevin asked for once the laptop has uploaded it.
 *
 * **The trigger is a poll, not a push.** `server/push/` is Web Push for browsers; nothing reaches
 * the phone, so this runs on the two loops the Tetris invites already use (`ClaudeResultPolling`).
 * It asks the engine nothing while no task or file is watched, so an idle phone costs nothing.
 *
 * **Compulsion test (CLAUDE.md section 7):** (a) every line is anchored to a task Kevin sent from
 * this phone and quotes the engine's own row; (b) it is actionable (a result, a question waiting
 * on his yes, a file to take or leave); (c) it never speaks of his absence or engagement; (d)
 * [ClaudeWatch.silenced] stops every announcement in one instruction (the screen's switch, or
 * "stop telling me about Claude tasks" through the tool), and the result still waits on the screen.
 */
class ClaudeResultWatcher(
    private val controller: () -> ClaudeSessionController?,
    private val watch: ClaudeWatch,
    private val announce: suspend (taskId: Int, text: String) -> ClaudeDelivery,
    private val saveFile: suspend (name: String, bytes: ByteArray) -> Boolean,
) {
    private val lock = Mutex()

    /** One pass. Returns how many announcements were handed on. */
    suspend fun pollOnce(): Int = lock.withLock { poll() }

    @Suppress("ReturnCount") // guard clauses: nothing watched, no engine, unreadable
    private suspend fun poll(): Int {
        val tasks = watch.watchedTasks()
        val files = watch.watchedFiles()
        if (tasks.isEmpty() && files.isEmpty()) return 0
        val c = controller() ?: return 0
        val overview = (c.overview(LIMIT) as? ClaudeOutcome.Ok)?.value ?: return 0
        val byId = overview.tasks.associateBy { it.id }.toMutableMap()
        var said = 0
        for (id in tasks) {
            val task = byId[id] ?: fetch(c, id)
            if (task != null) byId[id] = task
            if (task != null && settle(c, overview, task)) said++
        }
        for (file in files) {
            if (fetchFile(c, file, byId[file.taskId] ?: fetch(c, file.taskId))) said++
        }
        return said
    }

    /** True when a finished task was announced. Silenced: forgotten unsaid. Not finished: kept. */
    private suspend fun settle(c: ClaudeSessionController, overview: ClaudeOverview, task: ClaudeTask): Boolean {
        if (!task.isFinished) return false
        val delivered = !watch.silenced() &&
            announce(task.id, c.wording.result(task, overview.summariesOnly(task))) == ClaudeDelivery.DELIVERED
        if (delivered || watch.silenced()) watch.forgetTask(task.id)
        return delivered
    }

    /** A watched task older than the list: read it alone. A 404 forgets it. */
    private suspend fun fetch(c: ClaudeSessionController, id: Int): ClaudeTask? =
        when (val out = c.task(id)) {
            is ClaudeOutcome.Ok -> out.value
            is ClaudeOutcome.Refused -> {
                if (out.status == NOT_FOUND) {
                    watch.forgetTask(id)
                    watch.watchedFiles().filter { it.taskId == id }.forEach(watch::forgetFile)
                }
                null
            }
            else -> null
        }

    /** True when it announced. The file is forgotten once saved or once it can no longer come. */
    @Suppress("ReturnCount") // one guard per reason the file is not ready yet
    private suspend fun fetchFile(c: ClaudeSessionController, file: WatchedFile, task: ClaudeTask?): Boolean {
        val row = task?.files?.firstOrNull { it.id == file.fileId }
        if (task == null) return false
        if (row == null) {
            watch.forgetFile(file)
            return false
        }
        if (!row.uploaded) return false
        val bytes = (c.download(file.taskId, file.fileId) as? ClaudeOutcome.Ok)?.value ?: return false
        val saved = saveFile(file.name, bytes)
        watch.forgetFile(file)
        if (watch.silenced()) return false
        val text = if (saved) c.wording.fileSaved(file.name) else c.wording.fileSaveFailed(file.name)
        announce(file.taskId, text)
        return true
    }

    /** Polls forever at [intervalMs] until cancelled; a failed poll never ends the loop. */
    @Suppress("TooGenericExceptionCaught") // a poll must never end the loop; cancellation is rethrown first
    suspend fun run(intervalMs: Long = POLL_INTERVAL_MS, onCrash: (Throwable) -> Unit = {}) {
        while (coroutineContext.isActive) {
            try {
                pollOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onCrash(e)
            }
            delay(intervalMs)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
        private const val LIMIT = 50
        private const val NOT_FOUND = 404
    }
}
