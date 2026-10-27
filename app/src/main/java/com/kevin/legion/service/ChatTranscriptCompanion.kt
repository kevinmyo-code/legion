package com.kevin.legion.service

// Speaker reducer for [ChatTranscript], in its own file so the class itself stays under detekt's
// function limit.

/** Records who is answering from now on; used before a turn adds its ASSISTANT line. */
fun ChatTranscript.withSpeaker(name: String?): ChatTranscript = if (name == speaker) this else copy(speaker = name)
