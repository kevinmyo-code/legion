package com.kevin.legion.service.cascade

import android.content.Context
import android.os.PowerManager
import com.kevin.legion.BuildConfig
import com.kevin.legion.greeting.GreetingFactory
import com.kevin.legion.greeting.GreetingPackCache
import com.kevin.legion.service.LiveLatency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What runs BEFORE a conversation opens (voice-fast step 2, 2026-11-15): the prompt is prepared at
 * app start ([CascadePrep]), the greeting's facts pack is fetched then and every
 * [GreetingPackCache.TIMER_MS] while the screen is on ([GreetingFactory.prefetch]), and a wake
 * word's onset (a score past the detector's near-miss floor,
 * under the wake threshold) starts preparing it again and connecting to the laptop
 * ([LaptopPrewarm]); a wake that does not confirm closes that connection.
 *
 * No idle warm socket: the laptop serves ONE conversation and takes the slot at the WebSocket
 * upgrade, so an idle phone holding a socket would lock out every other client. A prewarmed socket
 * lives at most [LaptopPrewarm.HOLD_MS] unless a conversation takes it.
 */
internal object CascadeWarmup {
    @Volatile private var appContext: Context? = null

    /** A cascade conversation is open: onsets during it warm nothing (its own socket holds the slot). */
    @Volatile var conversationOpen = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Not on the app's first second: the prompt build reads the database and the engine. */
    private const val START_DELAY_MS = 8_000L

    /**
     * After a wake onset that did not become a wake, no new onset opens a laptop connection for this
     * long. Without it a voice hovering near the threshold (a TV, a similar word) connects and
     * closes back to back, each one taking the laptop's single conversation slot for a moment.
     * The prompt preparation is not held back: it opens nothing.
     */
    const val MISS_COOLDOWN_MS = 30_000L

    /** When the last onset ended without a wake (0: none yet). */
    @Volatile internal var lastMissAtMs = 0L

    /** True while [now] is inside the cooldown that follows a near miss. */
    internal fun missCooling(now: Long): Boolean = lastMissAtMs != 0L && now - lastMissAtMs < MISS_COOLDOWN_MS

    /** Called once by the controller at start. */
    fun install(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        KeptLineAudio.install(ctx)
        scope.launch {
            delay(START_DELAY_MS)
            CascadePrep.prepare(ctx, "start")
            GreetingFactory.prefetch(ctx, "start")
            // The greeting's facts pack, kept fresh while the screen is on (a dark screen asks
            // for nothing; the onset and the tap still refresh it).
            while (true) {
                delay(GreetingPackCache.TIMER_MS)
                if (screenOn(ctx)) GreetingFactory.prefetch(ctx, "timer")
            }
        }
    }

    private fun screenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /** The wake word's score rose past its onset floor (not yet the wake threshold). */
    fun onWakeOnset() {
        val ctx = appContext ?: return
        if (conversationOpen) return
        LiveLatency.log(LiveLatency.TAG, "wake_onset")
        CascadePrep.prepare(ctx, "onset")
        GreetingFactory.prefetch(ctx, "onset")
        if (missCooling(System.currentTimeMillis())) {
            LiveLatency.log(LiveLatency.TAG, "prewarm_skip reason=miss_cooldown")
        } else if (VoiceEngineSettings.engine(ctx) == VoiceEngine.LAPTOP) {
            LaptopPrewarm.start(ctx, "onset")
        }
    }

    /** The onset's run ended without a wake: let the laptop go. */
    fun onWakeMissed() {
        lastMissAtMs = System.currentTimeMillis()
        LaptopPrewarm.cancel("wake_missed")
    }
}

/**
 * One laptop connection opened ahead of the conversation that will use it (probe, then the socket's
 * hello/ready), taken by [CascadeSession] at open, closed if nobody takes it within [HOLD_MS].
 */
internal object LaptopPrewarm {
    sealed interface Opened {
        val probeMs: Long

        data class Ready(val connection: LaptopVoiceConnection, override val probeMs: Long) : Opened
        data class Refused(val token: String, override val probeMs: Long) : Opened
    }

    class Attempt(val startedAtMs: Long, val reason: String) {
        lateinit var result: Deferred<Opened>
            internal set

        /** Where a drop goes once a session has taken the connection (none before). */
        @Volatile var onDrop: ((String) -> Unit)? = null
    }

    /** A prewarmed socket nobody took within this is closed, so the laptop's one slot is free again. */
    const val HOLD_MS = 5_000L

    @Volatile private var pending: Attempt? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts probing and connecting unless an attempt is already in hand. Never throws. */
    @Suppress("ReturnCount") // already in hand, not set up, recently unreachable
    fun start(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (pending?.let { now - it.startedAtMs < HOLD_MS } == true) return
        val config = VoiceEngineSettings.laptop(context)
        if (!config.isComplete || CascadeSession.recentlyUnreachable(now)) return
        val a = Attempt(now, reason)
        a.result = scope.async { open(config, a) }
        pending = a
        LiveLatency.log(LiveLatency.TAG, "prewarm_start reason=$reason")
        scope.launch {
            val r = runCatching { a.result.await() }.getOrNull()
            LiveLatency.log(
                LiveLatency.TAG,
                "prewarm_ready ms=${System.currentTimeMillis() - a.startedAtMs} result=" +
                    when (r) {
                        is Opened.Ready -> "ready"
                        is Opened.Refused -> r.token
                        null -> "failed"
                    },
            )
            delay(HOLD_MS)
            if (pending === a) cancel("not_taken")
        }
    }

    private suspend fun open(config: LaptopVoiceConfig, a: Attempt): Opened {
        val probe = LaptopProbe.probe(config, CascadeTimings.PROBE_TIMEOUT_MS)
        val probeMs = when (probe) {
            is LaptopProbe.Result.Ready -> probe.ms
            is LaptopProbe.Result.Busy -> probe.ms
            is LaptopProbe.Result.Unreachable -> probe.ms
            else -> -1L
        }
        val refusal = CascadeRules.openRefusal(config.isComplete, turnModelsReady = true, probe)
        if (refusal != null) return Opened.Refused(refusal, probeMs)
        val c = LaptopVoiceConnection(
            connector = OkHttpWire(),
            config = config,
            clientName = "legion-phone/${BuildConfig.VERSION_CODE}",
            scope = scope,
            onDrop = { reason -> a.onDrop?.invoke(reason) },
        )
        return when (val r = c.open(CascadeTimings.OPEN_TIMEOUT_MS)) {
            is LaptopVoiceConnection.OpenResult.Failed -> Opened.Refused("connect_${r.reason}", probeMs)
            LaptopVoiceConnection.OpenResult.Ready -> Opened.Ready(c, probeMs)
        }
    }

    /** The attempt in hand, for the conversation opening now (and no one else). */
    fun take(): Attempt? {
        val a = pending
        pending = null
        val fresh = a?.takeIf { System.currentTimeMillis() - it.startedAtMs < HOLD_MS }
        if (a != null && fresh == null) release(a, "stale")
        fresh?.let {
            val age = System.currentTimeMillis() - it.startedAtMs
            LiveLatency.log(LiveLatency.TAG, "prewarm_taken age_ms=$age reason=${it.reason}")
        }
        return fresh
    }

    /** Drops the attempt in hand: its socket (if it opened) is closed once its open ends. */
    fun cancel(reason: String) {
        val a = pending ?: return
        pending = null
        val age = System.currentTimeMillis() - a.startedAtMs
        LiveLatency.log(LiveLatency.TAG, "prewarm_cancel reason=$reason age_ms=$age")
        release(a, reason)
    }

    /** Closes [a]'s socket once its open has ended (a taken attempt the session could not use, too). */
    fun release(a: Attempt, reason: String) {
        // Never cancel an open half-way (the socket would stay up); wait for it, then close it.
        scope.launch {
            val r = runCatching { a.result.await() }.getOrNull()
            (r as? Opened.Ready)?.connection?.close("prewarm_$reason")
        }
    }
}
