package com.kevin.midnightai

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cold-start forensics for the intermittent "white screen after the engine was off ~10 min".
 *
 * Why this exists: the head unit has no ADB, so Crashlytics is the only window into a bad
 * start. A cold start after a vendor shutdown stacks a lot of main-thread work before the
 * first frame (Bluetooth binder calls, Keystore decrypts, ExoPlayer), but WHICH piece
 * stalls was a guess. This records, per process, how long each suspect took and when the first
 * frame actually landed, so the next white screen carries its own answer.
 *
 * Two kinds of keys, all in Crashlytics custom keys + `log()` breadcrumbs:
 *  - `mark_<name>_ms`: milliseconds since process start (`Process.getStartElapsedRealtime`)
 *    when the point was reached. Order and gaps show where the time went.
 *  - `span_<name>_ms`: how long one call took, on the thread that ran it.
 *
 * Observability must never crash the app, same rule as [MidnightEvents]: every entry point
 * swallows Throwable (Firebase not initialised, placeholder google-services.json, etc.).
 *
 * Keys are bounded (Crashlytics caps custom keys at 64 per process): span/mark names are a
 * fixed set chosen at the call sites, nothing here builds a key from runtime data.
 */
object StartupTrace {
    private const val TAG = "StartupTrace"

    /** `Process.getStartElapsedRealtime` is API 24 and minSdk is 24; guarded anyway so a vendor
     *  ROM that stubs it degrades to "time since this class loaded" instead of throwing. */
    private val processStartElapsed: Long = try {
        Process.getStartElapsedRealtime()
    } catch (_: Throwable) {
        SystemClock.elapsedRealtime()
    }

    private val activityCreates = AtomicInteger(0)
    private val firstFrameRecorded = AtomicBoolean(false)
    private val watchdogStarted = AtomicBoolean(false)

    // PhoneLinkManager.refresh() stats. Updated from its worker thread, read nowhere but here.
    private val refreshCount = AtomicInteger(0)
    @Volatile private var refreshMsMax = 0L

    // Non-fatal budget is split so a bad startup cannot spend all of it: stalls during startup
    // (before the first frame + STARTUP_WINDOW_MS) and stalls afterwards each get their own cap.
    private val startupStallReports = AtomicInteger(0)
    private val laterStallReports = AtomicInteger(0)
    private val stallReports = AtomicInteger(0)
    /** uptimeMillis of the first frame; 0 until it lands (startup phase then lasts indefinitely). */
    @Volatile private var firstFrameUptime = 0L
    @Volatile private var stallMsMax = 0L

    /** Milliseconds since this process started. */
    fun sinceProcessStartMs(): Long = SystemClock.elapsedRealtime() - processStartElapsed

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun key(k: String, v: Long) = safe { FirebaseCrashlytics.getInstance().setCustomKey(k, v) }

    private fun logLine(msg: String) {
        Log.d(TAG, msg)
        safe { FirebaseCrashlytics.getInstance().log(msg) }
    }

    /** Records that [name] was reached now, as ms since process start. */
    fun mark(name: String) = safe {
        val t = sinceProcessStartMs()
        key("mark_${name}_ms", t)
        logLine("startup mark $name +${t}ms")
    }

    /** Times [block] and records `span_<name>_ms`. Always runs [block] exactly once. */
    inline fun <T> span(name: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            recordSpan(name, SystemClock.elapsedRealtime() - t0)
        }
    }

    /** Public only because [span] is inline; call [span] instead. */
    fun recordSpan(name: String, ms: Long) = safe {
        key("span_${name}_ms", ms)
        logLine("startup span $name ${ms}ms (+${sinceProcessStartMs()}ms)")
    }

    /**
     * Process-level context, called first thing in Application.onCreate. Separates a reboot /
     * cold power-up (small `boot_elapsed_at_process_start_s`) from a process that was merely
     * killed and relaunched while the unit stayed up. `uptime_ms_at_process_start` excludes deep
     * sleep, so a big gap between the two means the unit was suspended, not off.
     */
    fun processStarted() = safe {
        val bootElapsedS = processStartElapsed / 1000
        val uptimeAtStart = SystemClock.uptimeMillis() - (SystemClock.elapsedRealtime() - processStartElapsed)
        key("boot_elapsed_at_process_start_s", bootElapsedS)
        key("uptime_ms_at_process_start", uptimeAtStart)
        logLine("startup process started: boot_elapsed=${bootElapsedS}s uptime_at_start=${uptimeAtStart}ms")
        mark("app_create_start")
    }

    /**
     * MainActivity.onCreate entry. A fresh process hits this once (`start_kind=cold_process`);
     * a second hit in the same process is an Activity recreate (`warm_recreate`), which is the
     * resumed-from-suspend side of the intermittent symptom.
     */
    fun activityCreateStart() = safe {
        val n = activityCreates.incrementAndGet()
        safe { FirebaseCrashlytics.getInstance().setCustomKey("start_kind", if (n == 1) "cold_process" else "warm_recreate") }
        key("activity_create_count", n.toLong())
        // Only the first create is marked: later ones would just overwrite the same key.
        if (n == 1) mark("activity_create_start")
    }

    /**
     * Call from the first composition. Records `startup_first_composition_ms`, then the
     * `startup_first_frame_ms` after the second Choreographer callback, i.e. once the frame that
     * contains the composed content has been scheduled and run, which is the closest cheap proxy
     * for "the driver stopped seeing the window background". Approximate to one vsync.
     * Must be called on the main thread (Choreographer is per-looper).
     */
    fun firstCompositionDone(onFirstFrame: (() -> Unit)? = null) {
        if (!firstFrameRecorded.compareAndSet(false, true)) return
        safe {
            key("startup_first_composition_ms", sinceProcessStartMs())
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback {
                choreographer.postFrameCallback {
                    val t = sinceProcessStartMs()
                    key("startup_first_frame_ms", t)
                    logLine("startup first frame +${t}ms")
                    firstFrameUptime = SystemClock.uptimeMillis()
                    safe { onFirstFrame?.invoke() }
                }
            }
        }
    }

    /** Called by PhoneLinkManager after each refresh pass (from its worker thread). */
    fun phoneLinkRefreshed(ms: Long) = safe {
        val n = refreshCount.incrementAndGet()
        key("phonelink_refresh_count", n.toLong())
        if (ms > refreshMsMax) {
            refreshMsMax = ms
            key("phonelink_refresh_ms_max", ms)
        }
    }

    /**
     * Main-thread stall detector. A daemon thread posts a tick to the main Looper and waits; if
     * the tick has not run after [STALL_THRESHOLD_MS] it snapshots the main thread's stack AT THAT
     * MOMENT (the stack of whatever is blocking, which is the whole point) and records it as a
     * non-fatal. Then it waits for the tick and records the total stall length.
     *
     * Gaps are measured on `uptimeMillis` (stops in deep sleep) so a device suspend between post
     * and run cannot masquerade as a stall. Capped at 3 non-fatals during startup (until first frame + 10s) and 3 afterwards
     * so a pathological start cannot flood the dashboard or the reporter. Idempotent; the thread
     * is a daemon and never holds a reference to anything but the main Looper.
     */
    fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        safe {
            val mainLooper = Looper.getMainLooper()
            val main = Handler(mainLooper)
            val t = Thread({ watchdogLoop(mainLooper, main) }, "startup-watchdog")
            t.isDaemon = true
            t.start()
        }
    }

    private fun watchdogLoop(mainLooper: Looper, main: Handler) {
        try {
            while (true) {
                val ran = AtomicBoolean(false)
                val posted = SystemClock.uptimeMillis()
                main.post { ran.set(true) }
                var reported = false
                while (!ran.get()) {
                    Thread.sleep(POLL_MS)
                    val gap = SystemClock.uptimeMillis() - posted
                    if (!reported && gap > STALL_THRESHOLD_MS) {
                        reported = true
                        reportStall(mainLooper, gap)
                    }
                }
                if (reported) {
                    val total = SystemClock.uptimeMillis() - posted
                    if (total > stallMsMax) {
                        stallMsMax = total
                        key("main_stall_ms_max", total)
                    }
                    key("main_stall_last_ms", total)
                    logLine("startup main stall ended after ${total}ms (+${sinceProcessStartMs()}ms)")
                }
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
            // Process teardown; nothing to clean up.
        } catch (_: Throwable) {
            // The watchdog dying is acceptable, taking the app with it is not.
        }
    }

    private fun reportStall(mainLooper: Looper, gapMs: Long) = safe {
        val n = stallReports.incrementAndGet()
        val inStartup = firstFrameUptime == 0L ||
            SystemClock.uptimeMillis() < firstFrameUptime + STARTUP_WINDOW_MS
        val budgetOk = if (inStartup) {
            startupStallReports.incrementAndGet() <= MAX_STALL_REPORTS_PER_PHASE
        } else {
            laterStallReports.incrementAndGet() <= MAX_STALL_REPORTS_PER_PHASE
        }
        key("main_stall_count", n.toLong())
        key("main_stall_gap_first_seen_ms", gapMs)
        logLine("startup main thread stalled >${gapMs}ms at +${sinceProcessStartMs()}ms")
        if (budgetOk) {
            val stack = mainLooper.thread.stackTrace
            val ex = MainThreadStall("main thread blocked >${gapMs}ms")
            ex.stackTrace = stack
            FirebaseCrashlytics.getInstance().recordException(ex)
        }
    }

    /** Carries the main thread's stack at stall time; the type name is what to filter on. */
    class MainThreadStall(message: String) : RuntimeException(message)

    private const val POLL_MS = 250L
    private const val STALL_THRESHOLD_MS = 2_000L
    private const val MAX_STALL_REPORTS_PER_PHASE = 3
    private const val STARTUP_WINDOW_MS = 10_000L
}
