package com.kevin.legion.look

import android.content.Context
import android.os.SystemClock
import com.kevin.legion.service.LiveNotes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * "Look at this" (ADR 0084): the one controller both doors call (ADR 0035). The voice `look` tool
 * calls [openForVoice]; the Fleet screen's Show button calls [openByHand]. Either way [LookActivity]
 * shows the camera and hands frames to [offerFrame], which sends about one a second into the open
 * voice session while the look is open, and nothing once it closes ([close]).
 *
 * **Frames are never stored.** A frame lives in memory from the analyzer to the socket send and is
 * dropped; nothing here or in [LookActivity] writes one to disk, Room, a log or the engine. The
 * session marks every turn that saw a frame read-through, so nothing seen can become a memory
 * (`GeminiLiveSession.sendVideoFrame`).
 *
 * The voice session is reached through [Sink], registered by `LiveSessionController` the way
 * [LiveNotes] is, so this object never holds a session and frames always follow the CURRENT one.
 */
@Suppress("TooManyFunctions") // both doors and the frame path in one object, by ADR 0035; plus test seams
object LookController {

    /** Where frames and the button's opener go: the live voice session, or nobody. */
    interface Sink {
        /** A voice conversation is open right now. */
        fun inConversation(): Boolean

        /** Sends one JPEG into the open conversation. True only when the socket took it. */
        fun sendFrame(jpeg: ByteArray): Boolean

        /** The button path: join or start a conversation with [LookRules.HAND_OPENER]. Null on success. */
        fun beginByHand(): String?
    }

    enum class Source { VOICE, HAND }

    /** What the look screen shows. Only [framesSent] > 0 may say the assistant can see. */
    data class State(
        val open: Boolean = false,
        val source: Source? = null,
        val framesSent: Int = 0,
        val notice: String? = null,
    )

    @Volatile private var sink: Sink? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var lastSentMs = 0L
    @Volatile private var firstFrame = CompletableDeferred<Unit>()

    // A voice `look` returned before any frame went out: the first frame that does go out tells the
    // model so, once ([LookRules.LIVE_LATE_NOTE]).
    @Volatile private var lateNoteOwed = false

    /** Test seam: how the screen is brought up. */
    internal var launcher: (Context, Source) -> Boolean = { context, source -> LookActivity.open(context, source) }

    /** The motion verdict both doors and the open screen use; a test seam. */
    internal var motionProbe: suspend (Context) -> LookRules.Motion = LookMotion::read

    /** Test seam: the clock frames are throttled on. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Test seam: where the screen is started from. */
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    /** Test seam: how long the voice door waits for its first frame. */
    internal var firstFrameWaitMs: Long = LookRules.FIRST_FRAME_WAIT_MS

    fun register(sink: Sink) {
        this.sink = sink
    }

    fun unregister(sink: Sink) {
        if (this.sink === sink) this.sink = null
    }

    /**
     * The voice door: the `look` tool. Refuses in words when no conversation is open or the car is
     * moving; otherwise opens the screen and waits up to [firstFrameWaitMs] for a frame to actually
     * go out. The result claims sight only if one did.
     */
    suspend fun openForVoice(context: Context): JSONObject {
        val s = sink
        val refusal = when {
            s == null || !s.inConversation() -> LookRules.REFUSED_NO_CONVERSATION
            motionProbe(context) == LookRules.Motion.MOVING -> LookRules.REFUSED_MOVING
            else -> null
        }
        return if (refusal != null) LookRules.refusal(refusal) else openAndWait(context)
    }

    private suspend fun openAndWait(context: Context): JSONObject {
        val waitFor = begin(Source.VOICE)
        if (!withContext(mainDispatcher) { launcher(context, Source.VOICE) }) {
            close()
            return LookRules.refusal("Not opened: Android did not let LEGION bring the camera screen up.")
        }
        val seen = withTimeoutOrNull(firstFrameWaitMs) { waitFor.await() } != null
        if (!seen) lateNoteOwed = true
        return LookRules.toolResult(framesSent = seen)
    }

    /**
     * The hands door: the button. Returns a refusal in words (moving, no screen) or null when the
     * screen is opening. The conversation is started or joined by [LookActivity] once the camera is
     * up ([startConversationForHand]), so a refused camera permission never opens a socket.
     */
    suspend fun openByHand(context: Context): String? = when {
        motionProbe(context) == LookRules.Motion.MOVING -> LookRules.REFUSED_MOVING
        else -> {
            begin(Source.HAND)
            if (launcher(context, Source.HAND)) {
                null
            } else {
                close()
                "Android did not let LEGION bring the camera screen up."
            }
        }
    }

    /** Called by the screen once its camera runs, for a look opened by hand. A refusal shows on it. */
    fun startConversationForHand() {
        val s = sink
        val refusal = if (s == null) VOICE_NOT_RUNNING else s.beginByHand()
        if (refusal != null) _state.value = _state.value.copy(notice = refusal)
    }

    /** A look the screen opened itself (a relaunch after process death): treat it as opened by hand. */
    fun ensureOpen(source: Source) {
        if (!_state.value.open) begin(source)
    }

    private fun begin(source: Source): CompletableDeferred<Unit> {
        LiveNotes.withdraw(CLOSED_KEY)
        lastSentMs = 0L
        lateNoteOwed = false
        val deferred = CompletableDeferred<Unit>()
        firstFrame = deferred
        _state.value = State(open = true, source = source)
        return deferred
    }

    /** True when a frame should be encoded and offered now. Checked before encoding, to save the work. */
    fun frameWanted(): Boolean = _state.value.open && LookRules.frameDue(clock(), lastSentMs)

    /**
     * Sends one frame if the look is open, one is due, and a conversation is open. Returns whether it
     * went out. Holds no reference to [jpeg] after returning.
     */
    fun offerFrame(jpeg: ByteArray): Boolean {
        val s = sink
        val sent = frameWanted() && s != null && s.inConversation() && s.sendFrame(jpeg)
        if (sent) recordSent()
        return sent
    }

    private fun recordSent() {
        lastSentMs = clock()
        val st = _state.value
        _state.value = st.copy(framesSent = st.framesSent + 1, notice = null)
        if (!firstFrame.isCompleted) firstFrame.complete(Unit)
        if (lateNoteOwed) {
            lateNoteOwed = false
            LiveNotes.offer(LIVE_KEY, LookRules.LIVE_LATE_NOTE)
        }
    }

    /** Whether a conversation is open, for the screen's status line. */
    fun conversationOpen(): Boolean = sink?.inConversation() == true

    /**
     * Ends the look: frames stop at once. If any went out, the model is told it can no longer see
     * ([LookRules.CLOSED_NOTE]). [notice] stays on the state for a screen that is still showing.
     */
    fun close(notice: String? = null) {
        val was = _state.value
        if (!was.open) {
            if (notice != null) _state.value = was.copy(notice = notice)
            return
        }
        lateNoteOwed = false
        LiveNotes.withdraw(LIVE_KEY)
        if (was.framesSent > 0) LiveNotes.offer(CLOSED_KEY, LookRules.CLOSED_NOTE)
        if (!firstFrame.isCompleted) firstFrame.cancel()
        _state.value = State(open = false, notice = notice)
    }

    /** Test seam: back to a fresh, closed controller. */
    internal fun resetForTest() {
        sink = null
        lastSentMs = 0L
        lateNoteOwed = false
        firstFrame = CompletableDeferred()
        _state.value = State()
        launcher = { context, source -> LookActivity.open(context, source) }
        motionProbe = LookMotion::read
        clock = { SystemClock.elapsedRealtime() }
        mainDispatcher = Dispatchers.Main
        firstFrameWaitMs = LookRules.FIRST_FRAME_WAIT_MS
    }

    internal const val VOICE_NOT_RUNNING =
        "Voice is not running, so the assistant cannot see. Open LEGION and try again."
    internal const val CLOSED_KEY = "look_closed"
    internal const val LIVE_KEY = "look_live"
}
