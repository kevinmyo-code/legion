package com.kevin.legion.service.conversationlog

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one POST came back with, transport-neutral so a test needs no network. */
sealed interface PostResult {
    /** A 2xx. 201 is a new row, 200 a batch the engine already held (a retry after a lost reply). */
    data class Ok(val status: Int) : PostResult

    /** The engine answered with anything but a 2xx. [body] is its own words, verbatim. */
    data class Http(val status: Int, val body: String) : PostResult

    /** Nothing reached the engine: offline, wrong address, refused connection. */
    data class Unreachable(val sentence: String) : PostResult
}

/** The one call the uploader makes. Production is [EngineConversationTransport]. */
interface ConversationTransport {
    /** True when the phone has an engine address and a device token (not a Supabase-only install). */
    fun isUsable(): Boolean

    /** `POST /api/device-logs/` as multipart: [fields], then a `file` part named [fileName]. */
    suspend fun post(fields: Map<String, String>, fileName: String, gzipBody: ByteArray): PostResult
}

/** What the engine is told about this phone. */
data class UploadMeta(val deviceLabel: String, val versionName: String, val versionCode: Int)

/** What an upload attempt did, as a sentence the Settings row shows as-is. */
sealed interface SendOutcome {
    val sentence: String

    data object NothingToSend : SendOutcome {
        override val sentence = "Nothing to send."
    }

    /** No engine address or token: nothing sent, nothing lost. */
    data object NoEngine : SendOutcome {
        override val sentence = "Not sent: this phone is not signed in to an engine. The log stays on this phone."
    }

    /** An automatic trigger inside the backoff window after a failure. Nothing was tried. */
    data object Waiting : SendOutcome {
        override val sentence = "Waiting before trying again. The log stays on this phone."
    }

    data class Sent(val lines: Int, val batches: Int) : SendOutcome {
        override val sentence = "${linesSentence(lines)}."
    }

    /** [sentLines] went before this failure; the failed batch and everything after it are kept. */
    data class Failed(val kind: FailureKind, val reason: String, val sentLines: Int) : SendOutcome {
        override val sentence = (if (sentLines > 0) "${linesSentence(sentLines)}, then failed: " else "Failed: ") +
            "$reason Kept on this phone; it will be tried again."
    }
}

private fun linesSentence(lines: Int) = if (lines == 1) "Sent 1 line" else "Sent $lines lines"

/** Why a POST did not land. Every kind keeps the batch; they differ in what is said and how long to wait. */
enum class FailureKind {
    OFFLINE,
    SIGNED_OUT,

    /** A 400 naming `reason`: an engine from before `conversation` was a reason (`devicelogs/0003`). */
    OLD_ENGINE,
    REFUSED,
    TOO_LARGE,
    SERVER_ERROR,
}

/**
 * The retry table (conversation-records ticket 03). Pure, so the test reads it as a table.
 * Every non-2xx keeps the batch; only a 2xx deletes it.
 */
object RetryRules {
    private const val OK = 200
    private const val LAST_SUCCESS = 299
    private const val LAST_CLIENT_ERROR = 499
    private const val BAD_REQUEST = 400
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val TOO_LARGE = 413
    private const val MAX_REASON_CHARS = 200

    /** Null for a 2xx (delete the batch); otherwise why it failed. */
    fun classify(result: PostResult): FailureKind? = when (result) {
        is PostResult.Ok -> null
        is PostResult.Unreachable -> FailureKind.OFFLINE
        is PostResult.Http -> when {
            result.status in OK..LAST_SUCCESS -> null
            result.status == UNAUTHORIZED || result.status == FORBIDDEN -> FailureKind.SIGNED_OUT
            result.status == BAD_REQUEST && isUnknownReason(result.body) -> FailureKind.OLD_ENGINE
            result.status == TOO_LARGE -> FailureKind.TOO_LARGE
            result.status in BAD_REQUEST..LAST_CLIENT_ERROR -> FailureKind.REFUSED
            else -> FailureKind.SERVER_ERROR
        }
    }

    /**
     * DRF's ChoiceField refusal reaches the phone as the view's "Nothing was stored." sentence with
     * the serializer errors inlined: `{'reason': [ErrorDetail(string='"conversation" is not a valid
     * choice.', code='invalid_choice')]}`.
     */
    fun isUnknownReason(body: String): Boolean =
        body.contains("reason") && (body.contains("not a valid choice") || body.contains("invalid_choice"))

    /** The failure in words; the engine's own sentence is cut at [MAX_REASON_CHARS]. */
    fun sentence(kind: FailureKind, result: PostResult): String = when (kind) {
        FailureKind.OFFLINE -> {
            val why = (result as? PostResult.Unreachable)?.sentence?.take(MAX_REASON_CHARS) ?: "offline"
            "the engine could not be reached ($why)."
        }
        FailureKind.SIGNED_OUT -> "the engine no longer accepts this phone's sign-in. Sign in again."
        FailureKind.OLD_ENGINE -> "the engine is too old to accept conversation logs. Update the engine."
        FailureKind.TOO_LARGE -> "the engine said the batch is too large."
        FailureKind.REFUSED, FailureKind.SERVER_ERROR -> {
            val http = result as? PostResult.Http
            val detail = http?.body?.take(MAX_REASON_CHARS)?.ifBlank { null } ?: "no detail"
            "the engine answered HTTP ${http?.status}: $detail."
        }
    }
}

/**
 * Backoff for the automatic triggers after a failure: [baseMs] doubling to [maxMs]. An old engine
 * waits [maxMs] straight away, since it will not have changed in 30 seconds. "Send now" ignores it.
 * In memory only: app start is itself a trigger, so a restart retrying at once is what we want.
 */
class Backoff(private val baseMs: Long = BASE_MS, private val maxMs: Long = MAX_MS) {
    private var failures = 0
    private var notBeforeMs = 0L

    @Synchronized
    fun ready(nowMs: Long): Boolean = nowMs >= notBeforeMs

    @Synchronized
    fun failed(nowMs: Long, kind: FailureKind) {
        failures++
        val wait = if (kind == FailureKind.OLD_ENGINE) {
            maxMs
        } else {
            (baseMs shl (failures - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMs)
        }
        notBeforeMs = nowMs + wait
    }

    @Synchronized
    fun succeeded() {
        failures = 0
        notBeforeMs = 0L
    }

    companion object {
        const val BASE_MS = 30_000L
        const val MAX_MS = 10 * 60_000L
        private const val MAX_SHIFT = 10
    }
}

/**
 * Sends sealed conversation batches to the engine's `POST /api/device-logs/` as
 * `reason=conversation` (conversation-records ticket 03). The phone's sibling of the head unit's
 * `DriveLogUploader`, with the same request and the same rules:
 *
 * - **A batch is deleted only on a 2xx.** Offline, a refusal, a 5xx all leave it on disk, and the
 *   first failure stops the run (oldest goes first, so order is kept). The engine dedupes on
 *   `batch_id`, so a 2xx lost on the way back and its retry store one row.
 * - **No engine means nothing is sent and nothing is lost** ([SendOutcome.NoEngine]), said once
 *   per process through [say].
 * - **An old engine** (a 400 naming `reason`) is said once per process and the batch kept.
 * - The body is the batch file gzipped as-is: byte-identical to the lines the spool was handed.
 */
class ConversationUploader(
    private val spool: ConversationSpool,
    private val transport: ConversationTransport,
    private val meta: UploadMeta,
    private val say: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff(),
) {
    private val lock = Mutex()
    private var saidNoEngine = false
    private var saidOldEngine = false

    /** [manual] is "Send now": it ignores the backoff. Runs on [Dispatchers.IO]. */
    suspend fun upload(manual: Boolean): SendOutcome = lock.withLock {
        withContext(Dispatchers.IO) { run(manual) }
    }

    private suspend fun run(manual: Boolean): SendOutcome {
        val usable = transport.isUsable()
        if (!usable && !saidNoEngine) {
            saidNoEngine = true
            say("No engine is configured on this phone, so conversation logs stay on the phone. Nothing was sent.")
        }
        val due = manual || backoff.ready(now())
        if (usable && due) spool.seal()
        val batches = if (usable && due) spool.pending() else emptyList()
        return when {
            !usable -> SendOutcome.NoEngine
            !due -> SendOutcome.Waiting
            batches.isEmpty() -> SendOutcome.NothingToSend
            else -> sendAll(batches)
        }
    }

    /** Oldest first; the first failure stops the run and keeps that batch and every later one. */
    private suspend fun sendAll(batches: List<SpooledBatch>): SendOutcome {
        var lines = 0
        var sent = 0
        for (batch in batches) {
            val count = batch.lineCount()
            val result = transport.post(fieldsFor(batch), "${batch.id}.log.gz", gzip(batch.file.readBytes()))
            val failure = RetryRules.classify(result)
            if (failure == null) {
                spool.delete(batch)
                lines += count
                sent++
                continue
            }
            backoff.failed(now(), failure)
            if (failure == FailureKind.OLD_ENGINE && !saidOldEngine) {
                saidOldEngine = true
                say("The engine refused reason=conversation (an engine from before devicelogs 0003). Batches are kept.")
            }
            return SendOutcome.Failed(failure, RetryRules.sentence(failure, result), lines)
        }
        backoff.succeeded()
        return SendOutcome.Sent(lines, sent)
    }

    /** The multipart text fields, in the head unit's order (`devicelogs/views.py` UploadSerializer). */
    fun fieldsFor(batch: SpooledBatch): Map<String, String> = linkedMapOf(
        "batch_id" to batch.id,
        "device_label" to meta.deviceLabel,
        "app_version_name" to meta.versionName,
        "app_version_code" to meta.versionCode.toString(),
        "reason" to REASON,
        "window_start_ms" to batch.startMs.toString(),
        "window_end_ms" to batch.endMs.toString(),
    )

    companion object {
        const val PATH = "/api/device-logs/"
        const val REASON = "conversation"

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}
