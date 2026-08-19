package com.kevin.legion.ai

/**
 * The full set of prebuilt Gemini voices for the voice picker (widened
 * 2026-07-22 from an 8-voice curated shortlist - see decisions.md). [name] is
 * the actual `prebuiltVoiceConfig.voiceName` sent to the Live API; [descriptor]
 * is Google's published style word (docs: "Voice options" table,
 * ai.google.dev/gemini-api/docs/speech-generation, confirmed 2026-07-22).
 * Audition clips are bundled at res/raw/[sampleRawName] - as of 2026-08-02 all
 * 30 [CURATED_VOICES] have a generated clip there
 * (tools/generate_voice_samples.ps1). Still, callers that play the sample
 * (`ui/companions/VoiceAudition.kt`) resolve it by name via
 * `Resources.getIdentifier` and must handle a 0 (missing) result gracefully
 * rather than crash - a voice added to this list later without re-running the
 * generator script must degrade, not throw.
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
