package com.kevin.legion.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Whether web text came back to a sub-agent while one tool call ran (round 3, 2026-11-09).
 *
 * `ask_fleet` routes to specialists (`diagnose_codes`, `triage_symptom`, `ask_maintenance`) whose
 * `web_lookup` tool, and whose one-shot fallback answers, are Gemini calls grounded on Google
 * Search. That text reached the Live model through `ask_fleet`, which is not a read-through tool,
 * so `remember` and the goal-plan constraint save in the same turn were not gated.
 *
 * The tool runner (voice: `LiveSessionController.handleToolCall`; typed: `TextChatEngine.runOne`)
 * puts a fresh mark in the coroutine context around ONE tool call. [SubAgent] notes it whenever a
 * search-grounded POST answers. The runner then raises the turn's read-through flag, the same one
 * a mail tool raises. Per call, not per tool name: an `ask_fleet` answered from the car's own data
 * leaves the turn untouched. A coroutine context element so no signature between the runner and the
 * nested sub-agent changes; with no runner around a call (a screen, a worker), noting does nothing.
 */
class WebReadMark : AbstractCoroutineContextElement(WebReadMark) {

    @Volatile
    var touched: Boolean = false
        private set

    /** Called from parallel routed tools too; a one-way flip, so a plain volatile write is enough. */
    fun note() {
        touched = true
    }

    companion object Key : CoroutineContext.Key<WebReadMark> {
        /** Notes a web read on the calling coroutine's mark, if a tool runner installed one. */
        suspend fun noteWebRead() {
            currentCoroutineContext()[Key]?.note()
        }
    }
}
