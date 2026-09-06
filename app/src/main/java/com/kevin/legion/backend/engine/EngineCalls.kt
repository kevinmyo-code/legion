package com.kevin.legion.backend.engine

import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException

/**
 * The failure-translation shared by [DjangoEventsBackend] and [DjangoChecklistsBackend].
 *
 * Extracted here rather than written once per backend for two reasons, and the second one is the
 * load-bearing one: the three helpers were byte-identical in both files, and keeping them as
 * members pushed `DjangoChecklistsBackend` to thirteen functions - past detekt's
 * `TooManyFunctions.allowedFunctionsPerClass` ceiling of eleven, which this ticket's brief forbids
 * baselining away. Moving shared plumbing out of a class is the fix that rule is asking for.
 */

/**
 * Runs [block], turning anything it throws into a `Result.failure` that still says which branch of
 * [EngineFailure] it is - a caller deciding whether to QUEUE a write reads that branch, so
 * flattening it into a bare message string would take away the one thing it needs.
 *
 * **The second catch is deliberately narrow, and this is the list of what can actually reach it.**
 * [block] does exactly two things: unwrap an [EngineHttp] result (which throws only
 * [EngineHttpException], caught above) and decode JSON. Decoding raises [SerializationException]
 * for a malformed body or a missing required field, [DateTimeParseException] for a timestamp in a
 * shape `OffsetDateTime`/`LocalDate` will not read, and [IllegalArgumentException] from
 * `Json.parseToJsonElement` on structured metadata this codebase produced itself. **Anything else
 * is a bug in this file rather than a wire problem, and is deliberately left to propagate** to the
 * caller's own crash guard, rather than being relabelled as a transport failure the driver can do
 * nothing about.
 */
internal inline fun <T> translatingEngineCall(action: String, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: EngineHttpException) {
    Result.failure(EngineHttpException(prefixEngineFailure(e.failure, action)))
} catch (e: SerializationException) {
    Result.failure(EngineHttpException(malformed(action, e)))
} catch (e: DateTimeParseException) {
    Result.failure(EngineHttpException(malformed(action, e)))
} catch (e: IllegalArgumentException) {
    Result.failure(EngineHttpException(malformed(action, e)))
}

internal fun malformed(action: String, e: Exception): EngineFailure.Malformed =
    EngineFailure.Malformed("Couldn't $action: ${e.message ?: "the engine's reply didn't decode"}")

/**
 * Names what could not be done in front of the engine's own explanation - except on
 * [EngineFailure.Refused], which is passed through untouched.
 *
 * **That exception is the point of this function existing at all.** The measured-item refusal is
 * copied VERBATIM between `ChecklistController.tick` and `checklists/serializers.py` precisely so
 * the phone and the engine say the same words; putting "Couldn't record that tick." in front of it
 * would break that on the one path it was built for.
 */
internal fun prefixEngineFailure(failure: EngineFailure, action: String): EngineFailure = when (failure) {
    is EngineFailure.Unreachable -> failure.copy(message = "Couldn't $action. ${failure.message}")
    is EngineFailure.Unauthorized -> EngineFailure.Unauthorized("Couldn't $action. ${failure.sentence}")
    is EngineFailure.Refused -> failure
    is EngineFailure.Malformed -> EngineFailure.Malformed("Couldn't $action. ${failure.sentence}")
}

/**
 * The shared `DELETE` shape: a 2xx is `true`, a 404 is `Result.success(false)` ("there was no such
 * row"), everything else stays a failure carrying the engine's own words.
 *
 * **404 is the only non-2xx that becomes a success**, and only because "nothing there to remove" is
 * an outcome the caller asked about rather than a refusal - matching
 * [com.kevin.legion.backend.EventsBackend.softDelete]'s own `Result.success(false)` contract. Note
 * what this cannot tell apart, because both engine views are deliberately idempotent: a row this
 * call tombstoned and a row that was already tombstoned both answer 204, so `true` means "it is
 * gone now", never "this call is what removed it".
 */
internal suspend fun deletingEngineRow(action: String, call: suspend () -> Result<EngineOk>): Result<Boolean> {
    val result = call()
    val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
    return when {
        result.isSuccess -> Result.success(true)
        failure is EngineFailure.Refused && failure.status == HTTP_NOT_FOUND -> Result.success(false)
        else -> Result.failure(
            EngineHttpException(
                prefixEngineFailure(failure ?: EngineFailure.Malformed("unknown error"), action),
            ),
        )
    }
}

/** DRF's `status.HTTP_404_NOT_FOUND` - the one status [deletingEngineRow] reads as an answer
 * rather than as a refusal, and the one `DjangoEventsBackend` reports its own missing endpoints
 * under. */
internal const val HTTP_NOT_FOUND = 404

/** DRF's `status.HTTP_201_CREATED` - the only signal that tells an idempotent create's two
 * outcomes apart, since `POST /api/events` and `POST /api/checklists/` both hand back a row either
 * way. See [EngineOk]'s own doc comment. */
internal const val HTTP_CREATED = 201

/**
 * Wraps a fire-and-forget foreground pass so an unexpected failure becomes a breadcrumb rather
 * than a crash, and returns null when it does.
 *
 * **This is the one place in this ticket's code that catches [Exception] broadly, and the
 * suppression below is the whole reason it exists as a shared helper rather than as six inline
 * try/catch blocks.** The callers are the app's foreground entry points -
 * `ChecklistsSync.maybeAutoPull`, `ChecklistsBackfill.maybeAutoRun`,
 * `ChecklistsOutboxDrain.maybeDrain`, `EnginePoll`'s loop, `EngineSyncNow`'s two lines - and for
 * every one of them the intent genuinely IS "anything at all": they run on a `SupervisorJob` scope
 * with no `CoroutineExceptionHandler`, so an uncaught throw reaches the thread's default handler
 * and takes the app down on something as ordinary as unlocking the screen. Narrowing the catch
 * would not make those paths safer, it would make an unanticipated failure fatal. Every sibling
 * aspect's `maybeAutoPull` catches exactly this broadly for exactly this reason
 * ([com.kevin.legion.backend.LastAspectsSync], [com.kevin.legion.backend.EventsSync],
 * [com.kevin.legion.backend.BodySync]); they predate detekt and sit in its baseline, and this
 * ticket's brief forbids adding to that baseline - so the same decision is stated here, once, in
 * words, instead of six times in a file nobody reads.
 *
 * `inline` so [block] may suspend at the call site.
 */
@Suppress("TooGenericExceptionCaught") // Crash guard: the breadth IS the behaviour, per the doc above.
internal inline fun <T> guardingForeground(onFailure: (Exception) -> Unit, block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    onFailure(e)
    null
}
