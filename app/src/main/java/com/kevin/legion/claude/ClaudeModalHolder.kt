package com.kevin.legion.claude

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The task the Claude task dialog should show. [seen] turns true the first time the dialog is
 * actually drawn; from then on only Kevin's Close takes it down (Kevin 2026-11-04: "until i close
 * the modal myself").
 */
data class ClaudeModalPending(val taskId: Int, val shownAt: Instant, val seen: Boolean)

/** Where the pending dialog survives process death. SharedPreferences in production. */
interface ClaudeModalMemory {
    fun load(): ClaudeModalPending?
    fun save(pending: ClaudeModalPending?)
}

/**
 * Which Claude task the dialog over the app shows, for the whole process (Kevin 2026-11-04: "when i
 * ask alfred to check something on claude ... pull up a pop up modal that shows the session status").
 * The `claude_session` voice tool calls [show] the moment the engine accepts a task; MainActivity
 * observes [pending], so the dialog opens over whichever screen is up.
 *
 * **One task at a time, the newest wins.** A second task started while the dialog is open replaces
 * the first; the first is still on the Claude session screen.
 *
 * **Not in the foreground when it started** (the screen off in the car): the dialog appears the next
 * time the app is opened, but only within [FRESH_FOR] of the send and only if Kevin has not closed it.
 * After that it is dropped unseen; a stale dialog popping up an hour later is noise, and the result
 * announcement and the Home line already carry the outcome. A dialog opened by hand from the header
 * sheet ([open]) is seen at once and never expires.
 *
 * A plain process singleton ([get]), the [ClaudeLiveWatch] shape: Hilt is not wired into this build.
 */
class ClaudeModalHolder(
    private val memory: ClaudeModalMemory,
    private val now: () -> Instant = Instant::now,
) {
    // Restored across process death only while fresh, seen or not: a dialog left open yesterday is
    // not resurrected by a cold start today.
    private val _pending = MutableStateFlow(memory.load()?.takeIf { fresh(it) })

    val pending: StateFlow<ClaudeModalPending?> = _pending.asStateFlow()

    /** The voice tool's door: a task the engine just accepted. */
    @Synchronized
    fun show(taskId: Int) = set(ClaudeModalPending(taskId, now(), seen = false))

    /** The header sheet's door: Kevin tapped a task, so it is on screen already. */
    @Synchronized
    fun open(taskId: Int) = set(ClaudeModalPending(taskId, now(), seen = true))

    /** Pure: may [p] be drawn now? Seen, or still within [FRESH_FOR] of its send. */
    fun mayShow(p: ClaudeModalPending): Boolean = p.seen || fresh(p)

    /**
     * Called once the app is in the foreground with [p] pending: drawn and so marked seen, or too
     * old to show and dropped. Ignored when [p] is no longer the pending one (a newer task won).
     */
    @Synchronized
    fun admit(p: ClaudeModalPending) {
        if (_pending.value != p || p.seen) return
        if (fresh(p)) set(p.copy(seen = true)) else set(null)
    }

    /** Kevin's Close (or back, or a tap outside). Only for the task he closed. */
    @Synchronized
    fun close(taskId: Int) {
        if (_pending.value?.taskId == taskId) set(null)
    }

    private fun fresh(p: ClaudeModalPending): Boolean = Duration.between(p.shownAt, now()) <= FRESH_FOR

    private fun set(p: ClaudeModalPending?) {
        _pending.value = p
        memory.save(p)
    }

    companion object {
        /** How long a dialog nobody has seen yet may still appear (Kevin 2026-11-04). */
        val FRESH_FOR: Duration = Duration.ofMinutes(30)

        @Volatile private var instance: ClaudeModalHolder? = null

        /** The process's one instance, remembered in SharedPreferences. */
        fun get(context: Context): ClaudeModalHolder {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ClaudeModalHolder(PrefsClaudeModalMemory(context.applicationContext))
                    .also { instance = it }
            }
        }
    }
}

/** Install-scoped, never synced: one task id, a time and a flag. */
private class PrefsClaudeModalMemory(context: Context) : ClaudeModalMemory {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): ClaudeModalPending? {
        val id = prefs.getInt(KEY_TASK, 0)
        val at = prefs.getLong(KEY_AT, 0L)
        if (id <= 0 || at <= 0L) return null
        return ClaudeModalPending(id, Instant.ofEpochMilli(at), prefs.getBoolean(KEY_SEEN, false))
    }

    override fun save(pending: ClaudeModalPending?) {
        val edit = prefs.edit()
        if (pending == null) {
            edit.remove(KEY_TASK).remove(KEY_AT).remove(KEY_SEEN)
        } else {
            edit.putInt(KEY_TASK, pending.taskId)
                .putLong(KEY_AT, pending.shownAt.toEpochMilli())
                .putBoolean(KEY_SEEN, pending.seen)
        }
        edit.apply()
    }

    private companion object {
        const val PREFS = "claude_modal"
        const val KEY_TASK = "task_id"
        const val KEY_AT = "shown_at"
        const val KEY_SEEN = "seen"
    }
}

/** An in-memory [ClaudeModalMemory], for tests and previews. */
class InMemoryClaudeModalMemory(var stored: ClaudeModalPending? = null) : ClaudeModalMemory {
    override fun load(): ClaudeModalPending? = stored
    override fun save(pending: ClaudeModalPending?) {
        stored = pending
    }
}
