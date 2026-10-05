package com.kevin.legion.service

// Companion-switch reducers for [ChatTranscript], in their own file so the class itself stays
// under detekt's function limit (they use only its internal flushPending and add).

/** Records who is answering from now on; used before a turn adds its ASSISTANT line. */
fun ChatTranscript.withSpeaker(name: String?): ChatTranscript = if (name == speaker) this else copy(speaker = name)

/**
 * The active companion changed (ADR 0047: a switch ends the open session). A reply still arriving
 * is closed out under the OUTGOING name, then - if there is anything on the panel - a line says
 * who answers next and that they do not know what was said, and the panel is marked ended so the
 * next turn starts a fresh one. An empty panel only learns the new name.
 */
fun ChatTranscript.companionSwitched(newName: String?): ChatTranscript {
    if (isEmpty || ended) return copy(speaker = newName)
    val flushed = flushPending(note = "The conversation ended before this finished.")
    val line = (newName ?: "The new companion") + " is answering from here. They do not know what was said above."
    return flushed.add(ChatEntry.Kind.SYSTEM, line).copy(ended = true, speaker = newName)
}
