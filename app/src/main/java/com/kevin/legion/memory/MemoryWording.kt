package com.kevin.legion.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every sentence memory says, once, for the voice tool and the Memory screen alike, so the two never
 * disagree about what happened (the [com.kevin.legion.purchases.PurchaseWording] posture). Each
 * failure says what did NOT happen (CLAUDE.md section 7's outcome-verb rule).
 */
object MemoryWording {
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_NOT_FOUND = 404

    const val VAULT_REDIRECT =
        "That looks like an identity or bank number, so it was not saved: that belongs in the ID vault, " +
            "on the phone behind the fingerprint."

    const val DELETED = "Deleted for good."

    private val DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY)

    /** Where a memory came from, in words: "from a conversation on <date>" for a distilled one. */
    fun origin(memory: MemberMemory): String = when {
        memory.fromConversation != null || memory.source == "consolidation" ->
            "from a conversation on ${day(memory.createdAtMs)}"
        memory.source == "manual" -> "added by hand on ${day(memory.createdAtMs)}"
        memory.source == "web" -> "from the web desk on ${day(memory.createdAtMs)}"
        memory.source == "claude" -> "from Claude on ${day(memory.createdAtMs)}"
        else -> "said to the assistant on ${day(memory.createdAtMs)}"
    }

    fun sharing(memory: MemberMemory): String = when {
        memory.shared -> "shared with the household"
        else -> "private to you"
    }

    fun saved(memory: MemberMemory): String =
        if (memory.shared) "Saved, shared with the household." else "Saved. It is private to you."

    fun sharedChanged(memory: MemberMemory): String =
        if (memory.shared) "Shared with the household." else "Private to you now."

    /** Why a write did not happen. [verb] is the past participle: "saved", "deleted", "changed". */
    fun writeFailed(outcome: MemoryOutcome<*>, verb: String): String = when (outcome) {
        is MemoryOutcome.Unreachable ->
            "I can't reach the engine, so nothing was $verb. ${outcome.sentence} " +
                "Try again once the phone is back online."
        is MemoryOutcome.Refused -> refused(outcome, verb)
        is MemoryOutcome.Unconfirmed ->
            "The engine's reply could not be read, so I can't say whether it was $verb. ${outcome.sentence}"
        is MemoryOutcome.Ok<*> -> ""
    }

    /** Why a read did not happen. A failed read is never "nothing remembered". */
    fun readFailed(outcome: MemoryOutcome<*>): String = when (outcome) {
        is MemoryOutcome.Unreachable -> "I can't reach the engine, so I can't see your memories. ${outcome.sentence}"
        is MemoryOutcome.Refused -> "The engine refused to read your memories: ${outcome.sentence}"
        is MemoryOutcome.Unconfirmed -> "The engine's reply could not be read. ${outcome.sentence}"
        is MemoryOutcome.Ok<*> -> ""
    }

    private fun refused(outcome: MemoryOutcome.Refused, verb: String): String = when {
        outcome.status == HTTP_BAD_REQUEST && outcome.sentence.contains("ID vault", ignoreCase = true) -> VAULT_REDIRECT
        outcome.status == HTTP_NOT_FOUND -> "There is no memory with that id that you can see, so nothing was $verb."
        else -> outcome.sentence.ifBlank { "The engine refused, so nothing was $verb." }
    }

    /** The sentence a cached answer carries, for the tool result and the screen's banner. */
    fun cachedNote(read: MemoryController.MemoryRead): String {
        val asOf = read.cachedAtMs?.let { " as of ${day(it)}" }.orEmpty()
        return "The engine is out of reach, so this is the phone's copy$asOf; " +
            "anything saved or forgotten since may be missing."
    }
}
