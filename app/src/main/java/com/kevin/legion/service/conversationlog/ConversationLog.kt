package com.kevin.legion.service.conversationlog

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kevin.legion.BuildConfig
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineOk
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.service.phonelog.PhoneLog
import com.kevin.legion.service.turn.VoiceTurnSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turns one conversation line into a spooled threadtime line (conversation-records ticket 03).
 * Pure apart from the [executor] it hands the write to, so a test drives it with a direct executor.
 *
 * The caller's thread only reads the clock, the pid and the tid and queues the write; formatting
 * and disk happen on [executor] (a single thread in production), so the audio and socket threads
 * never wait on a file. [enabled] is read per line: off writes nothing at all.
 */
class ConversationRecorder(
    private val spool: ConversationSpool,
    private val executor: Executor,
    private val enabled: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val pid: () -> Int = { android.os.Process.myPid() },
    private val tid: () -> Int = { android.os.Process.myTid() },
) {
    // Confined to the executor's thread; SimpleDateFormat is not thread safe.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun record(tag: String, message: String) {
        if (!enabled() || tag !in ConversationSpool.TAGS) return
        val atMs = now()
        val p = pid()
        val t = tid()
        executor.execute {
            runCatching {
                spool.append(ConversationSpool.threadtime(stamp.format(Date(atMs)), p, t, tag, message), atMs)
            }
        }
    }

    /** Runs [block] on the writer after every line queued before it. */
    fun afterQueuedWrites(block: () -> Unit) {
        executor.execute { runCatching(block) }
    }
}

/** Production [ConversationTransport]: the phone's engine sign-in through [EngineHttp]. */
class EngineConversationTransport(private val http: EngineHttp) : ConversationTransport {
    override fun isUsable(): Boolean = http.isUsable()

    override suspend fun post(fields: Map<String, String>, fileName: String, gzipBody: ByteArray): PostResult =
        http.postMultipart(
            path = ConversationUploader.PATH,
            fields = fields,
            fileField = "file",
            fileName = fileName,
            fileBytes = gzipBody,
            fileMimeType = "application/gzip",
        ).toPostResult()
}

private const val UNAUTHORIZED = 401

// Never produced by a POST today (classify only decodes a status); kept a server-side kind.
private const val MALFORMED = 599

/** An engine call's result as the transport-neutral [PostResult] the retry rules read. */
internal fun Result<EngineOk>.toPostResult(): PostResult = fold(
    onSuccess = { PostResult.Ok(it.status) },
    onFailure = { e ->
        when (val failure = (e as? EngineHttpException)?.failure) {
            is EngineFailure.Unauthorized -> PostResult.Http(UNAUTHORIZED, failure.sentence)
            is EngineFailure.Unreachable -> PostResult.Unreachable(failure.sentence)
            is EngineFailure.Refused -> PostResult.Http(failure.status, failure.body)
            is EngineFailure.Malformed -> PostResult.Http(MALFORMED, failure.sentence)
            null -> PostResult.Unreachable(e.message ?: "the request failed")
        }
    },
)

/**
 * The phone's conversation log (conversation-records ticket 03, 2026-10-28, Kevin: "all logs on
 * engine. both headunit and phone"). Every `LIVE_AUDIT`, `LIVE_LATENCY`, `LIVE_FOLLOWUP`,
 * `LIVE_TOOL_CANCEL` and `NAV_AUDIT` line also goes to a bounded spool under `files/conversationlog/`, and sealed
 * batches go to the household's engine as `reason=conversation`, readable with
 * `manage.py device_logs --reason conversation --render`.
 *
 * **Triggers:** the end of every Live session, every [UPLOAD_INTERVAL_MS] while one is live, app
 * start when anything is spooled, and "Send now" in Assistant settings. Offline is not an error:
 * the batch stays and the next trigger retries it ([ConversationUploader]).
 *
 * **Two switches** ([VoiceTurnSettings.keepConversationLog], [VoiceTurnSettings.auditLog]): the
 * first drives the spool and the upload, the second the logcat mirror of the LIVE_AUDIT lines.
 * Off-and-off means `LiveAudit` writes no line at all.
 *
 * **Not initialised means silent**, never a throw: JVM tests and anything before
 * `MidnightApplication.onCreate` reach [record] with no spool.
 */
@Suppress("TooManyFunctions") // the log's whole surface: init, switches, record, and each upload trigger
object ConversationLog {
    const val UPLOAD_INTERVAL_MS = 3 * 60_000L
    private const val STARTUP_DELAY_MS = 30_000L
    private const val SAY_TAG = "CONVERSATION_LOG"
    private val NO_STATUS = MutableStateFlow<String?>(null)

    @Volatile var keepAndSend = false
        private set

    @Volatile var logcatMirror = VoiceTurnSettings.AUDIT_LOG_DEFAULT
        private set

    /** The last upload attempt in words, for the Settings row. Null until one ran this process. */
    @Volatile var lastResult: String? = null
        private set

    /**
     * How the per-turn save is going, in words, for the Settings row ([TurnSaver.status]): turns kept
     * for later because the engine is out of reach, turns that could not be saved. Null until a turn
     * was queued this run, and a flow so the screen follows it.
     */
    val turnSaveStatus: StateFlow<String?> get() = turnSaver?.status ?: NO_STATUS

    @Volatile private var recorder: ConversationRecorder? = null
    @Volatile private var uploader: ConversationUploader? = null
    @Volatile private var spool: ConversationSpool? = null
    @Volatile private var turnSaver: TurnSaver? = null

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conversationlog").apply { isDaemon = true }
    }

    // Outlives any one Live session (its scopes are cancelled right after it closes); the
    // uploader's own lock serialises every upload.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null

    fun init(context: Context) {
        if (recorder != null) return
        val app = context.applicationContext
        applySettings(app)
        val s = ConversationSpool(File(app.filesDir, "conversationlog"))
        spool = s
        uploader = ConversationUploader(
            spool = s,
            transport = EngineConversationTransport(EngineHttp(EngineConfig(app))),
            meta = UploadMeta(
                deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
            say = { Log.i(SAY_TAG, it) },
            // Joins these records to the same run's raw logcat batches (2026-11-09).
            runId = PhoneLog.runId,
        )
        turnSaver = TurnSaver(
            box = TurnOutbox(
                File(app.filesDir, "turnsave/pending.jsonl"),
                engineTurnTransport(app),
            ),
            writer = writer,
            scope = scope,
            keep = { keepAndSend },
            log = { message, error -> if (error == null) Log.i(SAY_TAG, message) else Log.w(SAY_TAG, message, error) },
        )
        // applySettings ran before the saver existed: with the log off, clear what an earlier run left.
        turnSaver?.let { saver -> writer.execute { saver.clearIfOff() } }
        recorder = ConversationRecorder(s, writer, enabled = { keepAndSend })
    }

    /** The turn queue's transport: sends as the engine account signed in now, and says who that is. */
    private fun engineTurnTransport(app: Context): EngineTurnTransport {
        val config = EngineConfig(app)
        return EngineTurnTransport(EngineHttp(config)) { config.userId()?.let { "$it@${config.baseUrl()}" } }
    }

    /** Re-read both switches. Called at every session start and whenever Settings flips one. */
    fun applySettings(context: Context) {
        keepAndSend = VoiceTurnSettings.keepConversationLog(context)
        logcatMirror = VoiceTurnSettings.auditLog(context)
        if (!keepAndSend) {
            stopTicker()
            // Switched off: whatever is still queued is not to be sent when it is switched back on.
            turnSaver?.let { saver -> writer.execute { saver.discardQueued() } }
        }
    }

    /**
     * [applySettings], then whether `LiveAudit` should produce lines at all (some sink wants them).
     * A Live session reads this once at its start.
     */
    fun auditWantedAfterReading(context: Context): Boolean {
        applySettings(context)
        return keepAndSend || logcatMirror
    }

    /** One line under [tag]; anything but the conversation tags ([ConversationSpool.TAGS]) is ignored. */
    fun record(tag: String, message: String) {
        recorder?.record(tag, message)
    }

    /**
     * One finished turn, saved to the engine as it happens (memory chain ticket 450). Follows the
     * same switch as the log ([keepAndSend]). The write to disk and the send run off the caller's
     * thread; with the engine unreachable the turn waits in the [TurnOutbox] and [turnSaveStatus] says so.
     */
    fun saveTurn(turn: SavedTurn) {
        turnSaver?.save(turn)
    }

    private fun drainTurns() {
        val saver = turnSaver ?: return
        scope.launch { saver.drain() }
    }

    /** Swaps the turn saver (a JVM test has no application to [init] with); null puts it back to silent. */
    @VisibleForTesting
    internal fun installTurnSaverForTest(saver: TurnSaver?) {
        turnSaver = saver
    }

    /** `LiveAudit`'s session_start (true) and session_end (false). */
    fun onSession(started: Boolean) {
        if (started) {
            startTicker()
        } else {
            stopTicker()
            if (keepAndSend && uploader != null) scope.launch { uploadNow(manual = false) }
            if (keepAndSend) drainTurns()
            PhoneLog.onLiveSession(started = false)
        }
    }

    /**
     * A finished nav trip (NAV_AUDIT `trip_end`): send what is spooled, as a session end would. A trip
     * started by hand opens no Live session, so without this its lines waited for the next one or app
     * start. Respects the switch and the uploader's backoff; silent when not initialised.
     */
    fun uploadSoon() {
        if (!keepAndSend || uploader == null) return
        scope.launch { uploadNow(manual = false) }
    }

    /** At app start: after a short wait for the network, send anything an earlier run left. */
    fun uploadAtStart() {
        if (keepAndSend) {
            scope.launch {
                delay(STARTUP_DELAY_MS)
                turnSaver?.drain()
            }
        }
        if (spool?.hasContent() != true) return
        scope.launch {
            delay(STARTUP_DELAY_MS)
            uploadNow(manual = false)
        }
    }

    /** "Send now": waits for queued lines, seals, sends, ignoring the backoff. */
    suspend fun sendNow(): SendOutcome = uploadNow(manual = true) ?: SendOutcome.NothingToSend

    /** [ConversationSpool.rewrite] on the phone's spool; 0 when the log was never set up. */
    fun rewriteSpool(write: Boolean, line: (String) -> String): Int = spool?.rewrite(write, line) ?: 0

    private suspend fun uploadNow(manual: Boolean): SendOutcome? {
        val up = uploader
        val rec = recorder
        val wanted = manual || keepAndSend
        return if (up != null && rec != null && wanted) drainAndUpload(up, rec, manual) else null
    }

    private suspend fun drainAndUpload(
        up: ConversationUploader,
        rec: ConversationRecorder,
        manual: Boolean,
    ): SendOutcome {
        val drained = CompletableDeferred<Unit>()
        rec.afterQueuedWrites { drained.complete(Unit) }
        drained.await()
        val outcome = runCatching { up.upload(manual) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            SendOutcome.Failed(FailureKind.OFFLINE, "${it.message ?: it::class.simpleName}.", 0)
        }
        if (outcome is SendOutcome.Sent || outcome is SendOutcome.Failed) {
            lastResult = outcome.sentence
            Log.i(SAY_TAG, outcome.sentence)
        }
        return outcome
    }

    @Synchronized
    private fun startTicker() {
        if (!keepAndSend || uploader == null || ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(UPLOAD_INTERVAL_MS)
                turnSaver?.drain()
                uploadNow(manual = false)
            }
        }
    }

    @Synchronized
    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
