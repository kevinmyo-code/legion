package com.kevin.legion.ui.claudesession

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kevin.legion.claude.ClaudePageOpener
import com.kevin.legion.claude.ClaudeSessionController
import kotlinx.coroutines.launch

/**
 * Opens a Claude task's delivered page (claude-session ticket 09) from a tap: the result
 * notification and the Claude session screen's Open page button. No UI of its own; it reads the
 * task's fresh signed link through [ClaudePageOpener], the path the voice tool's `open_page` takes,
 * hands it to LEGION's browser, and finishes. A page that cannot open says why in a toast.
 *
 * Not exported: it carries only a task id, and only LEGION's own PendingIntent reaches it.
 */
class ClaudePageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
        if (taskId <= 0) {
            finish()
            return
        }
        val app = applicationContext
        lifecycleScope.launch {
            val result = ClaudePageOpener.open(app, ClaudeSessionController.forContext(app), taskId)
            if (!result.opened) Toast.makeText(app, result.sentence, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "claude_task_id"

        fun intent(context: Context, taskId: Int): Intent =
            Intent(context, ClaudePageActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context, taskId: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_BASE + taskId,
                intent(context, taskId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private const val REQUEST_BASE = 0x4C50_0000
    }
}
