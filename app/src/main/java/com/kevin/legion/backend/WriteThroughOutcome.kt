package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException

/**
 * What one write-through attempt actually did, for the aspects that push BEFORE they write
 * locally ([BodyWriteThrough], [MemoryWriteThrough] - `.scratch/django-engine/issues/15-*`).
 *
 * **Four branches because four different sentences follow from them**, which is CLAUDE.md section
 * 7's outcome-verb rule expressed as a type: a caller cannot say "logged" off a [Refused] without
 * writing code that visibly ignores the branch it is in. It is the same posture
 * [ChecklistsWriteThrough.PushOutcome] already takes for checklists, with one branch that shape
 * does not need (see [StoredLocally]) and one distinction it gets wrong (see [Queued]).
 *
 * - [Sent] - the engine took it and the row is in Room. The only branch a caller may speak an
 *   unqualified outcome verb off.
 * - [Queued] - the row is in Room, the engine does NOT have it, and an [com.kevin.legion.data.local.OutboxEntry]
 *   is waiting. **The caller says so in words** (ADR 0044 rule 4: "the phone reads from Room when
 *   the server is down, queues the write, and says so"). An offline log is still a log.
 * - [StoredLocally] - the legacy local-first path: this aspect is still on
 *   [com.kevin.legion.backend.engine.Transport.SUPABASE], or no backend is configured at all.
 *   Row written, push attempted and queued on failure exactly
 *   as it always was, and **nothing extra is said**, because nothing extra was ever said on that
 *   path and ticket 15's brief freezes it ("nothing may change for an install that has not
 *   flipped"). Deliberately does NOT distinguish sent from queued: that distinction only exists to
 *   be spoken, and on this path it is not.
 * - [Refused] - the engine received the write, understood it, and said no. **Nothing was written
 *   locally**, so there is no poisoned row and no outbox entry, and [Refused.message] is the
 *   server's own sentence verbatim for the caller to relay.
 */
sealed interface WriteThroughOutcome<out T> {
    /** The row as it stands in Room, or null when nothing was written (the [Refused] branch, and
     * only that branch). */
    val row: T?

    data class Sent<out T>(override val row: T) : WriteThroughOutcome<T>

    data class Queued<out T>(override val row: T, val reason: String) : WriteThroughOutcome<T>

    data class StoredLocally<out T>(override val row: T) : WriteThroughOutcome<T>

    data class Refused(val message: String) : WriteThroughOutcome<Nothing> {
        override val row: Nothing? get() = null
    }
}

/**
 * The engine's own refusal sentence when [cause] is a write the server rejected, null for
 * everything else - **including a 5xx, and that exclusion is the whole reason this function
 * exists rather than a bare `is EngineFailure.Refused` check at each call site.**
 *
 * [com.kevin.legion.backend.engine.EngineHttp.classify] files 5xx under
 * [EngineFailure.Refused] too (it needs the status kept, and "not Unreachable" is the load-bearing
 * half there - the request DID reach the engine). But "The engine failed on its side (HTTP 503)"
 * is a FAULT, not something the user asked for and was told no about: reporting it as a refusal
 * would tell someone their perfectly good sleep log was rejected, and dropping their write on the
 * floor because a container restarted mid-request would be worse still. So only 400-499 is a
 * refusal; a 5xx falls through to the queue like any other failure the engine did not articulate.
 *
 * [EngineFailure.Unauthorized] is likewise not a refusal of the ROW - the device's token is wrong,
 * which says nothing about the write - so it queues too, and the queue's own bounded attempts
 * ([BodyOutboxDrain.MAX_ATTEMPTS]) stop it retrying forever.
 */
internal fun refusalSentence(cause: Throwable?): String? {
    val failure = (cause as? EngineHttpException)?.failure as? EngineFailure.Refused ?: return null
    return if (failure.status in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX) failure.body else null
}

/** 400-499, spelled as two constants rather than one `400..499` range purely because detekt's
 * MagicNumber rule exempts a `const val` and not a property initialiser. Same span
 * [com.kevin.legion.backend.engine.EngineHttp]'s own `CLIENT_ERROR_RANGE` uses. */
private const val CLIENT_ERROR_MIN = 400
private const val CLIENT_ERROR_MAX = 499

/**
 * The words a caller appends when its write is [WriteThroughOutcome.Queued] - one wording, shared,
 * so six controllers cannot drift into six different accounts of the same state. Names the reason
 * verbatim rather than summarising it, since a 5xx's own sentence and a "nothing was sent"
 * sentence are the two cases this covers and they read very differently.
 */
fun queuedSentence(reason: String): String =
    "It's saved on this phone but not on the server yet - queued, and it'll go out when the server is back. ($reason)"
