package com.kevin.legion.claude

import java.time.Duration
import java.time.Instant
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
 * It asks the engine nothing while no task or file is watched and the ongoing "Claude: N running"
 * notification is down ([ClaudeLiveWatch]), so an idle phone costs nothing. Each read it makes also
 * feeds that notification, which is how it follows a running task without a loop of its own.
 *
 * **Compulsion test (CLAUDE.md section 7):** (a) every line is anchored to a task Kevin sent from
 * this phone and quotes the engine's own row; (b) it is actionable (a result, a question waiting
 * on his yes, a file to take or leave); (c) it never speaks of his absence or engagement; (d)
 * [ClaudeWatch.silenced] stops every announcement in one instruction (the screen's switch, or
 * "stop telling me about Claude tasks" through the tool), and the result still waits on the screen.
 *
 * **Files to phone (ADR 0080).** A `deliver: files` task's requested files are fetched as soon as
 * each is on the engine, kept in app-private storage ([shelf]), and the engine told it may delete
 * its copy (`received`). Once all have come (or [FILES_WAIT] after the finish) [openFiles] opens
 * them on the screen if LEGION is in front, or posts the notification that opens them on a tap, and
 * the result is handed to [filesReady]. Silenced: the files still come and still open or notify
 * (Kevin asked for them by name); only the spoken result is not said.
 */
class ClaudeResultWatcher(
    private val controller: () -> ClaudeSessionController?,
    private val watch: ClaudeWatch,
    /** [text] is for the user; [hint] (may be blank) tells the model which call a yes makes. */
    private val announce: suspend (taskId: Int, text: String, hint: String) -> ClaudeDelivery,
    private val saveFile: suspend (name: String, bytes: ByteArray) -> Boolean,
    private val now: () -> Instant = Instant::now,
    /**
     * True while the ongoing "Claude: N running" notification is up ([ClaudeLiveWatch.active]): the
     * poll then keeps reading even with nothing of this phone's watched, until a read shows nothing
     * running and the notification comes down.
     */
    private val liveActive: () -> Boolean = { false },
    /** Every read this poll makes, for [ClaudeLiveWatch]; null when the read failed. */
    private val onOverview: (ClaudeOverview?) -> Unit = {},
    /** Where a files task's files are kept (ADR 0080). Null: files tasks are announced like any other. */
    private val shelf: ClaudeFileShelf? = null,
    /**
     * Opens task [taskId]'s kept files on the screen and returns true when LEGION was in front;
     * otherwise posts the notification that opens them on a tap and returns false.
     */
    private val openFiles: suspend (taskId: Int) -> Boolean = { false },
    /** The files result: said like any result, its notification opening the files when tapped. */
    private val filesReady: suspend (taskId: Int, text: String) -> ClaudeDelivery =
        { id, text -> announce(id, text, "") },
    /**
     * Rides every tick of [run], whatever this watcher found (ADR 0083: standing watches ask the
     * engine on this same loop at their own slower cadence, `WatchFiringPoller.maybePoll`). Its
     * failure never ends the loop or skips this watcher's own poll.
     */
    private val alsoEachTick: suspend () -> Unit = {},
) {
    private val lock = Mutex()

    /** Tasks whose files were already opened, so a "not now" retry does not open them twice. */
    private val openedFor = mutableSetOf<Int>()

    /** One pass. Returns how many announcements were handed on. */
    suspend fun pollOnce(): Int = lock.withLock { poll() }

    @Suppress("ReturnCount") // guard clauses: nothing watched, no engine, unreadable
    private suspend fun poll(): Int {
        val tasks = watch.watchedTasks()
        val files = watch.watchedFiles()
        if (tasks.isEmpty() && files.isEmpty() && !liveActive()) return 0
        val c = controller() ?: return 0
        val overview = (c.overview(LIMIT) as? ClaudeOutcome.Ok)?.value
        onOverview(overview)
        if (overview == null) return 0
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
    @Suppress("ReturnCount") // not finished, page still on its way, or the announcement
    private suspend fun settle(c: ClaudeSessionController, overview: ClaudeOverview, task: ClaudeTask): Boolean {
        if (!task.isFinished) return false
        // Ticket 09: the page goes up right after the finish. Wait for it a little rather than
        // announce "ready" before there is anything to open; past the wait, say it did not come.
        if (task.pagePending && !pageWaitOver(task)) return false
        // Re-read: a `claude_session` check may have read this result out (and forgotten the task)
        // while this poll was waiting on the engine. Saying it again is the double announcement.
        if (task.id !in watch.watchedTasks()) return false
        val shelf = shelf
        if (shelf != null && task.deliveredFiles.isNotEmpty()) return settleFiles(c, task, shelf)
        val only = overview.summariesOnly(task)
        val delivered = !watch.silenced() &&
            announce(task.id, c.wording.result(task, only), hint(task, only)) == ClaudeDelivery.DELIVERED
        if (delivered || watch.silenced()) watch.forgetTask(task.id)
        return delivered
    }

    /**
     * ADR 0080: fetch each delivered file that is up and not here yet, keep it, tell the engine to
     * delete its copy; then, once nothing more is coming, open and announce. True when announced.
     */
    @Suppress("ReturnCount") // still coming, silenced, or the announcement
    private suspend fun settleFiles(c: ClaudeSessionController, task: ClaudeTask, shelf: ClaudeFileShelf): Boolean {
        val wanted = task.deliveredFiles
        for (f in wanted) bring(c, task.id, f, shelf)
        val kept = wanted.filter { shelf.has(task.id, it.id) }
        val coming = wanted.filter { it !in kept && !it.gone }
        if (coming.isNotEmpty() && !filesWaitOver(task)) return false
        // Opened (or its notification posted) even when announcements are off: Kevin asked for
        // these by name, so this is the delivery, not a raise. Only the spoken line is silenced.
        val opened = kept.isNotEmpty() && (task.id in openedFor || openFiles(task.id))
        if (opened) openedFor += task.id
        if (watch.silenced()) {
            watch.forgetTask(task.id)
            openedFor -= task.id
            return false
        }
        val text = c.wording.filesDelivered(task, kept, wanted - kept.toSet(), opened)
        val delivered = filesReady(task.id, text) == ClaudeDelivery.DELIVERED
        if (delivered) {
            watch.forgetTask(task.id)
            openedFor -= task.id
        }
        return delivered
    }

    /**
     * One delivered file: fetched and kept if it is up and not here yet; then, once here (now, or
     * from an earlier poll whose receipt did not reach the engine), the engine told its copy may
     * go. A failed fetch or receipt is retried next poll; the engine's 24 h purge backs it.
     */
    private suspend fun bring(c: ClaudeSessionController, taskId: Int, f: ClaudeTaskFile, shelf: ClaudeFileShelf) {
        if (!shelf.has(taskId, f.id) && f.uploaded && !f.gone) {
            val bytes = (c.download(taskId, f.id) as? ClaudeOutcome.Ok)?.value
            if (bytes != null) shelf.keep(taskId, f.id, f.name, bytes)
        }
        if (shelf.has(taskId, f.id) && !f.received && !f.gone) c.received(taskId, f.id)
    }

    private fun filesWaitOver(task: ClaudeTask): Boolean {
        val finished = task.finishedAt ?: return true
        return Duration.between(finished, now()) >= FILES_WAIT
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
        announce(file.taskId, text, "")
        return true
    }

    private fun pageWaitOver(task: ClaudeTask): Boolean {
        val finished = task.finishedAt ?: return true
        return Duration.between(finished, now()) >= PAGE_WAIT
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** How long a finished task's result waits for its page to reach the engine. */
        val PAGE_WAIT: Duration = Duration.ofMinutes(3)

        /** How long a files task's result waits for its files (ADR 0080: up to 20, 25 MB each). */
        val FILES_WAIT: Duration = Duration.ofMinutes(10)

        /** The `claude_session` action a yes to "want me to open it?" makes. */
        const val OPEN_PAGE_ACTION = "open_page"
        private const val LIMIT = 50
        private const val NOT_FOUND = 404

        /**
         * For the model only, never the notification: which `claude_session` call the user's yes
         * makes. Blank when the result asks nothing, which is also what keeps the mic closed.
         */
        fun hint(task: ClaudeTask, summariesOnly: Boolean): String {
            val files = task.files.filterNot {
                it.uploaded || (task.wantsPage && it.isPage) || (task.wantsFiles && it.requested)
            }
            return when {
                task.needsConfirmation ->
                    "On a yes: claude_session answer, task_id ${task.id}, yes true."
                task.status == ClaudeTask.DONE && task.pages.isNotEmpty() ->
                    "On a yes: claude_session $OPEN_PAGE_ACTION, task_id ${task.id}."
                task.status == ClaudeTask.DONE && !summariesOnly && files.isNotEmpty() ->
                    "On a yes: claude_session send_file, task_id ${task.id}, " +
                        files.joinToString(" or ") { "file_id ${it.id} (${it.name})" } + "."
                else -> ""
            }
        }
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
            try {
                alsoEachTick()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onCrash(e)
            }
            delay(intervalMs)
        }
    }

}
