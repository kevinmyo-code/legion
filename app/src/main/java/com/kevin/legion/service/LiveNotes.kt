package com.kevin.legion.service

/**
 * The one door from outside the voice session into an OPEN conversation: a system note the model
 * reads at the next quiet moment and answers in its own words (claude-session ticket 05: a
 * finished task is "fed in as a system note and spoken" while a conversation is open).
 *
 * [LiveSessionController] registers its sink at construction; [offer] returns false whenever no
 * conversation is open (or no controller exists), so the caller falls back to the proactive raise.
 * The note waits in the session until nobody is speaking and no reply is being generated, the same
 * moment a cancelled write's note goes in ([GeminiLiveSession.offerNote]).
 *
 * **A note has a key, and a waiting note can be withdrawn** (2026-11-05, A25 LIVE_AUDIT 11-04): a
 * finished Claude task's note was queued while the user was mid-turn asking "what did Claude say",
 * the `claude_session` check in that same turn read the result out, and the queued note then went
 * in after the reply and the result was spoken a second time. [withdraw] drops it once something
 * else has said it.
 */
object LiveNotes {
    /** Where notes go: the open conversation, or nobody. */
    interface Sink {
        /** True when an open conversation took the note. */
        fun offer(key: String, text: String): Boolean

        /** Drops a note still waiting under [key]; a note already sent cannot be taken back. */
        fun withdraw(key: String)
    }

    @Volatile private var sink: Sink? = null

    fun register(sink: Sink) {
        this.sink = sink
    }

    fun unregister(sink: Sink) {
        if (this.sink === sink) this.sink = null
    }

    /** True when an open conversation took the note; false means nobody is listening. */
    fun offer(key: String, note: String): Boolean = sink?.offer(key, note) ?: false

    /** No-op when no conversation is registered. */
    fun withdraw(key: String) {
        sink?.withdraw(key)
    }

    /** The key a finished Claude session task's note is queued under. */
    fun claudeTaskKey(taskId: Int): String = "claude_task_$taskId"
}

/**
 * The notes waiting for a quiet moment in one session ([GeminiLiveSession.offerNote]), in arrival
 * order, each under its [LiveNotes] key. Thread-safe: offered from a background poll, withdrawn
 * from a tool call, drained on the socket's thread. Pure so a JVM test pins it.
 */
class OutsideNoteQueue {
    private val notes = ArrayList<Pair<String, String>>()

    @Synchronized
    fun add(key: String, text: String) {
        notes += key to text
    }

    @Synchronized
    fun withdraw(key: String) {
        notes.removeAll { it.first == key }
    }

    @Synchronized
    fun isNotEmpty(): Boolean = notes.isNotEmpty()

    /** Every waiting note's text, oldest first, and empties the queue. */
    @Synchronized
    fun drain(): List<String> = notes.map { it.second }.also { notes.clear() }
}
