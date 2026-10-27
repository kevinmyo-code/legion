package com.kevin.legion.service

/** One line in the assistant panel. */
data class ChatEntry(
    val id: Long,
    val kind: Kind,
    val text: String,
    val via: Via = Via.NONE,
    /** Extra words under the text: why a message was not sent, or that a reply was cut short. */
    val note: String? = null,
    /**
     * Who said an ASSISTANT line, stamped when it was said so a later companion switch cannot
     * relabel it. Null: the current name.
     */
    val speaker: String? = null,
) {
    enum class Kind { USER, ASSISTANT, TOOL, SYSTEM, NOT_SENT }

    /**
     * How the line reached the person. For an ASSISTANT line [TYPED] means the reply was SHOWN, NOT
     * SPOKEN; [SPOKEN] means it was played aloud. Said in words on screen, never by icon alone
     * (CLAUDE.md sec 7).
     */
    enum class Via { NONE, TYPED, SPOKEN }
}

/**
 * The assistant panel's conversation, as an immutable value with pure reducers. **Session-only by
 * construction**: it lives in memory behind [AssistantChatStore], nothing here is written to Room,
 * synced or remembered (what voice already persists goes through `GeminiLiveSession`'s episodic
 * and audit paths, untouched by this class).
 *
 * "Ended" rather than cleared when the Live socket closes: the person can still read what was said,
 * the panel says "Conversation ended.", and the next SPOKEN turn on a new session starts it afresh
 * (see [beginIfEnded]). Clearing the instant a voice chat closed would delete an answer they were
 * still reading, since a hands-free chat closes itself eight seconds after the reply.
 *
 * **Typed turns no longer ride the Live socket (Kevin, 2026-10-27).** They go to a text model that
 * takes this panel as its history ([textHistory]), so a voice session closing must not wipe what a
 * typed follow-up needs: [typedSent] continues a panel the voice socket ended, and only a companion
 * switch (whose new companion genuinely does not know what was said) still starts it afresh. A
 * socket that closes with no spoken line since the last end adds nothing, so the prewarm socket
 * cycling under a typed-only chat never stamps "Conversation ended." into it.
 */
data class ChatTranscript(
    val entries: List<ChatEntry> = emptyList(),
    /** A typed turn's reply still arriving: the text so far, shown as "Replying". Null when none. */
    val pendingTypedReply: String? = null,
    val ended: Boolean = false,
    /** A spoken line was added since the panel last ended: the only case a socket close is news. */
    val voiceSinceEnd: Boolean = false,
    /** [ended] came from a voice socket closing, which a typed turn continues past rather than wipes. */
    val endedByVoiceClose: Boolean = false,
    /** The active companion's name, stamped onto each ASSISTANT line as it is added. Null until known. */
    val speaker: String? = null,
    private val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && pendingTypedReply == null

    /**
     * The person typed [text] and it was sent to the text model. Opens the pending reply. A panel a
     * voice socket ended is continued, not wiped: the text model reads it as history.
     */
    fun typedSent(text: String): ChatTranscript {
        val continued =
            if (ended && endedByVoiceClose) copy(ended = false, endedByVoiceClose = false) else beginIfEnded()
        val base = continued.flushPending(note = "Cut off because you sent another message.")
        return base.add(ChatEntry.Kind.USER, text, ChatEntry.Via.TYPED).copy(pendingTypedReply = "")
    }

    /** The text model's reply to the pending typed message: shown, not spoken. */
    fun typedReplied(said: String): ChatTranscript =
        copy(pendingTypedReply = null).add(ChatEntry.Kind.ASSISTANT, said, ChatEntry.Via.TYPED)

    /**
     * The pending typed message went out and no reply came back. [words] says what did not happen
     * (and which writes did), in the engine's words. Not "not sent": it was sent.
     */
    fun typedFailed(words: String): ChatTranscript =
        copy(pendingTypedReply = null).add(ChatEntry.Kind.SYSTEM, words)

    /**
     * The panel as history for the text model: every USER and ASSISTANT line, typed or spoken, in
     * order, including lines above a "Conversation ended." marker. Tool, system and not-sent lines
     * are not conversation. The engine caps it ([TextChatEngine.capHistory]).
     */
    fun textHistory(): List<ChatTurn> = entries.mapNotNull {
        when (it.kind) {
            ChatEntry.Kind.USER -> ChatTurn(fromUser = true, text = it.text)
            ChatEntry.Kind.ASSISTANT -> ChatTurn(fromUser = false, text = it.text)
            else -> null
        }
    }

    /**
     * A turn finished. [typed] turns already have their USER line (from [typedSent]); a spoken turn
     * gets its USER line here from [heard], which may be blank when the transcriber returned
     * nothing - then no USER line is invented.
     */
    fun turnComplete(heard: String, said: String, typed: Boolean): ChatTranscript {
        var t = beginIfEnded()
        if (typed) {
            t = t.copy(pendingTypedReply = null)
            return if (said.isBlank()) {
                t.add(ChatEntry.Kind.SYSTEM, "No written reply came back for that.")
            } else {
                t.add(ChatEntry.Kind.ASSISTANT, said, ChatEntry.Via.TYPED)
            }
        }
        // A typed reply still pending is NOT closed out here: it comes from the text model, not
        // this socket, and lands on its own when it arrives.
        if (heard.isNotBlank()) t = t.add(ChatEntry.Kind.USER, heard, ChatEntry.Via.SPOKEN)
        if (said.isNotBlank()) t = t.add(ChatEntry.Kind.ASSISTANT, said, ChatEntry.Via.SPOKEN)
        return if (heard.isNotBlank() || said.isNotBlank()) t.copy(voiceSinceEnd = true) else t
    }

    /** A typed message that was NOT sent, with the reason in words. Never wipes the panel. */
    fun notSent(text: String, reason: String): ChatTranscript =
        add(ChatEntry.Kind.NOT_SENT, text, ChatEntry.Via.TYPED, note = reason)

    /**
     * One tool line, from a tool call that RETURNED. [ok] is the tool's own `success`; a tool whose
     * result carried no `success` field gets no line at all (the caller skips it), because a line
     * reading "Done" over a result nobody checked is the false success sec 7 forbids. A failure says
     * it did not run, with the tool's own [detail] when it gave one.
     */
    fun tool(name: String, ok: Boolean, detail: String? = null): ChatTranscript =
        add(
            ChatEntry.Kind.TOOL,
            humanToolName(name),
            note = if (ok) null else "Did not run" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
        )

    /**
     * The Live socket closed. Keeps what was said and says the voice conversation ended, but only
     * when a spoken line was added since the last end. A typed reply still pending is left alone:
     * it belongs to the text model, not this socket.
     */
    fun sessionEnded(): ChatTranscript {
        if (ended || !voiceSinceEnd) return this
        return add(ChatEntry.Kind.SYSTEM, "Conversation ended.")
            .copy(ended = true, voiceSinceEnd = false, endedByVoiceClose = true)
    }

    /** The first spoken line of a NEW session replaces an ended panel instead of stacking under it. */
    fun beginIfEnded(): ChatTranscript =
        if (ended) ChatTranscript(speaker = speaker, pendingTypedReply = pendingTypedReply, nextId = nextId) else this

    internal fun flushPending(note: String): ChatTranscript {
        val partial = pendingTypedReply ?: return this
        val cleared = copy(pendingTypedReply = null)
        return if (partial.isBlank()) {
            cleared.add(ChatEntry.Kind.SYSTEM, "Replying was stopped before any text came back.")
        } else {
            cleared.add(ChatEntry.Kind.ASSISTANT, partial, ChatEntry.Via.TYPED, note = note)
        }
    }

    internal fun add(
        kind: ChatEntry.Kind,
        text: String,
        via: ChatEntry.Via = ChatEntry.Via.NONE,
        note: String? = null,
    ) = copy(
        entries = (
            entries + ChatEntry(
                nextId, kind, text, via, note,
                speaker = if (kind == ChatEntry.Kind.ASSISTANT) speaker else null,
            )
        ).takeLast(MAX_ENTRIES),
        nextId = nextId + 1,
    )

    companion object {
        /** A session-only panel never needs more than a screenful of history; bounded so it cannot grow for days. */
        const val MAX_ENTRIES = 100
    }
}

/** `add_to_list` -> `Add to list`. Tool names are identifiers; the panel is for people. */
fun humanToolName(name: String): String =
    name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
