package com.kevin.legion.ai

/**
 * The full set of prebuilt Gemini voices for the voice picker (widened
 * 2026-07-22 from an 8-voice curated shortlist - see decisions.md). [name] is
 * the actual `prebuiltVoiceConfig.voiceName` sent to the Live API; [descriptor]
 * is Google's published style word (docs: "Voice options" table,
 * ai.google.dev/gemini-api/docs/speech-generation, confirmed 2026-07-22).
 * Audition clips are bundled at res/raw/[sampleRawName] - NOTE: only the
 * former 8-voice shortlist has a generated sample today
 * (tools/generate_voice_samples.ps1); the newly-added 22 have no bundled clip
 * yet, so [sampleRawName] may point at a missing raw resource for those until
 * the generator script is re-run. Callers that play the sample must handle a
 * missing resource gracefully (e.g. resource-not-found) rather than crash.
 */
data class GeminiVoice(val name: String, val descriptor: String) {
    val sampleRawName: String get() = "voice_sample_${name.lowercase()}"
}

/** Falls back to this when a car has no voice chosen (matches GeminiLiveSession's VOICE). Warm + female — the mascot is Zero. */
const val DEFAULT_VOICE = "Sulafat"

val CURATED_VOICES = listOf(
    GeminiVoice("Zephyr", "Bright"),
    GeminiVoice("Puck", "Upbeat"),
    GeminiVoice("Charon", "Informative"),
    GeminiVoice("Kore", "Firm"),
    GeminiVoice("Fenrir", "Excitable"),
    GeminiVoice("Leda", "Youthful"),
    GeminiVoice("Orus", "Firm"),
    GeminiVoice("Aoede", "Breezy"),
    GeminiVoice("Callirrhoe", "Easy-going"),
    GeminiVoice("Autonoe", "Bright"),
    GeminiVoice("Enceladus", "Breathy"),
    GeminiVoice("Iapetus", "Clear"),
    GeminiVoice("Umbriel", "Easy-going"),
    GeminiVoice("Algieba", "Smooth"),
    GeminiVoice("Despina", "Smooth"),
    GeminiVoice("Erinome", "Clear"),
    GeminiVoice("Algenib", "Gravelly"),
    GeminiVoice("Rasalgethi", "Informative"),
    GeminiVoice("Laomedeia", "Upbeat"),
    GeminiVoice("Achernar", "Soft"),
    GeminiVoice("Alnilam", "Firm"),
    GeminiVoice("Schedar", "Even"),
    GeminiVoice("Gacrux", "Mature"),
    GeminiVoice("Pulcherrima", "Forward"),
    GeminiVoice("Achird", "Friendly"),
    GeminiVoice("Zubenelgenubi", "Casual"),
    GeminiVoice("Vindemiatrix", "Gentle"),
    GeminiVoice("Sadachbia", "Lively"),
    GeminiVoice("Sadaltager", "Knowledgeable"),
    GeminiVoice("Sulafat", "Warm"),
)
