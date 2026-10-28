package com.kevin.midnightai.diag

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kevin.midnightai.backend.engine.EngineConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The head unit's own log, kept on disk so it can be uploaded after a drive (Kevin, 2026-10-15:
 * "so u can read exactly whats happening there... since we cant get adb"). ADB is impossible on
 * the unit and Crashlytics breadcrumbs only ride along with a crash, so this keeps two streams in
 * a bounded rolling file ([DriveLogStore]):
 *
 * 1. **Every [com.kevin.midnightai.MidnightEvents] breadcrumb**, appended as it happens (the
 *    Crashlytics call is mirrored here by [CrashlyticsTee]).
 * 2. **This app's own logcat**, snapshotted by [snapshotLogcat] every few minutes while the
 *    service runs, because the logcat ring buffer is small and the interesting minute is gone
 *    by the time anyone asks.
 *
 * Everything passes [LogRedactor] before it touches disk. All file work runs on ONE background
 * thread, so a breadcrumb call from the main thread or a hot audio path costs a queue post.
 *
 * **Not initialised means silent**, never a throw: unit tests that call `MidnightEvents` and any
 * path before `MidnightApplication.onCreate` reach [breadcrumb] with no store, and observability
 * must not crash the app it observes.
 */
object DriveLog {
    @Volatile private var store: DriveLogStore? = null
    @Volatile private var conversationStore: DriveLogStore? = null
    @Volatile private var appContext: Context? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "drivelog").apply { isDaemon = true } }

    // Confined to the `io` thread; SimpleDateFormat is not thread safe.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** The store, for the uploader and the Settings row. Null before [init]. */
    fun store(): DriveLogStore? = store

    /**
     * The conversation spool (2026-10-28): [ConvoAudit] and [ConvoLatency] lines only, in their own
     * bounded store so a batch uploads as `reason=conversation` with nothing else in it and the
     * engine's `device_logs --render` reads it whole. Same bounds and same delete-only-on-2xx rule
     * as the drive log. Null before [init].
     */
    fun conversationStore(): DriveLogStore? = conversationStore

    fun init(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        appContext = app
        val s = DriveLogStore(File(app.filesDir, "drivelog"))
        conversationStore = DriveLogStore(File(app.filesDir, "convolog"))
        store = s
        io.execute {
            runCatching {
                registerKnownSecrets(app)
                rememberOwnPid(s)
            }
        }
    }

    /** Append one breadcrumb line. Cheap: captures the time now and queues the write. */
    fun breadcrumb(message: String) = write(store, "Breadcrumb", message)

    /**
     * Append one conversation line under [tag] (`LIVE_AUDIT` or `LIVE_LATENCY`) to the
     * conversation spool, logcat-shaped so `tools/voice_audit.py` parses it as a capture. Written
     * straight to disk, never through logcat: a unit whose logcat is unreadable keeps every line.
     */
    fun conversation(tag: String, message: String) = write(conversationStore, tag, message)

    /** Runs [block] on the log thread after every write queued before it. */
    fun afterQueuedWrites(block: () -> Unit) {
        io.execute { runCatching(block) }
    }

    private fun write(target: DriveLogStore?, tag: String, message: String) {
        val s = target ?: return
        val atMs = System.currentTimeMillis()
        val pid = android.os.Process.myPid()
        val tid = android.os.Process.myTid()
        io.execute {
            runCatching {
                s.append(listOf(LogRedactor.redact(threadtime(stamp.format(Date(atMs)), pid, tid, tag, message))))
            }
        }
    }

    /** One `logcat -v threadtime`-shaped line: `MM-dd HH:mm:ss.SSS pid tid I Tag: message`. */
    internal fun threadtime(stamp: String, pid: Int, tid: Int, tag: String, message: String): String =
        "$stamp $pid $tid I $tag: ${message.replace('\n', ' ')}"

    /**
     * Copy new lines from this app's logcat into the store. Blocks while `logcat` runs (a second
     * or so), so call it from a background coroutine or [snapshotLogcatAsync]. Returns how many
     * lines were added, or -1 when logcat could not be read (reported by the caller as a
     * breadcrumb, not thrown).
     */
    @Synchronized
    fun snapshotLogcat(): Int {
        val s = store ?: return -1
        return runCatching {
            val raw = readLogcat() ?: return -1
            val pids = (s.readState(PIDS_STATE)?.split(',')?.filter { it.isNotBlank() }
                ?: listOf(android.os.Process.myPid().toString())).toSet()
            val ours = LogcatSnapshot.ours(raw, pids)
            val fresh = LogcatSnapshot.newSince(ours, s.readState(ANCHOR_STATE))
            if (ours.isNotEmpty()) s.writeState(ANCHOR_STATE, ours.last())
            if (fresh.isNotEmpty()) s.append(fresh.map(LogRedactor::redact))
            fresh.size
        }.getOrDefault(-1)
    }

    /** [snapshotLogcat] on the log thread, so the caller never waits. */
    fun snapshotLogcatAsync() {
        io.execute { snapshotLogcat() }
    }

    private fun readLogcat(): List<String>? {
        val process = ProcessBuilder("logcat", "-d", "-t", LOGCAT_TAIL_LINES.toString(), "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        // `Process.waitFor(timeout)` is API 26; minSdk is 24. A watchdog thread covers a logcat
        // that never returns.
        val watchdog = Thread {
            try {
                Thread.sleep(LOGCAT_TIMEOUT_MS)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        return try {
            process.inputStream.bufferedReader().use { it.readLines() }
        } catch (_: java.io.IOException) {
            null
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
    }

    private fun rememberOwnPid(s: DriveLogStore) {
        val mine = android.os.Process.myPid().toString()
        val known = s.readState(PIDS_STATE)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (mine in known) return
        s.writeState(PIDS_STATE, (known + mine).takeLast(MAX_REMEMBERED_PIDS).joinToString(","))
    }

    /** Exact values to strip wherever they appear. Today: the engine device token. The Gemini and
     * Spotify values are covered by shape (`AIza...`, 32-hex) in [LogRedactor]. */
    private fun registerKnownSecrets(context: Context) {
        LogRedactor.addLiteral(EngineConfig(context).token())
    }

    private const val ANCHOR_STATE = "logcat_anchor"
    private const val PIDS_STATE = "own_pids"
    private const val MAX_REMEMBERED_PIDS = 20
    private const val LOGCAT_TAIL_LINES = 5000
    private const val LOGCAT_TIMEOUT_MS = 8_000L
}

/**
 * Stands in for [FirebaseCrashlytics] inside `MidnightEvents`: every call goes to the drive log
 * first, then on to Crashlytics unchanged. One edit in `MidnightEvents` (`private val c get() =
 * CrashlyticsTee`) instead of 67 call sites, so a merge with other edits to that file stays easy.
 *
 * The drive-log write comes FIRST so a Firebase that is not ready (it throws; `MidnightEvents.safe`
 * swallows) still leaves the line in the log.
 */
object CrashlyticsTee {
    private val crashlytics get() = FirebaseCrashlytics.getInstance()

    fun log(message: String) {
        DriveLog.breadcrumb(message)
        crashlytics.log(message)
    }

    fun recordException(e: Throwable) {
        val top = e.stackTrace.take(3).joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" }
        DriveLog.breadcrumb("nonfatal ${e.javaClass.simpleName}: ${e.message} [$top]")
        crashlytics.recordException(e)
    }

    fun setCustomKey(key: String, value: String) {
        DriveLog.breadcrumb("key $key=$value")
        crashlytics.setCustomKey(key, value)
    }

    fun setCustomKey(key: String, value: Boolean) {
        DriveLog.breadcrumb("key $key=$value")
        crashlytics.setCustomKey(key, value)
    }

    fun setCustomKey(key: String, value: Int) {
        DriveLog.breadcrumb("key $key=$value")
        crashlytics.setCustomKey(key, value)
    }

    fun setCustomKey(key: String, value: Long) {
        DriveLog.breadcrumb("key $key=$value")
        crashlytics.setCustomKey(key, value)
    }
}
