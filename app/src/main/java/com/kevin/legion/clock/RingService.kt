package com.kevin.legion.clock

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import com.kevin.legion.ui.clock.RingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ring (ticket 02, ADR 0079): a foreground service that plays the alarm sound and vibrates,
 * with a notification carrying a full-screen intent to [RingActivity] over the lock screen. When
 * full-screen is refused (Android 14+, `canUseFullScreenIntent`), the same notification arrives
 * heads-up with its buttons.
 *
 * Type `systemExempted` on Android 14+ (allowed for an app holding an exact-alarm permission),
 * `mediaPlayback` below. Started from [ClockFireReceiver], which an exact alarm exempts from the
 * background-start ban. Direct-boot aware.
 *
 * Several things can ring at once (two timers); the newest is on the screen and notification.
 * Each auto-silences after [ClockDefaults.AUTO_SILENCE_MIN]. Stopping is [RingControl]'s: this
 * service only plays, shows and logs ([RingAudit]) each start and stop with its reason.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val sound by lazy { soundFactory(this) }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let(::requestOf)
        if (request == null) {
            // A restart with no ring to play: nothing to show, so do not linger.
            if (ringing.value.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        add(request)
        return START_NOT_STICKY
    }

    private fun add(request: RingRequest) {
        if (ringing.value.none { it.key() == request.key() }) {
            RingAudit.started(request.kind)
            if (!RingNotification.canShow(this)) RingAudit.blocked(request.kind)
        }
        mutable.value = ringing.value.filterNot { it.key() == request.key() } + request
        startInForeground(RingNotification.build(this, request))
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legion:clock-ring")
                .apply { acquire((ClockDefaults.AUTO_SILENCE_MIN + 1) * ClockDefaults.MINUTE_MS) }
        }
        sound.start()
        val after = ClockDefaults.AUTO_SILENCE_MIN * ClockDefaults.MINUTE_MS
        handler.postDelayed({ silence(request) }, request.key(), after)
    }

    /** Rang out unanswered: an alarm is dismissed as missed, a timer is finished, an alert posted. */
    private fun silence(request: RingRequest) = RingControl.stop(this, request, RingStopReason.TIMEOUT)

    private fun remove(kind: RingKind, id: Int) {
        val key = RingRequest(kind, id, "").key()
        val reason = reasons.remove(key) ?: RingStopReason.CLOCK
        if (ringing.value.none { it.key() == key }) return
        RingAudit.stopped(kind, reason)
        handler.removeCallbacksAndMessages(key)
        mutable.value = ringing.value.filterNot { it.key() == key }
        val next = ringing.value.lastOrNull()
        if (next != null) {
            startInForeground(RingNotification.build(this, next))
            return
        }
        sound.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sound.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        mutable.value = emptyList()
        if (current === this) current = null
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        runCatching { ServiceCompat.startForeground(this, ClockNotifier.RING_ID, n, type) }
            .recoverCatching {
                // systemExempted refused (no exact-alarm grant): mediaPlayback is still a ring.
                Log.w(TAG, "systemExempted refused, ringing as mediaPlayback", it)
                val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                ServiceCompat.startForeground(this, ClockNotifier.RING_ID, n, media)
            }
            .onFailure { Log.e(TAG, "could not start the ring in the foreground", it) }
    }

    companion object {
        private const val TAG = "AlfredClock"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"

        /** Test seam: what plays the ring. */
        internal var soundFactory: (Context) -> RingSound = ::AlarmSound

        @Volatile private var current: RingService? = null
        private val mutable = MutableStateFlow<List<RingRequest>>(emptyList())

        /** What is ringing now, newest last. The ring screen shows the newest and closes when empty. */
        val ringing: StateFlow<List<RingRequest>> = mutable.asStateFlow()

        fun start(context: Context, r: RingRequest) {
            ContextCompat.startForegroundService(context, intent(context, r))
        }

        /** The start intent for [r]; a test hands it to the service it builds. */
        internal fun intent(context: Context, r: RingRequest): Intent = Intent(context, RingService::class.java)
            .putExtra(EXTRA_KIND, r.kind.name).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_LABEL, r.label)

        /** Why each ring is about to stop, set by [RingControl] before it asks; unset is [RingStopReason.CLOCK]. */
        private val reasons = ConcurrentHashMap<String, RingStopReason>()

        internal fun noteReason(kind: RingKind, id: Int, reason: RingStopReason) {
            reasons[RingRequest(kind, id, "").key()] = reason
        }

        /** Stops one ring. Runs on the service's main thread; a no-op when it is not ringing. */
        fun stop(kind: RingKind, id: Int) {
            val service = current ?: run {
                reasons.remove(RingRequest(kind, id, "").key())
                return
            }
            Handler(Looper.getMainLooper()).post { service.remove(kind, id) }
        }

        private fun requestOf(i: Intent): RingRequest? {
            val kind = runCatching { RingKind.valueOf(i.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull()
                ?: return null
            return RingRequest(kind, i.getIntExtra(EXTRA_ID, -1), i.getStringExtra(EXTRA_LABEL).orEmpty())
        }

        /** Interned: a Handler matches message tokens by identity. */
        private fun RingRequest.key(): String = "${kind.name}:$id".intern()
    }
}

/** The ringer the controller holds. */
class AndroidRinger(private val context: Context) : Ringer {
    override fun ring(request: RingRequest) = RingService.start(context.applicationContext, request)
    override fun stop(kind: RingKind, id: Int) = RingService.stop(kind, id)
}
