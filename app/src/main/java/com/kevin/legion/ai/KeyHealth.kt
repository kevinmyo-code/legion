package com.kevin.legion.ai

/**
 * Last-known health of the user's BYO Gemini key, set from sub-agent call
 * outcomes so the Setup screen can surface a quiet heads-up ("key issue seen
 * recently") without a network probe. Process-lifetime only; cleared on the
 * first clean success.
 */
object KeyHealth {
    @Volatile var lastProblem: String? = null; private set   // "rate-limited" | "invalid"
    @Volatile var lastProblemAt: Long = 0L; private set

    fun noteRateLimited() { lastProblem = "rate-limited"; lastProblemAt = System.currentTimeMillis() }
    fun noteInvalid() { lastProblem = "invalid"; lastProblemAt = System.currentTimeMillis() }
    fun noteOk() { lastProblem = null }
}
