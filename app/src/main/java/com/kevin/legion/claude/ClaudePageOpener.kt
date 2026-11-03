package com.kevin.legion.claude

import android.content.Context
import com.kevin.legion.MidnightApplication
import com.kevin.legion.browser.LoadOutcome

/** What opening a task's page did. [opened] only when the browser reported the page loaded. */
data class PageOpenResult(val opened: Boolean, val sentence: String)

/**
 * Opens a Claude task's delivered page in LEGION's own browser (claude-session ticket 09). ONE path
 * for all three doors (ADR 0035): the voice tool's `open_page`, the result notification's tap
 * ([com.kevin.legion.ui.claudesession.ClaudePageActivity]) and the Claude session screen's
 * Open page button.
 *
 * **The link is read fresh from the engine every time.** It is signed and lives about ten minutes,
 * so a link kept in a notification or the watch store would be dead by the time it is tapped. The
 * page is served sandboxed (`Content-Security-Policy: sandbox allow-scripts`), so its scripts reach
 * neither the engine nor the browser's cookies.
 *
 * "Opened" is said only on [LoadOutcome.Loaded] (CLAUDE.md section 7).
 */
object ClaudePageOpener {

    /** Test seam: production loads the url in the browser and waits for it to finish. */
    internal var browserOpen: suspend (Context, String) -> LoadOutcome = { context, url ->
        val app = context.applicationContext as? MidnightApplication
        app?.browser?.open(url) ?: LoadOutcome.NotOpen
    }

    @Suppress("ReturnCount") // unreadable task, no page, or the load's outcome: one worded exit each
    suspend fun open(context: Context, controller: ClaudeSessionController, taskId: Int): PageOpenResult {
        val task = when (val out = controller.task(taskId)) {
            is ClaudeOutcome.Ok -> out.value
            else -> return PageOpenResult(false, ClaudeWording.failed(out, "open the page for task $taskId"))
        }
        val page = task.pages.firstOrNull() ?: return PageOpenResult(false, controller.wording.noPage(taskId))
        val w = controller.wording
        return when (val loaded = browserOpen(context, page.url)) {
            is LoadOutcome.Loaded -> PageOpenResult(true, w.pageOpened(page))
            is LoadOutcome.Failed -> PageOpenResult(false, w.pageNotOpened(page, loaded.reason))
            is LoadOutcome.Blocked -> PageOpenResult(false, w.pageNotOpened(page, "the browser blocked it."))
            LoadOutcome.TimedOut -> PageOpenResult(false, w.pageNotOpened(page, "it did not finish loading."))
            LoadOutcome.NotOpen -> PageOpenResult(false, w.pageNotOpened(page, "the browser did not open."))
        }
    }
}
