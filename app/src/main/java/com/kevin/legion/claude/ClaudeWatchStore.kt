package com.kevin.legion.claude

import android.content.Context

/** A requested file the watcher fetches once the laptop has uploaded it. */
data class WatchedFile(val taskId: Int, val fileId: Int, val name: String) {
    fun key(): String = "$taskId:$fileId:$name"

    companion object {
        private const val PARTS = 3

        fun parse(key: String): WatchedFile? {
            val parts = key.split(":", limit = PARTS)
            val task = parts.getOrNull(0)?.toIntOrNull()
            val file = parts.getOrNull(1)?.toIntOrNull()
            return if (parts.size == PARTS && task != null && file != null) WatchedFile(task, file, parts[2]) else null
        }
    }
}

/**
 * What [ClaudeResultWatcher] is waiting on, and the one durable "stop telling me" flag.
 *
 * Only tasks THIS phone sent are watched (the engine's task list is the household's), so a result
 * is always anchored to something Kevin asked for (CLAUDE.md section 7, compulsion test (a)).
 * SharedPreferences, install-scoped, never synced: it holds task ids and file names, nothing more.
 */
interface ClaudeWatch {
    fun watchedTasks(): Set<Int>
    fun watchTask(id: Int)
    fun forgetTask(id: Int)
    fun watchedFiles(): Set<WatchedFile>
    fun watchFile(taskId: Int, fileId: Int, name: String)
    fun forgetFile(file: WatchedFile)
    fun silenced(): Boolean
    fun setSilenced(on: Boolean)
}

class ClaudeWatchStore(context: Context) : ClaudeWatch {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun watchedTasks(): Set<Int> =
        prefs.getStringSet(KEY_TASKS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    @Synchronized
    override fun watchTask(id: Int) = putTasks(watchedTasks() + id)

    @Synchronized
    override fun forgetTask(id: Int) = putTasks(watchedTasks() - id)

    override fun watchedFiles(): Set<WatchedFile> =
        prefs.getStringSet(KEY_FILES, emptySet()).orEmpty().mapNotNull(WatchedFile::parse).toSet()

    @Synchronized
    override fun watchFile(taskId: Int, fileId: Int, name: String) =
        putFiles(watchedFiles() + WatchedFile(taskId, fileId, name))

    @Synchronized
    override fun forgetFile(file: WatchedFile) = putFiles(watchedFiles() - file)

    override fun silenced(): Boolean = prefs.getBoolean(KEY_SILENCED, false)

    override fun setSilenced(on: Boolean) {
        prefs.edit().putBoolean(KEY_SILENCED, on).apply()
    }

    private fun putTasks(ids: Set<Int>) {
        prefs.edit().putStringSet(KEY_TASKS, ids.map { it.toString() }.toSet()).apply()
    }

    private fun putFiles(files: Set<WatchedFile>) {
        prefs.edit().putStringSet(KEY_FILES, files.map { it.key() }.toSet()).apply()
    }

    private companion object {
        const val PREFS = "claude_session"
        const val KEY_TASKS = "watched_tasks"
        const val KEY_FILES = "watched_files"
        const val KEY_SILENCED = "results_silenced"
    }
}
