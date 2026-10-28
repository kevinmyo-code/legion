package com.kevin.legion.service.turn

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone side of turn-taking for one Live session: Silero over every mic buffer, the
 * [EndOfTurnPolicy] for activityStart/activityEnd, the [BargeInGate] while Alfred speaks, Smart Turn
 * checks on a dedicated thread, and what audio reaches the socket.
 *
 * [GeminiLiveSession] owns one when [TurnConfig.manualActivity] or [TurnConfig.bargeIn] is on, hands
 * it each mic buffer through [feed] instead of forwarding the buffer itself, and acts on [Sink]
 * calls. Everything that touches the policy runs under one lock, so an activityEnd sent from the
 * inference thread can never interleave with audio the mic thread is forwarding.
 *
 * Forwarding:
 * - manual activity: audio goes up only inside an activity; activityStart is followed by the
 *   [PRE_ROLL_MS] ring, so the 200 ms speech-start confirmation clips nothing.
 * - server VAD with barge-in: everything goes up except while the reply is audible; a barge-in
 *   sends the ring first so the server's VAD hears the onset.
 */
internal class TurnTaker(
    config: TurnConfig,
    private val silero: SpeechProbStream,
    private val smartTurn: TurnEndModel?,
    private val sink: Sink,
) {
    interface Sink {
        fun sendActivityStart()
        fun sendAudio(pcm: ByteArray, offset: Int, length: Int)
        fun sendActivityEnd()
        /** Local speech onset confirmed (or barge-in): a user turn has begun. */
        fun onUserTurnStart(bargeIn: Boolean)
        fun onEndOfTurn(end: EndOfTurnPolicy.Action.EndActivity)
        fun onBargeIn(fire: BargeInGate.Event.Fire)
        fun onBargeInDisabled(selfInterrupts: Int)
        /** Silero heard speech while the reply was not playing (the follow-up window's local signal). */
        fun onLocalSpeech()
        fun onSileroFailed(reason: String)
        fun onSmartTurnFailed(reason: String)
        fun log(tag: String, message: String)
    }

    private val lock = Any()
    val manual = config.manualActivity
    @Volatile var bargeIn = config.bargeIn
        private set
    private val policy = EndOfTurnPolicy(smartAvailable = config.smartTurn && smartTurn != null)
    private val gate = BargeInGate()

    private val ring = ByteArray(PRE_ROLL_BYTES)
    private var ringFill = 0
    private var ringPos = 0
    private val floatRing = FloatArray(FLOAT_RING)
    private var floatPos = 0
    private val window = FloatArray(SileroVad.WINDOW)
    private var windowFill = 0

    private var turnAudio = FloatArray(INITIAL_TURN)
    private var turnLen = 0
    private var turnKey = Any()

    private var bargedThisReply = false
    private var failed = false

    val inActivity: Boolean get() = synchronized(lock) { policy.inActivity }

    /** A reply started playing (first model audio). */
    fun onReplyStart() = synchronized(lock) {
        bargedThisReply = false
        if (bargeIn) gate.onReplyStart()
    }

    /** The reply finished playing, or was flushed. */
    fun onReplyEnd() = synchronized(lock) { gate.onReplyEnd() }

    /** Barge-in off for the rest of the session (Bluetooth connected, self-interrupts). */
    fun disableBargeIn() = synchronized(lock) {
        bargeIn = false
        gate.onReplyEnd()
    }

    /** Forget any turn in progress (mic stopped, session closing). Sends nothing. */
    fun reset() = synchronized(lock) {
        policy.reset()
        windowFill = 0
        silero.reset()
    }

    /**
     * One mic buffer (16 kHz PCM16 LE). [playing] is true while the reply is audible. Returns the
     * bytes forwarded to the socket.
     */
    fun feed(pcm: ByteArray, n: Int, playing: Boolean): Int = synchronized(lock) {
        if (failed) return 0
        writeRing(pcm, n)
        val wasActive = policy.inActivity
        var started = false
        var barged = false
        var ended = false
        var i = 0
        while (i + 1 < n) {
            val s = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort()
            window[windowFill++] = s / 32768f
            i += 2
            if (windowFill == SileroVad.WINDOW) {
                windowFill = 0
                val outcome = processWindow(playing) ?: return 0
                if (outcome and STARTED != 0) started = true
                if (outcome and BARGED != 0) barged = true
                if (outcome and ENDED != 0) ended = true
            }
        }
        forward(pcm, n, playing, wasActive, started, barged, ended)
    }

    private fun processWindow(playing: Boolean): Int? {
        var level = 0
        for (v in window) {
            val a = kotlin.math.abs((v * 32768f).toInt())
            if (a > level) level = a
        }
        val prob = try {
            silero.prob(window)
        } catch (t: Throwable) {
            failed = true
            if (policy.inActivity) sink.sendActivityEnd()
            policy.reset()
            sink.onSileroFailed("${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        appendFloatRing()
        if (policy.inActivity) appendTurn(window, 0, window.size)
        if (prob >= EndOfTurnPolicy.START_PROB && !playing) sink.onLocalSpeech()
        var outcome = 0
        if (bargeIn) {
            when (val ev = gate.onWindow(prob, level, playing)) {
                is BargeInGate.Event.Fire -> {
                    bargedThisReply = true
                    outcome = outcome or BARGED
                    sink.log(
                        BARGE_TAG,
                        "fire prob=${"%.2f".format(java.util.Locale.US, ev.prob)} level=${ev.level} " +
                            "echo_floor=${ev.echoFloor} threshold=${ev.threshold} sustain_ms=${ev.sustainMs}",
                    )
                    if (manual) {
                        policy.forceStart()?.let {
                            beginTurnAudio(BargeInGate.SUSTAIN_MS / EndOfTurnPolicy.WINDOW_MS + 1)
                            outcome = outcome or STARTED
                        }
                    }
                    sink.onBargeIn(ev)
                    sink.onUserTurnStart(bargeIn = true)
                }
                is BargeInGate.Event.Verdict -> {
                    sink.log(
                        BARGE_TAG,
                        "verdict self_interrupt=${ev.selfInterrupt} speech_after_ms=${ev.speechMsAfter} " +
                            "fire_level=${ev.fireLevel} fire_threshold=${ev.fireThreshold} " +
                            "self_interrupts=${ev.selfInterrupts} disabled=${ev.nowDisabled}",
                    )
                    if (ev.nowDisabled && bargeIn) {
                        bargeIn = false
                        sink.onBargeInDisabled(ev.selfInterrupts)
                    }
                }
                null -> Unit
            }
        }
        // While the reply is audible the echo is speech too: only the gate may start a turn.
        if (manual && (!playing || policy.inActivity)) {
            outcome = outcome or handle(policy.onWindow(prob))
        }
        return outcome
    }

    /** Runs [actions] from the policy. Must hold [lock]. Returns STARTED/ENDED bits. */
    private fun handle(actions: List<EndOfTurnPolicy.Action>): Int {
        var out = 0
        for (a in actions) {
            when (a) {
                is EndOfTurnPolicy.Action.StartActivity -> {
                    beginTurnAudio(a.onsetWindows)
                    out = out or STARTED
                    sink.onUserTurnStart(bargeIn = false)
                }
                is EndOfTurnPolicy.Action.RequestCheck -> requestCheck(a)
                is EndOfTurnPolicy.Action.EndActivity -> {
                    sink.sendActivityEnd()
                    sink.onEndOfTurn(a)
                    out = out or ENDED
                }
                is EndOfTurnPolicy.Action.SpeechResumed -> Unit
            }
        }
        return out
    }

    private fun forward(
        pcm: ByteArray,
        n: Int,
        playing: Boolean,
        wasActive: Boolean,
        started: Boolean,
        barged: Boolean,
        ended: Boolean,
    ): Int {
        if (manual) {
            return when {
                // activityStart goes out here, before the pre-roll, so the onset lands inside it.
                started -> {
                    sink.sendActivityStart()
                    sendRing()
                }
                // An end inside this buffer was sent from handle(); the audio before it is in the
                // turn already, and audio after it must not open a new one.
                wasActive && !ended -> {
                    sink.sendAudio(pcm, 0, n)
                    n
                }
                else -> 0
            }
        }
        // Server VAD with barge-in.
        return when {
            barged -> sendRing()
            !playing || bargedThisReply -> {
                sink.sendAudio(pcm, 0, n)
                n
            }
            else -> 0
        }
    }

    private fun requestCheck(a: EndOfTurnPolicy.Action.RequestCheck) {
        val model = smartTurn ?: return
        val audio = turnAudio
        val len = turnLen
        val key = turnKey
        val asked = System.nanoTime()
        INFERENCE.execute {
            try {
                val t0 = System.nanoTime()
                val features = MEL.extractTurn(audio, len, key)
                val frames = MEL.lastFramesComputed
                val t1 = System.nanoTime()
                val score = model.probability(features)
                val t2 = System.nanoTime()
                synchronized(lock) {
                    sink.log(
                        LATENCY_TAG,
                        "st_score=${"%.3f".format(java.util.Locale.US, score)} st_check=${a.checkId} " +
                            "st_asked_at_ms=${a.silenceMs} st_arrive_ms=${policy.currentSilenceMs} " +
                            "st_queue_ms=${(t0 - asked) / 1_000_000} st_feat_ms=${(t1 - t0) / 1_000_000} " +
                            "st_infer_ms=${(t2 - t1) / 1_000_000} frames=$frames",
                    )
                    handle(policy.onCheckResult(a.checkId, score))
                }
            } catch (t: Throwable) {
                synchronized(lock) { handle(policy.onCheckFailed(a.checkId)) }
                sink.onSmartTurnFailed("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun writeRing(pcm: ByteArray, n: Int) {
        for (i in 0 until n) {
            ring[ringPos] = pcm[i]
            ringPos = (ringPos + 1) % ring.size
        }
        ringFill = minOf(ring.size, ringFill + n)
    }

    private fun sendRing(): Int {
        if (ringFill == 0) return 0
        val out = ByteArray(ringFill)
        val start = (ringPos - ringFill + ring.size) % ring.size
        for (i in 0 until ringFill) out[i] = ring[(start + i) % ring.size]
        sink.sendAudio(out, 0, out.size)
        return out.size
    }

    private fun appendFloatRing() {
        for (v in window) {
            floatRing[floatPos] = v
            floatPos = (floatPos + 1) % floatRing.size
        }
    }

    /** Seeds the turn with the onset plus Smart Turn's 200 ms pre-roll, from the float ring. */
    private fun beginTurnAudio(onsetWindows: Int) {
        turnKey = Any()
        turnLen = 0
        val want = minOf(floatRing.size, onsetWindows * SileroVad.WINDOW + MODEL_PRE_ROLL_SAMPLES)
        val start = (floatPos - want + floatRing.size) % floatRing.size
        val seed = FloatArray(want) { floatRing[(start + it) % floatRing.size] }
        appendTurn(seed, 0, want)
    }

    private fun appendTurn(src: FloatArray, from: Int, count: Int) {
        if (turnLen + count > MAX_TURN) {
            // Keep the newest 8 s: older audio is outside Smart Turn's window anyway. A new array and
            // key, so a check still running keeps reading the old one untouched.
            val keep = WhisperLogMel.N_SAMPLES
            val next = FloatArray(maxOf(INITIAL_TURN, keep * 2))
            System.arraycopy(turnAudio, turnLen - keep, next, 0, keep)
            turnAudio = next
            turnLen = keep
            turnKey = Any()
        }
        if (turnLen + count > turnAudio.size) {
            // A new array rather than growing in place: a check running on the old one keeps it.
            turnAudio = turnAudio.copyOf(maxOf(turnAudio.size * 2, turnLen + count))
        }
        System.arraycopy(src, from, turnAudio, turnLen, count)
        turnLen += count
    }

    companion object {
        const val LATENCY_TAG = "LIVE_LATENCY"
        const val BARGE_TAG = "LIVE_BARGE_IN"
        /** Design section 11: ~400 ms of mic audio kept for activityStart. */
        const val PRE_ROLL_MS = 400
        private const val PRE_ROLL_BYTES = PRE_ROLL_MS * 32
        /** Smart Turn's input starts 200 ms before speech onset (the offline eval's PREROLL). */
        private const val MODEL_PRE_ROLL_SAMPLES = 3_200
        private const val FLOAT_RING = 16_000
        private const val INITIAL_TURN = 16_000 * 4
        private const val MAX_TURN = 16_000 * 16
        private const val STARTED = 1
        private const val BARGED = 2
        private const val ENDED = 4

        /** One thread for every session's Smart Turn checks, so checks never overlap. */
        private val INFERENCE: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "turn-infer").apply { isDaemon = true }
        }

        /** Confined to [INFERENCE]'s thread. */
        private val MEL by lazy { WhisperLogMel() }
    }
}
