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
    @Volatile private var appContext: Context? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "drivelog").apply { isDaemon = true } }

    // Confined to the `io` thread; SimpleDateFormat is not thread safe.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** The store, for the uploader and the Settings row. Null before [init]. */
    fun store(): DriveLogStore? = store

    fun init(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        appContext = app
        val s = DriveLogStore(File(app.filesDir, "drivelog"))
        store = s
        io.execute {
            runCatching {
                registerKnownSecrets(app)
                rememberOwnPid(s)
            }
        }
    }

    /** Append one breadcrumb line. Cheap: captures the time now and queues the write. */
    fun breadcrumb(message: String) {
        val s = store ?: return
        val atMs = System.currentTimeMillis()
        val pid = android.os.Process.myPid()
        val tid = android.os.Process.myTid()
        io.execute {
            runCatching {
                val line = "${stamp.format(Date(atMs))} $pid $tid I Breadcrumb: ${message.replace('\n', ' ')}"
                s.append(listOf(LogRedactor.redact(line)))
            }
        }
    }

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
