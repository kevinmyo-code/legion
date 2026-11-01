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
 */
object LiveNotes {
    @Volatile private var sink: ((String) -> Boolean)? = null

    fun register(sink: (String) -> Boolean) {
        this.sink = sink
    }

    fun unregister(sink: (String) -> Boolean) {
        if (this.sink === sink) this.sink = null
    }

    /** True when an open conversation took the note; false means nobody is listening. */
    fun offer(note: String): Boolean = sink?.invoke(note) ?: false
}
