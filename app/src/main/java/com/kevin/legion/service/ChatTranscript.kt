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
 * the panel says "Conversation ended.", and the next real turn on a NEW session starts it afresh
 * (see [beginIfEnded]). Clearing the instant a voice chat closed would delete an answer they were
 * still reading, since a hands-free chat closes itself eight seconds after the reply.
 */
data class ChatTranscript(
    val entries: List<ChatEntry> = emptyList(),
    /** A typed turn's reply still arriving: the text so far, shown as "Replying". Null when none. */
    val pendingTypedReply: String? = null,
    val ended: Boolean = false,
    /** The active companion's name, stamped onto each ASSISTANT line as it is added. Null until known. */
    val speaker: String? = null,
    private val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && pendingTypedReply == null

    /** The person typed [text] and it was sent. Opens the pending reply. */
    fun typedSent(text: String): ChatTranscript {
        val base = beginIfEnded().flushPending(note = "Cut off because you sent another message.")
        return base.add(ChatEntry.Kind.USER, text, ChatEntry.Via.TYPED).copy(pendingTypedReply = "")
    }

    /** The typed reply so far (the session's full accumulated output transcription). */
    fun typedReplyProgress(textSoFar: String): ChatTranscript =
        if (pendingTypedReply == null) this else copy(pendingTypedReply = textSoFar)

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
        // A spoken turn finishing while a typed reply was still pending means the person talked
        // over it (the session un-mutes on speech): voice won, say so rather than leave it pending.
        t = t.voiceTookOver()
        if (heard.isNotBlank()) t = t.add(ChatEntry.Kind.USER, heard, ChatEntry.Via.SPOKEN)
        if (said.isNotBlank()) t = t.add(ChatEntry.Kind.ASSISTANT, said, ChatEntry.Via.SPOKEN)
        return t
    }

    /** Typing cut the assistant's speech short. */
    fun interruptedByTyping(): ChatTranscript =
        beginIfEnded().add(ChatEntry.Kind.SYSTEM, "Stopped speaking because you typed.")

    /** Push-to-talk (or a wake word) began while a typed reply was still arriving: voice wins. */
    fun voiceTookOver(): ChatTranscript {
        if (pendingTypedReply == null) return this
        return flushPending(note = "Stopped because you started talking.")
    }

    /**
     * A typed message that was NOT sent, with the reason in words. [clearPending] is for a message
     * that WAS shown as sent (so a reply was pending) and then could not be delivered at connect: the
     * waiting reply is closed out and [text] is blank, since the USER line is already above it.
     */
    fun notSent(text: String, reason: String, clearPending: Boolean = false): ChatTranscript {
        val base = if (clearPending) copy(pendingTypedReply = null) else beginIfEnded()
        return base.add(ChatEntry.Kind.NOT_SENT, text, ChatEntry.Via.TYPED, note = reason)
    }

    /**
     * One tool line, from a tool call that RETURNED. [ok] is the tool's own `success`; a tool whose
     * result carried no `success` field gets no line at all (the caller skips it), because a line
     * reading "Done" over a result nobody checked is the false success sec 7 forbids. A failure says
     * it did not run, with the tool's own [detail] when it gave one.
     */
    fun tool(name: String, ok: Boolean, detail: String? = null): ChatTranscript =
        beginIfEnded().add(
            ChatEntry.Kind.TOOL,
            humanToolName(name),
            note = if (ok) null else "Did not run" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
        )

    /** The Live socket closed. Keeps what was said, says it ended, flushes a half-arrived reply. */
    fun sessionEnded(): ChatTranscript {
        if (isEmpty || ended) return this
        val flushed = flushPending(note = "The conversation ended before this finished.")
        return flushed.add(ChatEntry.Kind.SYSTEM, "Conversation ended.").copy(ended = true)
    }

    /** The first line of a NEW session replaces an ended panel instead of stacking under it. */
    fun beginIfEnded(): ChatTranscript = if (ended) ChatTranscript(speaker = speaker, nextId = nextId) else this

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
