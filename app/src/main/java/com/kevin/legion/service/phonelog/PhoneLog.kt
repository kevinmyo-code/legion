package com.kevin.legion.service.phonelog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.service.conversationlog.ConversationSpool
import com.kevin.legion.service.conversationlog.ConversationUploader
import com.kevin.legion.service.conversationlog.DailyBudget
import com.kevin.legion.service.conversationlog.EngineConversationTransport
import com.kevin.legion.service.conversationlog.FailureKind
import com.kevin.legion.service.conversationlog.SendOutcome
import com.kevin.legion.service.conversationlog.UploadMeta
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's own logs on the engine (2026-11-09, Kevin: "Send everything from the phone to the
 * engine so we can audit without adb"; 2026-10-28: "all logs on engine. both headunit and phone").
 *
 * - **Capture:** [LogcatCapture] streams every line this app's process writes (any tag, any
 *   library, native included), redacted by [LogcatRedactor], into a spool under
 *   `files/phonelog/logcat/` capped at [LOGCAT_MAX_BYTES] (oldest batch dropped first). A crash's
 *   stack goes synchronously to `files/phonelog/crash/` from the dying process ([CrashCapture]),
 *   and at the next start Android's exit records (ANR traces, low-memory kills; API 30+) and the
 *   previous run's unread logcat tail are added.
 * - **Upload:** `POST /api/device-logs/` through the conversation log's own uploader, reasons
 *   `logcat` and `crash`, gzip, `batch_id` as the idempotency key, plus [runId] so a batch joins to
 *   the conversation records of the same run. Logcat sends at most [DAILY_CAP_BYTES] compressed per
 *   day ([DailyBudget]); over it the rest waits and the spool's cap drops the oldest.
 * - **Triggers:** app start (after [STARTUP_DELAY_MS]), the end of every Live session, every
 *   [FOREGROUND_INTERVAL_MS] while the app is in the foreground, and [PhoneLogWorker] hourly on any
 *   network. Offline is not an error: a batch is deleted only on a 2xx.
 * - **Switch:** Settings > Permissions and diagnostics > Developer, default ON. Off stops the reader,
 *   sends nothing, and deletes what is spooled.
 *
 * Nothing here runs on the main thread or the voice path: the reader has its own thread, uploads run
 * on [Dispatchers.IO]. **Not initialised means silent**, never a throw: JVM tests reach the
 * callers with nothing set up.
 */
@Suppress("TooManyFunctions") // the log's whole surface: init, switch, capture, exit records, triggers
object PhoneLog {
    const val REASON_LOGCAT = "logcat"
    const val REASON_CRASH = "crash"
    const val LOGCAT_MAX_BYTES = 16L * 1024 * 1024
    const val LOGCAT_SEAL_BYTES = 1L * 1024 * 1024
    const val CRASH_MAX_BYTES = 2L * 1024 * 1024
    const val CRASH_SEAL_BYTES = 256L * 1024
    const val DAILY_CAP_BYTES = 4L * 1024 * 1024
    const val FOREGROUND_INTERVAL_MS = 15 * 60_000L
    private const val STARTUP_DELAY_MS = 45_000L
    private const val TAG = "PHONE_LOG"
    private const val PREFS = "phone_log"
    private const val KEY_ENABLED = "enabled"
    private const val EXIT_RECORDS = 16
    private const val MAX_TRACE_BYTES = 256 * 1024

    /** One per process start; sent with every batch this process uploads, conversation ones too. */
    val runId: String = UUID.randomUUID().toString()

    @Volatile var enabled = false
        private set

    /** The last upload in words, for the Settings row. Null until one ran this process. */
    @Volatile var lastResult: String? = null
        private set

    @Volatile private var logcatSpool: ConversationSpool? = null
    @Volatile private var crashSpool: ConversationSpool? = null
    @Volatile private var logcatUploader: ConversationUploader? = null
    @Volatile private var crashUploader: ConversationUploader? = null
    @Volatile private var state: RunState? = null
    @Volatile private var capture: LogcatCapture? = null
    @Volatile private var previous: RunState.Snapshot? = null
    @Volatile private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null
    private var handlerInstalled = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Called once from `MidnightApplication.onCreate`, on the main thread. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val dir = File(app.filesDir, "phonelog")
        val runState = RunState(File(dir, "run.properties"))
        state = runState
        previous = runState.read()
        runState.startRun(Process.myPid(), runId)
        val engine = EngineConfig(app)
        LogcatRedactor.addLiteral(engine.token())
        LogcatRedactor.addLiteral(GeminiKeyProvider.key())
        val transport = EngineConversationTransport(EngineHttp(engine))
        val meta = UploadMeta(
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        val logs = ConversationSpool(File(dir, "logcat"), LOGCAT_MAX_BYTES, LOGCAT_SEAL_BYTES)
        val crashes = ConversationSpool(File(dir, "crash"), CRASH_MAX_BYTES, CRASH_SEAL_BYTES)
        logcatSpool = logs
        crashSpool = crashes
        logcatUploader = ConversationUploader(
            spool = logs, transport = transport, meta = meta, say = { Log.i(TAG, it) },
            reason = REASON_LOGCAT, runId = runId,
            budget = DailyBudget(File(dir, "budget.txt"), DAILY_CAP_BYTES),
            prepare = ::redactAgain,
        )
        crashUploader = ConversationUploader(
            spool = crashes, transport = transport, meta = meta, say = { Log.i(TAG, it) },
            reason = REASON_CRASH, runId = runId, prepare = ::redactAgain,
        )
        enabled = isEnabled(app)
        installCrashHandler()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = startTicker()

                override fun onStop(owner: LifecycleOwner) = stopTicker()
            },
        )
        if (enabled) start(app, processStartMs())
    }

    /** The Settings switch. On starts capturing now; off stops, sends nothing, and deletes the spool. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        enabled = on
        if (on) {
            // From now, not from process start: what was logged while it was off stays unsent.
            appContext?.let { start(it, System.currentTimeMillis()) }
        } else {
            stopCapture()
            stopTicker()
            logcatSpool?.clear()
            crashSpool?.clear()
            PhoneLogWorker.cancel(context)
        }
    }

    /** `ConversationLog.onSession`: the end of a Live session sends what this run has so far. */
    fun onLiveSession(started: Boolean) {
        if (!started) uploadSoon()
    }

    fun uploadSoon() {
        if (!enabled || logcatUploader == null) return
        scope.launch { upload(manual = false) }
    }

    /** "Send now" in Settings: ignores the backoff (not the daily cap). */
    suspend fun sendNow(): SendOutcome = upload(manual = true) ?: SendOutcome.NothingToSend

    /** [PhoneLogWorker]'s call. */
    suspend fun uploadFromWorker() {
        upload(manual = false)
    }

    @Synchronized
    private fun start(app: Context, fromMs: Long) {
        if (capture != null) return
        val runState = state ?: return
        val logs = logcatSpool ?: return
        val reader = LogcatCapture(
            myPid = Process.myPid(),
            fromMs = fromMs,
            state = runState,
            sink = { lines, at -> logs.appendAll(lines, at) },
        )
        capture = reader
        val before = previous
        previous = null // recovered once per process, not on every switch flip
        Thread({
            runCatching {
                if (before != null) {
                    reader.recoverPreviousRun(before)
                    recordExits(app, before)
                }
            }
            reader.run()
        }, "phonelog-reader").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        PhoneLogWorker.schedule(app)
        scope.launch {
            delay(STARTUP_DELAY_MS)
            upload(manual = false)
        }
    }

    @Synchronized
    private fun stopCapture() {
        capture?.stop()
        capture = null
    }

    /** Android's own exit records newer than the last one sent (API 30+). */
    private fun recordExits(app: Context, before: RunState.Snapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val records = am.getHistoricalProcessExitReasons(null, 0, EXIT_RECORDS)
            .filter { it.timestamp > before.lastExitSeenMs }
            .sortedBy { it.timestamp }
        if (records.isEmpty()) return
        val lines = records.flatMap { info ->
            val trace = if (info.reason == CrashCapture.REASON_ANR) readTrace(info) else emptyList()
            CrashCapture.exitLines(
                CrashCapture.Exit(
                    timestampMs = info.timestamp, pid = info.pid, processName = info.processName,
                    reason = info.reason, status = info.status, importance = info.importance,
                    pssKb = info.pss, rssKb = info.rss, description = info.description, trace = trace,
                ),
                previousRunId = before.runId.takeIf { info.pid.toString() == before.pid },
            )
        }
        crashSpool?.appendAll(lines, System.currentTimeMillis())
        state?.exitSeen(records.last().timestamp)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: android.app.ApplicationExitInfo): List<String> = runCatching {
        info.traceInputStream?.use { stream ->
            val bytes = ByteArray(MAX_TRACE_BYTES)
            var read = 0
            while (read < bytes.size) {
                val n = stream.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, Charsets.UTF_8).lines()
        }
    }.getOrNull() ?: emptyList()

    /**
     * Wraps the current default handler: the stack goes to the crash spool on this (dying) thread,
     * then the previous handler runs exactly as before. Installed whatever the switch says, writing
     * only while it is on, so flipping it on needs no restart.
     */
    @Synchronized
    private fun installCrashHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (enabled) {
                    crashSpool?.appendAll(
                        CrashCapture.fatalLines(
                            throwable, thread.name, Process.myPid(), Process.myTid(), runId, System.currentTimeMillis(),
                        ),
                        System.currentTimeMillis(),
                    )
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    private fun startTicker() {
        if (!enabled || ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(FOREGROUND_INTERVAL_MS)
                upload(manual = false)
            }
        }
    }

    @Synchronized
    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Crash batches first (small, and the reason an audit is run), then logcat. */
    private suspend fun upload(manual: Boolean): SendOutcome? {
        val logs = logcatUploader ?: return null
        val crashes = crashUploader ?: return null
        if (!enabled) return null
        val crash = runCatching { crashes.upload(manual) }.getOrElse { failed(it) }
        val logcat = runCatching { logs.upload(manual) }.getOrElse { failed(it) }
        val shown = when {
            logcat is SendOutcome.NothingToSend -> crash
            else -> logcat
        }
        if (shown !is SendOutcome.NothingToSend && shown !is SendOutcome.Waiting) {
            lastResult = shown.sentence
            Log.i(TAG, shown.sentence)
        }
        return shown
    }

    private fun failed(t: Throwable): SendOutcome {
        if (t is kotlinx.coroutines.CancellationException) throw t
        return SendOutcome.Failed(FailureKind.OFFLINE, "${t.message ?: t::class.simpleName}.", 0)
    }

    /** Batches are redacted when written; this runs the current redactor over them again on the way out. */
    private fun redactAgain(bytes: ByteArray): ByteArray =
        String(bytes, Charsets.UTF_8).split('\n')
            .joinToString("\n") { if (it.isEmpty()) it else LogcatRedactor.redactLine(it) }
            .toByteArray(Charsets.UTF_8)

    /** The wall-clock time this process started, so the reader backfills from its first line. */
    private fun processStartMs(): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
}
