package com.kevin.legion.claude

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.service.LiveNotes
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.ui.claudesession.ClaudeFileActivity
import com.kevin.legion.ui.claudesession.ClaudePageActivity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ONE [ClaudeResultWatcher] for the process, and the two existing loops that run it: while a
 * LEGION activity is in front ([bindForeground], from `MidnightApplication`) and while
 * `AriaForegroundService` runs. The Tetris invites' shape exactly (`GameInvitePolling`): no new
 * alarm, no WorkManager. With the assistant off and the app closed nothing polls, and a result
 * waits until LEGION is next opened; it is still on the Claude session screen.
 *
 * **Delivery, in order:** an open conversation takes it as a system note ([LiveNotes]); otherwise
 * it goes through the proactive registry ([ProactiveBus.speakIfAllowed], its own "Claude tasks"
 * category: on by default), which speaks it when it may and posts it when it
 * may not. A spoken result that asks something listens about 20 s for the yes. A refusal that
 * means "not right now" leaves the task watched for the next poll.
 */
object ClaudeResultPolling {
    @Volatile private var watcher: ClaudeResultWatcher? = null

    fun watcher(context: Context): ClaudeResultWatcher {
        watcher?.let { return it }
        return synchronized(this) {
            watcher ?: build(context.applicationContext).also { watcher = it }
        }
    }

    private fun build(app: Context): ClaudeResultWatcher = ClaudeResultWatcher(
        controller = { ClaudeSessionController.forContext(app) },
        watch = ClaudeWatchStore(app),
        announce = { id, text, hint -> announce(app, id, text, hint) },
        saveFile = { name, bytes -> saveToDownloads(app, name, bytes) },
        // The ongoing "Claude: N running" notification rides this poll (no loop of its own).
        liveActive = { ClaudeLiveWatch.get(app).active() },
        onOverview = { overview ->
            val live = ClaudeLiveWatch.get(app)
            if (overview != null) live.seen(overview) else live.unreadable()
        },
        // ADR 0080: files Kevin named land in app-private storage, open at once if LEGION is in
        // front, and otherwise wait behind a notification that opens them.
        shelf = ClaudeFileStore(app),
        openFiles = { id -> openOrNotify(app, id) },
        filesReady = { id, text -> announce(app, id, text, "", ClaudeFileActivity.pendingIntent(app, id)) },
        // ADR 0083: standing watches ride this loop (no loop of their own). Worker-workflows
        // ticket 06: so do scheduled runs waiting on his yes. One failing never skips the other.
        alsoEachTick = {
            val scheduled = scheduledQuestions(app)
            try {
                com.kevin.legion.watches.WatchPolling.poller(app).maybePoll()
            } finally {
                scheduled.maybePoll()
            }
        },
    )

    @Volatile private var scheduled: ScheduledQuestionPoller? = null

    /** The ONE [ScheduledQuestionPoller] for the process (its cadence lives in the instance). */
    private fun scheduledQuestions(app: Context): ScheduledQuestionPoller =
        scheduled ?: synchronized(this) {
            scheduled ?: ScheduledQuestionPoller(
                controller = { ClaudeSessionController.forContext(app) },
                seen = ScheduledQuestionSeenStore(app),
                watch = ClaudeWatchStore(app),
                announce = { id, text, hint -> announce(app, id, text, hint) },
            ).also { scheduled = it }
        }

    /**
     * Opens task [taskId]'s delivered files over LEGION, only while one of LEGION's screens is in
     * front ("if Alfred's screen is up"): Android does not let a background app start an activity,
     * and a viewer jumping over another app would be the wrong thing anyway. Otherwise posts the
     * notification that opens them on a tap ([ClaudeFilesNotifier]) and returns false.
     */
    private suspend fun openOrNotify(context: Context, taskId: Int): Boolean = withContext(Dispatchers.Main) {
        val inFront = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (inFront) {
            context.startActivity(ClaudeFileActivity.intent(context, taskId))
        } else {
            ClaudeFilesNotifier.post(context, taskId, ClaudeFileStore(context).list(taskId).size)
        }
        inFront
    }

    /** Polls while the app is in front; call once from `Application.onCreate` (main thread). */
    fun bindForeground(context: Context, scope: CoroutineScope) {
        val watcher = watcher(context)
        var loop: Job? = null
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (loop?.isActive != true) loop = scope.launch { watcher.run() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    loop?.cancel()
                    loop = null
                }
            },
        )
    }

    /** The system note for an open conversation: the facts, and what to do with them. */
    internal fun note(text: String, hint: String): String =
        "[Claude session result, for the user, from a task they sent] $text Tell the user this now, in " +
            "one or two short sentences in your own words. If it ends with a question, ask it, and act " +
            "only on their yes. $hint Say nothing else about it."

    internal fun prompt(text: String, hint: String): String =
        "A task the user sent to their Claude session has a result. The facts, and the only things you " +
            "may say about it: $text Tell the user in one or two short sentences, in your own words. " +
            "If it ends with a question, ask it and act only on their yes. $hint Mention no other subject."

    /** How long a result that asks something listens for the yes (Kevin 2026-11-01: about 20 s). */
    const val LISTEN_HOLD_MS = 20_000L

    private suspend fun announce(
        context: Context,
        taskId: Int,
        text: String,
        hint: String,
        tapOpens: android.app.PendingIntent? = null,
    ): ClaudeDelivery {
        // Keyed by task, so a `claude_session` check that reads the same result out first can
        // withdraw this note before it is spoken a second time (ClaudeSessionToolbox.check).
        if (LiveNotes.offer(LiveNotes.claudeTaskKey(taskId), note(text, hint))) return ClaudeDelivery.DELIVERED
        val asks = hint.isNotBlank()
        val raise = ProactiveRaise(
            // One rule per task, so brushing off one result never suppresses the next task's.
            ruleId = "claude_task_result_$taskId",
            category = ProactiveCategory.CLAUDE_TASKS,
            reason = text,
            facts = text,
            prompt = prompt(text, hint),
            // A question opens the mic for the yes; the follow-up window closes it (no ring to end).
            listensForReply = asks,
            listenHoldMs = if (asks) LISTEN_HOLD_MS else 0L,
            // Ticket 09: a ready page's notification opens it when tapped (the hands path).
            // ADR 0080: a files result's notification opens the files.
            contentIntent = tapOpens ?: if (hint.contains(ClaudeResultWatcher.OPEN_PAGE_ACTION)) {
                ClaudePageActivity.pendingIntent(context, taskId)
            } else {
                null
            },
        )
        return when (ProactiveBus.speakIfAllowed(context, raise)) {
            ProactiveBus.RaiseOutcome.NotNow -> ClaudeDelivery.RETRY
            else -> ClaudeDelivery.DELIVERED
        }
    }

    /**
     * Saves [bytes] as [name] in the phone's Downloads (MediaStore on Android 10+, the app's own
     * external Downloads folder below that). False on any failure; nothing claims it was saved.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // MediaStore throws several types; any is "not saved"
    internal suspend fun saveToDownloads(context: Context, name: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "claude-file" }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext false
                    true
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext false
                    File(dir, safe).writeBytes(bytes)
                    true
                }
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false
            }
        }
}
