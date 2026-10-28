package com.kevin.legion.service.turn

import org.json.JSONObject

/**
 * `toolCallCancellation` (ai.google.dev/api/live): the user spoke over a turn that was waiting on a
 * tool, the server dropped the call, and the ids "should not have been executed". BLOCKING tools are
 * already running by then. Kevin, 2026-10-27: **report what happened, no undo.**
 *
 * So a cancelled call is left to finish; its response is NOT sent (the server no longer wants it).
 * For a write, what it did becomes a [Note] the session hands the model at the next quiet moment, so
 * Alfred says plainly whether it happened. A read that was cancelled is simply dropped. Pure, so the
 * bookkeeping is a JVM test.
 */
internal class CancelledToolReport {
    data class Note(val text: String)

    private val cancelled = HashSet<String>()
    private val pending = ArrayList<Note>()

    @Synchronized
    fun onCancellation(ids: Collection<String>) {
        cancelled += ids
    }

    @Synchronized
    fun isCancelled(id: String): Boolean = id in cancelled

    /**
     * A tool finished. Returns true when its response must be swallowed (cancelled); for a cancelled
     * write the outcome is queued as a note.
     */
    @Synchronized
    fun onFinished(id: String, name: String, response: JSONObject, isWrite: Boolean): Boolean {
        if (!cancelled.remove(id)) return false
        if (isWrite) pending += Note(noteFor(name, response))
        return true
    }

    @Synchronized
    fun hasNotes(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun takeNotes(): List<Note> = pending.toList().also { pending.clear() }

    companion object {
        /**
         * The text sent as a user-role turn. Conditioned on the tool RESULT (CLAUDE.md sec 7 outcome
         * verbs): success only when the tool said so.
         */
        fun noteFor(name: String, response: JSONObject): String {
            val ok = response.optBoolean("success", false)
            val detail = response.optString("message").ifBlank { response.optString("result") }.take(MAX_DETAIL)
            val what = if (ok) {
                "it had already run and succeeded" + if (detail.isNotBlank()) ": $detail" else ""
            } else {
                "it did NOT complete" + if (detail.isNotBlank()) ": $detail" else ""
            }
            return "(System note, not spoken by the user: the user interrupted while `$name` was running, " +
                "so its call was cancelled, but $what. Nothing was undone. Tell the user in one short " +
                "sentence whether it happened, then carry on with what they just said.)"
        }

        private const val MAX_DETAIL = 200
    }
}
