package com.kevin.midnightai.diag

import android.content.Context
import android.os.Build
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.backend.engine.EngineHttpException
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why an upload ran. The strings are the engine's `reason` values (`devicelogs/models.py`). */
enum class UploadReason(val wire: String) {
    DRIVE_END("drive_end"),
    STARTUP("startup"),
    MANUAL("manual"),
    PERIODIC("periodic"),
    // The conversation spool (2026-10-28): LIVE_AUDIT and LIVE_LATENCY lines only, whatever the
    // trigger (session end, the periodic tick, startup, the Settings button). The reason names the
    // CONTENT here, so the engine can tell a conversation batch from a drive log.
    CONVERSATION("conversation"),
}

/** What an upload attempt did, in words the Settings row can show as-is. */
sealed interface UploadOutcome {
    val sentence: String

    data object NothingToSend : UploadOutcome {
        override val sentence = "Nothing waiting to upload."
    }

    /** No engine address or token: logs stay on the unit, nothing was sent. */
    data object NoEngine : UploadOutcome {
        override val sentence = "Not signed in to the engine, so logs stay on this unit. Nothing was sent."
    }

    data class Sent(val batches: Int) : UploadOutcome {
        override val sentence = if (batches == 1) "Uploaded 1 batch." else "Uploaded $batches batches."
    }

    /** [sent] batches went before this one failed; the failed batch and the rest are still on the
     * unit and will be retried. */
    data class Failed(val reason: String, val sent: Int) : UploadOutcome {
        override val sentence = "Upload failed: $reason Logs are kept here and will be retried."
    }
}

/** Where the last attempt's result is remembered, for the Settings row. */
interface UploadStatusSink {
    fun record(atMs: Long, ok: Boolean, sentence: String)
}

/** What the engine is told about this unit. */
data class UploadMeta(val deviceLabel: String, val versionName: String, val versionCode: Int)

/**
 * Sends sealed drive-log batches to the engine's `POST /api/device-logs/` (2026-10-15).
 *
 * **A batch is deleted only on a 2xx.** Offline, a refusal, a 5xx or a timeout all leave it on
 * disk for the next trigger, and the first failure stops the run (the oldest batch goes first, so
 * order is kept). The engine dedupes on `batch_id`, so a 2xx lost on the way back and a retry
 * stores one row, not two.
 *
 * **No token on file means nothing is sent** and nothing is lost ([UploadOutcome.NoEngine]).
 * Batches are sealed small ([DriveLogStore.DEFAULT_SEAL_AT_BYTES]) so a size refusal cannot
 * happen; if the engine still refuses one for another reason it stays and the reason is shown in
 * Settings rather than retried silently forever.
 *
 * Each body is redacted again on the way out ([LogRedactor]), so a batch written by an older
 * redactor is cleaned too.
 */
class DriveLogUploader(
    private val store: DriveLogStore,
    private val http: EngineHttp,
    private val meta: UploadMeta,
    private val status: UploadStatusSink,
    /** Pull the latest logcat into the store before sealing. Blocking; runs off the main thread. */
    private val snapshot: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun upload(reason: UploadReason): UploadOutcome = lock.withLock {
        val outcome = withContext(Dispatchers.IO) { run(reason) }
        // "Nothing to send" is not an attempt worth overwriting a real result with.
        if (outcome !is UploadOutcome.NothingToSend) {
            status.record(now(), ok = outcome is UploadOutcome.Sent, sentence = outcome.sentence)
        }
        outcome
    }

    private suspend fun run(reason: UploadReason): UploadOutcome {
        if (!http.isUsable()) return UploadOutcome.NoEngine
        runCatching { snapshot() }
        store.seal()
        val batches = store.pending()
        if (batches.isEmpty()) return UploadOutcome.NothingToSend
        var sent = 0
        for (batch in batches) {
            val body = gzip(batch.file.readLines().joinToString("\n", postfix = "\n") { LogRedactor.redact(it) })
            val result = http.postMultipart(
                path = PATH,
                fields = mapOf(
                    "batch_id" to batch.id,
                    "device_label" to meta.deviceLabel,
                    "app_version_name" to meta.versionName,
                    "app_version_code" to meta.versionCode.toString(),
                    "reason" to reason.wire,
                    "window_start_ms" to batch.startMs.toString(),
                    "window_end_ms" to batch.endMs.toString(),
                ),
                fileField = "file",
                fileName = "${batch.id}.log.gz",
                fileBytes = body,
                fileMimeType = "application/gzip",
            )
            if (result.isSuccess) {
                store.delete(batch)
                sent++
            } else {
                val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
                return UploadOutcome.Failed(failure?.sentence?.take(MAX_REASON_CHARS) ?: "unknown error.", sent)
            }
        }
        return UploadOutcome.Sent(sent)
    }

    companion object {
        const val PATH = "/api/device-logs/"
        private const val MAX_REASON_CHARS = 200
        private val lock = Mutex()

        fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        /** The production wiring: the shared store, the device's engine sign-in, prefs-backed status. */
        fun create(context: Context): DriveLogUploader? {
            val store = DriveLog.store() ?: return null
            return wire(context, store, DriveLogStatus(context), snapshot = { DriveLog.snapshotLogcat() })
        }

        /**
         * The conversation spool's uploader (2026-10-28): same endpoint, same delete-only-on-2xx
         * rule, no logcat snapshot (its lines are written straight to disk), and its own status so
         * it never overwrites the drive-log row in Settings. Always send with
         * [UploadReason.CONVERSATION].
         */
        fun createForConversation(context: Context): DriveLogUploader? {
            val store = DriveLog.conversationStore() ?: return null
            return wire(context, store, DriveLogStatus(context, DriveLogStatus.CONVERSATION_PREFS), snapshot = {})
        }

        private fun wire(
            context: Context,
            store: DriveLogStore,
            status: UploadStatusSink,
            snapshot: () -> Unit,
        ): DriveLogUploader {
            val config = EngineConfig(context)
            // A token saved after boot was not known at DriveLog.init; register it before any
            // batch is redacted for upload.
            LogRedactor.addLiteral(config.token())
            return DriveLogUploader(
                store = store,
                http = EngineHttp(config),
                meta = UploadMeta(
                    deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                ),
                status = status,
                snapshot = snapshot,
            )
        }
    }
}

/** SharedPreferences-backed last-upload record for the Settings row. */
class DriveLogStatus(context: Context, prefsName: String = PREFS) : UploadStatusSink {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun record(atMs: Long, ok: Boolean, sentence: String) {
        prefs.edit()
            .putLong(KEY_AT, atMs)
            .putBoolean(KEY_OK, ok)
            .putString(KEY_SENTENCE, sentence)
            .apply()
    }

    /** Epoch ms of the last attempt, or null if none has run. */
    fun lastAtMs(): Long? = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }

    fun lastOk(): Boolean = prefs.getBoolean(KEY_OK, false)

    fun lastSentence(): String? = prefs.getString(KEY_SENTENCE, null)

    companion object {
        private const val PREFS = "drive_log_status"
        const val CONVERSATION_PREFS = "conversation_log_status"
        private const val KEY_AT = "last_at_ms"
        private const val KEY_OK = "last_ok"
        private const val KEY_SENTENCE = "last_sentence"
    }
}
