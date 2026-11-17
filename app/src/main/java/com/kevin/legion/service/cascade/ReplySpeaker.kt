package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whole words for a voice that takes streamed text (contract 4a): everything up to the last
 * whitespace goes now, the half-written word waits, so `$1,284.17` is never normalised in pieces.
 */
class WordChunker {
    private val buf = StringBuilder()

    fun feed(delta: String): String? {
        buf.append(delta)
        val cut = buf.indexOfLast { it.isWhitespace() }
        if (cut < 0) return null
        val out = buf.substring(0, cut + 1)
        buf.delete(0, cut + 1)
        return out
    }

    fun flush(): String? {
        val rest = buf.toString()
        buf.setLength(0)
        return rest.ifEmpty { null }
    }
}

/**
 * One reply's speech, from streamed model text to the speaker: text in ([text], [finishText]),
 * clips out to the [TextToSpeech] engine, audio played in order through [play]. Two ways in:
 * - the voice streams text ([TextToSpeech.streamsText], Kyutai): one clip, fed whole words;
 * - it does not (Kokoro): one clip per sentence from [SentenceSplitter].
 *
 * Ends with a [Outcome]: played to the end, interrupted, or failed (a laptop that stopped
 * speaking mid-reply; the session then hands over to Live). [spoken] is what was actually heard
 * (clips whose audio started), which is what history keeps after an interruption.
 */
@Suppress("TooManyFunctions") // text in, filler, scripted line, interrupt, drain: one reply's speech
internal class ReplySpeaker(
    private val tts: TextToSpeech,
    private val scope: CoroutineScope,
    private val play: suspend (pcm: ByteArray) -> Boolean,
    private val firstFrameTimeoutMs: Long,
    /** First audio of the reply ([filler] false), and of a scripted filler before it ([filler] true). */
    private val onFirstAudio: (clipText: String, filler: Boolean) -> Unit,
    private val onClipStarted: (clipText: String) -> Unit,
    private val normalise: (String) -> String = { SpokenText.forSpeech(it) },
    /** The reply's own leading filler sentence was dropped ([LeadingFiller]); logged as a count. */
    private val onFillerDropped: () -> Unit = {},
    /**
     * A scripted filler's audio was all handed to the player and nothing of the answer has played
     * yet. Half-duplex reopens the mic from here until the answer starts (2026-11-17).
     */
    private val onFillerPlayed: () -> Unit = {},
) {
    sealed interface Outcome {
        data object Played : Outcome
        data object Interrupted : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val queue = Channel<SpeechClip>(Channel.UNLIMITED)
    private val splitter = SentenceSplitter()
    private val chunker = WordChunker()

    /** Drops a leading "Checking." the model wrote itself, before anything is spoken or logged. */
    private val lead = LeadingFiller()

    /** This reply's tag allowance ([TtsTags]); a voice that does not perform them gets none. */
    private val tagBudget = TtsTags.Budget(if (tts.performsTags) TtsTags.MAX_PER_REPLY else 0)

    /** Holds a tag split across streamed deltas, for the voice that streams text (it never gets tags). */
    private val tagHold = TagHold()
    private var stream: StreamingSpeechClip? = null
    private var streamChars = 0
    private var streaming = tts.streamsText
    @Volatile private var current: SpeechClip? = null
    @Volatile private var interrupted = false
    private val started = mutableListOf<SpeechClip>()
    private var anyAudio = false
    private var anyFillerAudio = false

    /** Scripted fillers ([filler]): heard, never part of what the reply said. */
    private val fillers: MutableSet<SpeechClip> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<SpeechClip, Boolean>())
    @Volatile var anyText = false
        private set

    @Volatile private var result: Outcome? = null

    // Last: the drain reads every field above.
    private val player: Job = scope.launch { result = drain() }

    /**
     * Model text as it streams. A leading filler sentence ([LeadingFiller]) is held, then dropped,
     * so it is never spoken, never an `assistant text` line and never in [spoken]. Held text is not
     * yet [anyText]: the scripted filler may still play for it.
     */
    fun text(delta: String) {
        if (interrupted || delta.isEmpty()) return
        val wasDropped = lead.dropped
        val pass = lead.feed(delta)
        if (lead.dropped && !wasDropped) onFillerDropped()
        speakText(pass)
    }

    private fun speakText(text: String) {
        if (text.isEmpty()) return
        anyText = true
        if (streaming) {
            val bare = tagHold.feed(text)
            if (bare.isEmpty()) return
            chunker.feed(bare)?.let { appendStreamed(speakable(it)) }
        } else {
            splitter.feed(text).forEach { enqueue(it) }
        }
    }

    /** The model is done writing. A reply that is only a filler line is spoken as it is. */
    fun finishText() {
        if (interrupted) return
        speakText(lead.flush())
        if (streaming) {
            tagHold.flush().takeIf { it.isNotEmpty() }?.let { chunker.feed(it) }
            chunker.flush()?.let { appendStreamed(speakable(it)) }
            stream?.finish()
        } else {
            splitter.flush()?.let { enqueue(it) }
        }
        queue.close()
    }

    /**
     * A short scripted line ("One moment.") played before the reply, while a slow tool runs. Never
     * part of [spoken] (it claims nothing and is not the answer). Only before any reply text, and
     * dropped unheard if reply text arrives before its first audio does.
     */
    fun filler(line: String): Boolean {
        if (interrupted || anyText) return false
        val clip = tts.speak(normalise(line))
        fillers.add(clip)
        queue.trySend(clip)
        return true
    }

    /**
     * Speaks [line] as its own clip (a scripted line, a failure in words), then ends. A short line
     * is one whole clip even on a voice that streams text (2026-11-15): the whole text is known, a
     * whole clip starts sooner on Kyutai (~0.75 s against ~1.0 s streamed, 11-10), and a kept
     * scripted line plays from memory ([LaptopVoiceConnection]'s keepLines).
     */
    fun say(line: String, tagsAllowed: Boolean = false) {
        if (!interrupted && line.isNotBlank() && line.length <= SAY_WHOLE_CHARS) {
            anyText = true
            enqueue(line, plain = !tagsAllowed)
            queue.close()
            return
        }
        text(if (tagsAllowed) line else TtsTags.strip(line))
        finishText()
    }

    /** Stops now: no more audio from this reply. */
    fun interrupt() {
        interrupted = true
        queue.close()
        current?.cancel()
        while (true) {
            val c = queue.tryReceive().getOrNull() ?: break
            c.cancel()
        }
        stream?.cancel()
    }

    suspend fun await(): Outcome {
        player.join()
        return result ?: Outcome.Interrupted
    }

    /** What was heard: whole clips whose audio started. */
    fun spoken(): String = TtsTags.strip(
        synchronized(started) { started.filterNot { it in fillers }.joinToString(" ") { it.text.trim() } }.trim(),
    )

    /**
     * Into the current streamed clip; a clip that would pass [STREAM_CHAR_CAP] is finished and the
     * text goes on in a new one (the service closes an id past 1,000 chars, contract changelog).
     */
    private fun appendStreamed(chunk: String) {
        if (chunk.isEmpty()) return
        val current = stream
        if (current != null && streamChars + chunk.length > STREAM_CHAR_CAP) {
            current.finish()
            stream = null
        }
        val s = stream ?: tts.openStream().also {
            stream = it
            streamChars = 0
            queue.trySend(it)
        }
        s.append(chunk)
        streamChars += chunk.length
    }

    private fun speakable(chunk: String): String {
        // Keep the edges: a streamed chunk's spaces are part of the text.
        val lead = chunk.takeWhile { it.isWhitespace() }
        val trail = chunk.takeLastWhile { it.isWhitespace() }
        val core = normalise(chunk.trim())
        return if (core.isEmpty()) chunk else lead + core + trail
    }

    /**
     * One sentence to the voice. Tags ([TtsTags]) only for a voice that performs them, within this
     * reply's allowance, never on a [plain] line (a scripted error or failure) or a figure or bad news.
     */
    private fun enqueue(sentence: String, plain: Boolean = false) {
        val tagged = if (tts.performsTags) TtsTags.forSpeech(sentence, tagBudget, plain) else TtsTags.strip(sentence)
        val line = normalise(tagged)
        if (TtsTags.strip(line).isBlank()) return
        queue.trySend(tts.speak(line))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // one exit per clip outcome
    private suspend fun drain(): Outcome {
        for (clip in queue) {
            if (interrupted) {
                clip.cancel()
                continue
            }
            current = clip
            val first = withTimeoutOrNull(firstFrameTimeoutMs) { clip.frames.receiveCatching() }
            if (first == null) {
                clip.cancel()
                return if (interrupted) Outcome.Interrupted else Outcome.Failed("first_audio_timeout")
            }
            val firstPcm = first.getOrNull()
            if (firstPcm == null) {
                // The clip ended with no audio: a failure, a cancel, or an empty clip.
                when (val o = clip.outcome()) {
                    is TtsOutcome.Failed -> {
                        if (interrupted) return Outcome.Interrupted
                        // A voice that refuses streamed text: replay this reply sentence by sentence.
                        if (clip === stream && !anyAudio && o.reason == "bad_request") {
                            streaming = false
                            stream = null
                            fallbackToSentences(clip.text)
                            continue
                        }
                        return Outcome.Failed("tts_${o.reason}")
                    }
                    TtsOutcome.Cancelled -> if (interrupted) return Outcome.Interrupted
                    is TtsOutcome.Done -> Unit
                }
                continue
            }
            val isFiller = clip in fillers
            // The answer's text arrived before the filler's first audio: the filler would only hold
            // the answer up behind it, so it is dropped unheard (2026-11-16).
            if (isFiller && anyText) {
                clip.cancel()
                continue
            }
            synchronized(started) { started.add(clip) }
            // Captions, the audit and history see the words only, never a tag.
            if (!isFiller) onClipStarted(TtsTags.strip(clip.text))
            if (isFiller && !anyFillerAudio && !anyAudio) {
                anyFillerAudio = true
                onFirstAudio(clip.text, true)
            } else if (!isFiller && !anyAudio) {
                anyAudio = true
                onFirstAudio(clip.text, false)
            }
            if (!play(firstPcm)) return Outcome.Interrupted
            for (pcm in clip.frames) {
                if (interrupted || !play(pcm)) return Outcome.Interrupted
            }
            if (isFiller && !anyAudio && !interrupted) onFillerPlayed()
            when (val o = clip.outcome()) {
                is TtsOutcome.Failed ->
                    return if (interrupted) Outcome.Interrupted else Outcome.Failed("tts_${o.reason}")
                TtsOutcome.Cancelled -> return Outcome.Interrupted
                is TtsOutcome.Done -> Unit
            }
        }
        return if (interrupted) Outcome.Interrupted else Outcome.Played
    }

    /**
     * The streamed clip was refused before any audio: the text it was given, plus whatever is still
     * in the chunker, goes again as sentences. [queue] may already be closed (the model finished),
     * so the sentences are spoken from here directly.
     */
    @Suppress("ReturnCount")
    private suspend fun fallbackToSentences(sentSoFar: String) {
        val rest = chunker.flush().orEmpty()
        val sentences = SentenceSplitter().let { s -> s.feed(sentSoFar + rest) + listOfNotNull(s.flush()) }
        for (s in sentences) {
            if (interrupted) return
            val clip = tts.speak(normalise(s))
            current = clip
            val first = withTimeoutOrNull(firstFrameTimeoutMs) { clip.frames.receiveCatching() }
                ?.getOrNull() ?: continue
            synchronized(started) { started.add(clip) }
            if (!anyAudio) {
                anyAudio = true
                onFirstAudio(clip.text, false)
            }
            if (!play(first)) return
            for (pcm in clip.frames) if (interrupted || !play(pcm)) return
        }
    }

    /** Streamed text with every tag removed, a tag split across deltas held until it closes. */
    private class TagHold {
        private val held = StringBuilder()

        fun feed(delta: String): String {
            held.append(delta)
            val s = held.toString()
            val open = s.lastIndexOf('<')
            // An unclosed "<" near the end may be a tag still arriving: hold from there.
            val cut = if (open >= 0 && s.indexOf('>', open) < 0 && s.length - open <= MAX_TAG) open else s.length
            held.setLength(0)
            held.append(s, cut, s.length)
            return TtsTags.strip(s.substring(0, cut))
        }

        fun flush(): String = TtsTags.strip(held.toString()).also { held.setLength(0) }

        private companion object {
            const val MAX_TAG = 24
        }
    }

    companion object {
        /** Under the service's 1,000 chars per streamed id, with room for a last word. */
        const val STREAM_CHAR_CAP = 900

        /** A [say] line up to this long is one whole clip; longer (a re-voiced reply) goes as usual. */
        const val SAY_WHOLE_CHARS = 300
    }
}

