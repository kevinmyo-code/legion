package com.kevin.legion.claude

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every sentence the Claude session says, shared by the voice tool, the result announcement and
 * the screen (ADR 0035: one wording, so the two doors never disagree). Pure: the clock and zone
 * come in, so a test pins "off since".
 *
 * **No sentence says a task is done unless the engine's row says `done`** (CLAUDE.md section 7):
 * a send is "sent" or "queued", never finished, and a failure says what did not happen. No
 * assistant name appears in any of it (CLAUDE.md section 1).
 */
@Suppress("TooManyFunctions") // one function per sentence, so the voice tool and the screen share them
class ClaudeWording(
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** "3:40 PM", "yesterday 9:05 PM" or "Oct 30, 9:05 PM", in the phone's own zone. */
    fun clock(at: Instant): String {
        val z = zone()
        val then = at.atZone(z)
        val today: LocalDate = now().atZone(z).toLocalDate()
        val time = then.format(TIME)
        return when (then.toLocalDate()) {
            today -> time
            today.minusDays(1) -> "yesterday $time"
            else -> then.format(DAY) + ", " + time
        }
    }

    /** "on, idle", "on, busy", "off since 3:40 PM", "off (never connected)". */
    fun machineState(state: String, lastSeenAt: Instant?): String = when {
        state != ClaudeMachine.STATE_OFFLINE -> "on, $state"
        lastSeenAt != null -> "off since ${clock(lastSeenAt)}"
        else -> "off (no heartbeat yet)"
    }

    fun machineLine(m: ClaudeMachine): String {
        val base = "${m.name}: ${machineState(m.state, m.lastSeenAt)}"
        val scope = if (m.summariesOnly) " (summaries only, never files)" else ""
        val running = if (m.running.isEmpty()) "" else {
            "; running " + m.running.joinToString { id ->
                "task $id" + (m.progress[id]?.let { " ($it)" } ?: "")
            }
        }
        return base + scope + running
    }

    // --------------------------------------------------------------------------- sending

    fun sent(created: ClaudeCreated, followUp: Boolean = false): String {
        val what = if (followUp) "The follow-up" else "Task ${created.id}"
        val m = created.machine ?: return "$what is queued for whichever machine comes on first. It is not done; " +
            "I'll say when there is a result."
        return if (m.state != ClaudeMachine.STATE_OFFLINE) {
            "$what was sent to the ${m.name}, which is on. It is not done yet; I'll say when there is a result."
        } else {
            "The ${m.name} is ${machineState(m.state, m.lastSeenAt)}, so $what is queued and runs when it is " +
                "back on. Nothing has run yet."
        }
    }

    fun whichMachine(choices: List<String>, created: Boolean): String {
        val list = choices.joinToString(" or ") { "the $it" }
        return if (created) {
            "Not sent yet: more than one machine is on. Which one, $list?"
        } else {
            "Not sent yet: no machine is on and there is more than one. Which one should it queue for, $list?"
        }
    }


    fun unknownMachine(name: String, known: List<String>): String =
        "There is no machine called \"$name\". Known: ${known.joinToString().ifEmpty { "none" }}. Nothing was sent."


    fun fileRequested(file: ClaudeTaskFile, machine: String?): String =
        "Asked the ${machine ?: "machine"} to upload ${file.name}. It is not on the phone yet; it lands in " +
            "Downloads after the machine's next check-in, and I'll say when it has."

    fun pageNeedsFullMachine(machine: String): String =
        "The $machine returns summaries only, so it can't send a page to your phone. Nothing was sent; " +
            "ask for it on the Alienware, or for the summary."

    fun summariesOnly(machine: String): String =
        "The $machine returns summaries only, never files, so nothing was sent to the phone."

    // --------------------------------------------------------------------------- results

    /**
     * The finished task in words, for the announcement and for `check`. Files are named with their
     * size and offered, never sent ([summariesOnly] machines have none to offer, ruling 5).
     */
    @Suppress("CyclomaticComplexMethod") // one branch per task status, each a sentence
    fun result(task: ClaudeTask, summariesOnly: Boolean): String {
        val on = task.machine?.let { " on the $it" } ?: ""
        val head = "Task ${task.id}$on (\"${task.request.take(REQUEST_SHOWN)}\")"
        val summary = task.summary?.trim()?.takeIf { it.isNotEmpty() }
        return when (task.status) {
            ClaudeTask.DONE -> buildString {
                append("$head is done.")
                summary?.let { append(" ").append(it) }
                // A delivered page is opened, not saved; it is never offered as a file too.
                val offer = task.files.filterNot { it.uploaded || (task.wantsPage && it.isPage) }
                val page = task.pages.firstOrNull()
                when {
                    !task.wantsPage -> Unit
                    page != null -> append(" The page \"${page.title}\" is ready. Want me to open it?")
                    task.pagePending -> append(" The page has not reached your phone yet, so it can't be opened.")
                    else -> append(" No page came back with it.")
                }
                if (!summariesOnly && offer.isNotEmpty()) {
                    append(" Found ").append(offer.joinToString { "${it.name} (${size(it.size)})" })
                    append(if (page != null) "." else ". Want it on your phone?")
                }
            }
            ClaudeTask.NEEDS_CONFIRMATION ->
                "$head stopped to ask: ${task.question ?: summary ?: "it needs your yes"}. Nothing on the ask " +
                    "list has run; it goes ahead only on your yes."
            ClaudeTask.FAILED -> "$head did not finish. ${summary ?: "No reason was given."}"
            ClaudeTask.CANCELLED -> "$head was cancelled. ${summary ?: ""}".trim()
            ClaudeTask.RUNNING, ClaudeTask.CANCEL_REQUESTED ->
                // The current step, or the newest one kept: one line, never the history.
                "$head is running" + ((task.progress ?: task.latestStep)?.let { ": $it" } ?: "") + ". Not done yet."
            ClaudeTask.NEEDS_MACHINE -> "$head is waiting for you to pick a machine. Nothing has run."
            else -> "$head is queued. Nothing has run yet."
        }
    }

    fun fileSaved(name: String): String = "$name is on your phone, in Downloads."

    // --------------------------------------------------------------------------- pages (ticket 09)

    fun pageOpened(page: ClaudePage): String = "Opened \"${page.title}\" in the browser."

    fun noPage(taskId: Int): String = "Task $taskId has no page on the engine yet, so nothing was opened."

    fun pageNotOpened(page: ClaudePage, why: String): String = "\"${page.title}\" did not open: $why"

    fun fileSaveFailed(name: String): String =
        "$name was uploaded but could not be saved to Downloads. Open the Claude session screen to try again."

    companion object {
        const val NO_MACHINE_EVER = "No machine has ever connected to the Claude session, so nothing was sent."
        const val EMPTY_REQUEST = "What should the Claude session do? Nothing was sent."
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        private const val REQUEST_SHOWN = 80
        private const val KB = 1024L
        private const val MB = KB * KB

        /** "512 bytes", "40 KB", "2.1 MB", or "size unknown". */
        fun size(bytes: Long?): String = when {
            bytes == null -> "size unknown"
            bytes < KB -> "$bytes bytes"
            bytes < MB -> "${bytes / KB} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MB)
        }

        /** What a failed call says. [what] completes "so I couldn't ...". */
        fun failed(outcome: ClaudeOutcome<*>, what: String): String = when (outcome) {
            is ClaudeOutcome.Unreachable -> "I can't reach the engine right now, so I couldn't $what."
            is ClaudeOutcome.Refused -> "The engine refused, so I couldn't $what: ${outcome.sentence}"
            is ClaudeOutcome.Unconfirmed ->
                "The engine answered but I couldn't read the reply, so I can't say whether I could $what. " +
                    "Check the Claude session screen before assuming it went."
            is ClaudeOutcome.Ok -> error("failed called with an Ok outcome")
        }
    }
}
