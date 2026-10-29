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
 *   [PRE_ROLL_MS] ring, so neither the 200 ms speech-start confirmation nor a word begun in the
 *   reply's drain tail is clipped (see [PRE_ROLL_MS]).
 * - server VAD with barge-in: everything goes up except while the reply is audible; a barge-in
 *   sends the ring first so the server's VAD hears the onset.
 * - a barge-in (either mode) sends [BARGE_PRE_ROLL_MS] of ring: the gate can fire up to a 512 ms
 *   span after the first qualifying window (2026-10-28).
 * - every start logs one `pre_roll_ms=` [LATENCY_TAG] line ([logPreRoll]) saying how far back the
 *   ring reached, so a clipped onset can be told apart from one the ring held.
 */
@Suppress("TooManyFunctions") // one small step per concern; splitting the class would split the lock
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

    private val ring = ByteArray(maxOf(PRE_ROLL_MS, BARGE_PRE_ROLL_MS) * BYTES_PER_MS)
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

    // For the pre_roll line: the start's onset in windows, the bytes of its buffer after the window
    // that confirmed it, and windows since the reply was last audible (-1: none yet this session),
    // live and as it stood at the confirming window.
    private var startOnsetWindows = 0
    private var startTailBytes = 0
    private var windowsSincePlaying = -1
    private var startSincePlaying = -1

    val inActivity: Boolean get() = synchronized(lock) { policy.inActivity }

    /**
     * A reply started playing (first model audio). A reply still open here (the next model turn began
     * before the last one drained, so its drain watcher stood down) is ended and logged first.
     */
    fun onReplyStart() = synchronized(lock) {
        endReply()
        bargedThisReply = false
        if (bargeIn) gate.onReplyStart()
        sink.log(BARGE_TAG, "armed=${gate.armedNow} disabled=${!bargeIn || gate.disabled}")
    }

    /** The reply finished playing, or was flushed. Logs its [BargeInGate.ReplyStats] once. */
    fun onReplyEnd() = synchronized(lock) { endReply() }

    /** Barge-in off for the rest of the session (Bluetooth connected, self-interrupts). */
    fun disableBargeIn() = synchronized(lock) {
        bargeIn = false
        endReply()
    }

    /** Forget any turn in progress (mic stopped, session closing). Sends nothing. */
    fun reset() = synchronized(lock) {
        endReply()
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
            val s = ((pcm[i].toInt() and BYTE_MASK) or (pcm[i + 1].toInt() shl BITS_PER_BYTE)).toShort()
            window[windowFill++] = s / PCM_SCALE
            i += 2
            if (windowFill == SileroVad.WINDOW) {
                windowFill = 0
                val outcome = processWindow(playing) ?: return 0
                if (outcome and STARTED != 0 && !started) {
                    startTailBytes = n - i
                    startSincePlaying = windowsSincePlaying
                }
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
            val a = kotlin.math.abs((v * PCM_SCALE).toInt())
            if (a > level) level = a
        }
        val prob = sileroProb() ?: return null
        windowsSincePlaying = when {
            playing -> 0
            windowsSincePlaying < 0 -> -1
            else -> minOf(windowsSincePlaying + 1, MAX_WINDOWS_SINCE_PLAYING)
        }
        appendFloatRing()
        if (policy.inActivity) appendTurn(window, 0, window.size)
        if (prob >= EndOfTurnPolicy.START_PROB && !playing) sink.onLocalSpeech()
        var outcome = if (bargeIn) gateWindow(prob, level, playing) else 0
        // While the reply is audible the echo is speech too: only the gate may start a turn.
        if (manual && (!playing || policy.inActivity)) {
            outcome = outcome or handle(policy.onWindow(prob))
        }
        return outcome
    }

    /** Silero on [window]; null (and the session told) when it throws. */
    @Suppress("TooGenericExceptionCaught") // anything from the native runtime means fall back
    private fun sileroProb(): Float? = try {
        silero.prob(window)
    } catch (t: Throwable) {
        failed = true
        if (policy.inActivity) sink.sendActivityEnd()
        policy.reset()
        sink.onSileroFailed("${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Ends the gate's reply and logs what it heard, if one was open. Must hold [lock]. */
    private fun endReply() {
        val s = gate.onReplyEnd() ?: return
        sink.log(BARGE_TAG, replyEndLine(s))
    }

    /** One window through the echo gate. Returns STARTED/BARGED bits. Must hold [lock]. */
    private fun gateWindow(prob: Float, level: Int, playing: Boolean): Int {
        var outcome = 0
        when (val ev = gate.onWindow(prob, level, playing)) {
            is BargeInGate.Event.Fire -> {
                bargedThisReply = true
                startOnsetWindows = BargeInGate.SPAN_WINDOWS + 1
                outcome = outcome or BARGED
                sink.log(
                    BARGE_TAG,
                    "fire prob=${"%.2f".format(java.util.Locale.US, ev.prob)} level=${ev.level} " +
                        "echo_floor=${ev.echoFloor} threshold=${ev.threshold} sustain_ms=${ev.sustainMs}",
                )
                if (manual) {
                    policy.forceStart()?.let {
                        // The whole span the gate fired on, so the onset of a short word is in it.
                        beginTurnAudio(BargeInGate.SPAN_WINDOWS + 1)
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
        return outcome
    }

    /** Runs [actions] from the policy. Must hold [lock]. Returns STARTED/ENDED bits. */
    private fun handle(actions: List<EndOfTurnPolicy.Action>): Int {
        var out = 0
        for (a in actions) {
            when (a) {
                is EndOfTurnPolicy.Action.StartActivity -> {
                    startOnsetWindows = a.onsetWindows
                    beginTurnAudio(a.onsetWindows)
                    out = out or STARTED
                    sink.onUserTurnStart(bargeIn = false)
                }
                is EndOfTurnPolicy.Action.RequestCheck -> requestCheck(a)
                is EndOfTurnPolicy.Action.EndActivity -> {
                    sink.sendActivityEnd()
                    // The user was talking until a moment ago: the next reply must not learn them.
                    gate.onUserTurnEnd()
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
                    sendRing(if (barged) BARGE_PRE_ROLL_MS else PRE_ROLL_MS).also { logPreRoll(it, barged) }
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
            barged -> sendRing(BARGE_PRE_ROLL_MS).also { logPreRoll(it, barge = true) }
            !playing || bargedThisReply -> {
                sink.sendAudio(pcm, 0, n)
                n
            }
            else -> 0
        }
    }

    /**
     * One line per start (async-lookups ticket 08 item 3). `pre_roll_lead_ms` is how far the ring
     * reached before the first window Silero (or the gate) counted: the pre-roll, less the onset,
     * less the part of the mic buffer after the confirming window. `pre_roll_after_reply_ms` is the
     * time from the reply's last audible window to the confirming one; when it equals
     * `pre_roll_onset_ms` the candidate began on the first window after the drain, and the word may
     * have begun inside it, where the policy does not listen.
     */
    private fun logPreRoll(sentBytes: Int, barge: Boolean) {
        val sentMs = sentBytes / BYTES_PER_MS
        val onsetMs = startOnsetWindows * BargeInGate.WINDOW_MS
        val tailMs = startTailBytes / BYTES_PER_MS
        val afterReply = if (startSincePlaying >= 0) {
            " pre_roll_after_reply_ms=${startSincePlaying * BargeInGate.WINDOW_MS}"
        } else {
            ""
        }
        sink.log(
            LATENCY_TAG,
            "pre_roll_ms=$sentMs pre_roll_bytes=$sentBytes barge=$barge pre_roll_onset_ms=$onsetMs " +
                "pre_roll_tail_ms=$tailMs pre_roll_lead_ms=${sentMs - onsetMs - tailMs}$afterReply",
        )
    }

    private fun requestCheck(a: EndOfTurnPolicy.Action.RequestCheck) {
        val model = smartTurn ?: return
        val audio = turnAudio
        val len = turnLen
        val key = turnKey
        val asked = System.nanoTime()
        INFERENCE.execute {
            @Suppress("TooGenericExceptionCaught") // a throwing check means silence-only from here on
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
                            "st_queue_ms=${(t0 - asked) / NANOS_PER_MS} st_feat_ms=${(t1 - t0) / NANOS_PER_MS} " +
                            "st_infer_ms=${(t2 - t1) / NANOS_PER_MS} frames=$frames",
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

    /** The newest [ms] of the ring (or all of it, if less is held). */
    private fun sendRing(ms: Int): Int {
        val len = minOf(ringFill, ms * BYTES_PER_MS)
        if (len == 0) return 0
        val out = ByteArray(len)
        val start = (ringPos - len + ring.size) % ring.size
        for (i in 0 until len) out[i] = ring[(start + i) % ring.size]
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
        /** The gate's span plus one window: its first qualifying window can be 512 ms back. */
        const val BARGE_PRE_ROLL_MS = (BargeInGate.SPAN_WINDOWS + 1) * BargeInGate.WINDOW_MS
        /**
         * Mic audio sent after activityStart on a normal (non-barge) start: 544 ms, the barge-in
         * figure. Design section 11 said ~400 ms. Run 5 (A25, 2026-10-28): "stop" said as a reply
         * ended reached the model as "top". What 400 ms covers, by reading:
         *  - the start confirms on the 7th window at >= [EndOfTurnPolicy.START_PROB] (224 ms);
         *  - the ring holds the whole 100 ms mic buffer (`GeminiLiveSession.CHUNK_BYTES`), written
         *    before its windows are read, so up to ~100 ms of it lies after the confirming window;
         *  - so 400 ms reaches only 76 to 176 ms before the first window Silero called speech, and
         *    an unvoiced /s/ plus a /t/ closure, which Silero is slow to score, can be longer;
         *  - right after a reply the policy is blind while `playing` is true: up to ~80 ms of drain
         *    poll and settle plus one 100 ms buffer read under the old flag. A word begun there is
         *    counted only from the first window after, and its onset can sit 500 ms before the start.
         * 544 ms reaches 220 to 320 ms before the first speech window. The cost is up to 144 ms more
         * room tone, or the reply's last echo, at the head of a turn that starts right after a drain.
         * [EndOfTurnPolicy.START_PROB] is unchanged. The `pre_roll_ms=` line measures it on the phone.
         */
        const val PRE_ROLL_MS = BARGE_PRE_ROLL_MS
        /** 16 kHz PCM16 mono. */
        const val BYTES_PER_MS = 32
        /** Smart Turn's input starts 200 ms before speech onset (the offline eval's PREROLL). */
        private const val MODEL_PRE_ROLL_SAMPLES = 3_200
        private const val FLOAT_RING = 16_000
        private const val INITIAL_TURN = 16_000 * 4
        private const val MAX_TURN = 16_000 * 16
        private const val BYTE_MASK = 0xff
        private const val BITS_PER_BYTE = 8
        private const val PCM_SCALE = 32_768f
        private const val NANOS_PER_MS = 1_000_000L
        private const val STARTED = 1
        private const val BARGED = 2
        private const val ENDED = 4
        /** Caps the pre_roll line's since-the-reply count (about 64 s); it only matters when short. */
        private const val MAX_WINDOWS_SINCE_PLAYING = 2_000

        /** The `reply_end` line: everything needed to say why a reply was not barged in on. */
        fun replyEndLine(s: BargeInGate.ReplyStats): String =
            "reply_end fired=${s.fired} windows=${s.windows} speech_windows=${s.speechWindows} " +
                "loud_windows=${s.loudWindows} qualifying_windows=${s.qualifyingWindows} " +
                "max_prob=${"%.2f".format(java.util.Locale.US, s.maxProb)} max_level=${s.maxLevel} " +
                "longest_run_ms=${s.longestRunMs} best_span=${s.bestSpanWindows} " +
                "echo_floor=${s.echoFloor} threshold=${s.threshold}"

        /** One thread for every session's Smart Turn checks, so checks never overlap. */
        private val INFERENCE: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "turn-infer").apply { isDaemon = true }
        }

        /** Confined to [INFERENCE]'s thread. */
        private val MEL by lazy { WhisperLogMel() }
    }
}
