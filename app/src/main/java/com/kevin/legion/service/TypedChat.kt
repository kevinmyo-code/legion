package com.kevin.legion.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One finished typed exchange, handed to the recorder (episodic memory and the audit trail). */
data class TypedTurnRecord(
    val turnSeq: Long,
    val userText: String,
    /** The reply shown, or blank when the turn failed. */
    val replyText: String,
    /** A read-through tool ran this turn: keep it out of episodic memory and redact the reply. */
    val readThrough: Boolean,
)

/**
 * Typed chat, end to end (Kevin, 2026-10-27: typed chat goes to a normal text model, voice stays on
 * Gemini Live). Owned by [LiveSessionController], which supplies the shared pieces - the same system
 * instruction builder, the same tool declarations, the same tool runner - and never hands this
 * class the Live session at all. That absence is the guarantee: a typed message cannot open a
 * socket, claim the microphone, deafen the wake word or touch the follow-up window, because nothing
 * here can reach any of them.
 *
 * The panel ([AssistantChatStore]) is both what the person sees and the text model's history, so a
 * typed follow-up to something just said aloud has that spoken exchange in front of it.
 *
 * Every dependency is a constructor parameter so `TypedChatTest` drives it with a fake transport
 * and no Context.
 */
@Suppress("LongParameterList")
class TypedChat(
    private val scope: CoroutineScope,
    private val engine: TextChatEngine,
    private val hasKey: () -> Boolean,
    private val isOnline: () -> Boolean,
    private val isCrisis: (String) -> Boolean,
    private val systemParts: suspend () -> List<String>,
    private val declarations: () -> JSONArray,
    private val runTool: suspend (name: String, args: JSONObject, readThrough: Boolean, turnSeq: Long) -> JSONObject,
    private val speakerName: () -> String?,
    private val onCrisis: () -> Unit,
    /** True when a typed turn starts, false when it ends: the controller's phase hook. */
    private val onInFlightChanged: (Boolean) -> Unit = {},
    private val record: (TypedTurnRecord) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val readPanel: () -> ChatTranscript = { AssistantChatStore.transcript.value },
    private val updatePanel: ((ChatTranscript) -> ChatTranscript) -> Unit = { AssistantChatStore.update(it) },
) {
    /** A typed turn is being answered. Main-thread confined, like the controller that owns this. */
    var inFlight: Boolean = false
        private set

    /** Routes one typed message. Returns the decision taken, for the caller's logging and tests. */
    fun onTyped(text: String): TypedTurnPolicy.Decision {
        val typed = text.trim()
        val decision = TypedTurnPolicy.decide(
            hasText = typed.isNotEmpty(),
            crisis = typed.isNotEmpty() && isCrisis(typed),
            hasKey = hasKey(),
            online = isOnline(),
            textTurnInFlight = inFlight,
        )
        when (decision) {
            TypedTurnPolicy.Decision.Ignore -> Unit
            // The crisis card is the whole response; the message is not sent and not recorded as sent.
            TypedTurnPolicy.Decision.Crisis -> onCrisis()
            is TypedTurnPolicy.Decision.Refuse -> updatePanel { it.notSent(typed, decision.reason.words) }
            TypedTurnPolicy.Decision.Send -> send(typed)
        }
        return decision
    }

    private fun send(typed: String) {
        // History is read BEFORE this message joins the panel, so it is not sent twice.
        val history = TextChatEngine.capHistory(readPanel().textHistory())
        updatePanel { it.withSpeaker(speakerName()).typedSent(typed) }
        inFlight = true
        onInFlightChanged(true)
        val turnSeq = clock()
        scope.launch {
            val result = try {
                engine.answer(
                    systemParts = systemParts(),
                    history = history,
                    message = typed,
                    declarations = declarations(),
                    executor = { name, args, readThrough -> runTool(name, args, readThrough, turnSeq) },
                    onToolResult = { name, response -> showToolLine(name, response) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                // Building the instruction failed before any model call or tool ran.
                TextTurnResult.Failed(
                    TextFailure.MODEL_ERROR,
                    TextChatEngine.failureWords(TextFailure.MODEL_ERROR, emptyList(), emptyList()),
                    writesDone = emptyList(),
                    readThroughTouched = false,
                )
            } finally {
                inFlight = false
                onInFlightChanged(false)
            }
            when (result) {
                is TextTurnResult.Reply -> updatePanel { it.withSpeaker(speakerName()).typedReplied(result.text) }
                is TextTurnResult.Failed -> updatePanel { it.typedFailed(result.words) }
            }
            record(
                TypedTurnRecord(
                    turnSeq = turnSeq,
                    userText = typed,
                    replyText = (result as? TextTurnResult.Reply)?.text.orEmpty(),
                    readThrough = result.readThroughTouched,
                ),
            )
        }
    }

    /**
     * The panel's tool line, the same rule as voice: only a result carrying an explicit `success`
     * gets a line, so nothing reads "Done" over a result nobody checked (CLAUDE.md sec 7).
     */
    private fun showToolLine(name: String, response: JSONObject) {
        if (!response.has("success")) return
        val ok = response.optBoolean("success", false)
        val detail = if (ok) null else response.optString("message").takeIf { it.isNotBlank() }
        updatePanel { it.tool(name, ok, detail) }
    }
}
