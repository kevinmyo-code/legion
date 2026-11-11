package com.kevin.legion.service.cascade

import com.kevin.legion.service.Phase
import org.json.JSONObject

/**
 * The controller's side of [CascadeHost], fed lambdas the way [com.kevin.legion.service.TypedChat]
 * is, so [com.kevin.legion.service.LiveSessionController] carries only thin branches.
 *
 * One bridge per conversation. [post] runs a block on the controller's main scope. Once the
 * controller detaches it ([attached] false: the conversation closed, crisis, or Live took over),
 * nothing this session says can move the strip any more, so a late event from a finished
 * conversation can never stomp the next one. A tool already running still finishes.
 */
class CascadeBridge(
    private val post: (() -> Unit) -> Unit,
    private val setPhase: (Phase, String) -> Unit,
    private val showNotice: (String) -> Unit,
    private val tool: suspend (name: String, args: JSONObject, readThrough: Boolean, userText: String, turnSeq: Long) -> JSONObject,
    private val transcript: (heard: String, said: String) -> Unit,
    private val onCrisis: () -> Unit,
    private val tone: () -> Unit,
    private val toLive: (fromWakeWord: Boolean, opener: String?) -> Unit,
    private val onClosed: (reason: String) -> Unit,
) : CascadeHost {
    @Volatile var attached = true

    private fun ifAttached(block: () -> Unit) = post { if (attached) block() }

    override fun phase(phase: Phase, status: String) = ifAttached { setPhase(phase, status) }

    override fun notice(text: String) = ifAttached { showNotice(text) }

    override suspend fun runTool(name: String, args: JSONObject, readThrough: Boolean, userText: String, turnSeq: Long): JSONObject =
        tool(name, args, readThrough, userText, turnSeq)

    override fun turnTranscript(heard: String, said: String) = ifAttached { transcript(heard, said) }

    override fun crisis() = ifAttached {
        attached = false
        onCrisis()
    }

    override fun closingTone() = ifAttached { tone() }

    override fun handOverToLive(fromWakeWord: Boolean, opener: String?) = ifAttached {
        attached = false
        toLive(fromWakeWord, opener)
    }

    override fun closed(reason: String) = ifAttached {
        attached = false
        onClosed(reason)
    }
}
