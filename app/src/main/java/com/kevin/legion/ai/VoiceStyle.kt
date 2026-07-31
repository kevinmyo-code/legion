package com.kevin.legion.ai

/**
 * A guided DELIVERY picker - same shape as [PERSONA_STAGES] but for HOW the
 * companion sounds, not who they are. Gemini's Live API exposes no numeric
 * pitch/rate/prosody parameters (confirmed against the docs,
 * `.scratch/drive-notes-batch/research-gemini-voice-tuning.md`, 2026-07-18) -
 * the only documented lever for shaping delivery is natural-language steering
 * in the prompt. This is that lever, made pickable instead of hand-typed,
 * layered ON TOP of the [CURATED_VOICES] preset choice (picking Algenib vs
 * Sulafat) rather than replacing it - the preset sets the base timbre, this
 * steers pace/warmth/energy within it.
 *
 * Reuses [PersonaSelection]/[CUSTOM_KEY]/[encodeSelections]/[decodeSelections]
 * from PersonaTraits.kt - that machinery is already generic (a stage key,
 * choice key, optional custom text), no need to duplicate it for a second
 * kind of picker.
 *
 * Deliberately small (3 stages, not 5): delivery has fewer independent axes
 * than personality before choices start fighting each other in the prompt
 * (e.g. "brisk pace" + "hushed and quiet" pulling in different directions).
 */
val VOICE_STYLE_STAGES: List<PersonaStage> = listOf(
    PersonaStage(
        key = "pace",
        question = "How fast do they talk?",
        choices = listOf(
            PersonaChoice(
                "brisk", "Brisk & quick",
                "Speak at a brisk, quick pace - efficient, no dawdling on words.",
            ),
            PersonaChoice(
                "relaxed", "Relaxed & unhurried",
                "Speak at a relaxed, unhurried pace - take your time, never rushed.",
            ),
            PersonaChoice(
                "measured", "Measured & even",
                "Speak at a measured, even pace - steady and deliberate, not fast or slow.",
            ),
            PersonaChoice(
                "clipped", "Clipped & terse",
                "Speak in short, clipped bursts - get to the point fast, minimal wind-up.",
            ),
        ),
    ),
    PersonaStage(
        key = "tone",
        question = "What does their tone feel like?",
        choices = listOf(
            PersonaChoice(
                "soft", "Soft & warm",
                "Let your tone stay soft and warm, like a low, easy voice late at night.",
            ),
            PersonaChoice(
                "crisp", "Crisp & clear",
                "Keep your tone crisp and clear, articulate and easy to make out over road noise.",
            ),
            PersonaChoice(
                "cool", "Cool & even",
                "Keep your tone cool and even, unbothered, nothing dramatic in the delivery.",
            ),
            PersonaChoice(
                "husky", "Husky & low",
                "Let your tone sit low and husky, a little rough at the edges.",
            ),
        ),
    ),
    PersonaStage(
        key = "energy",
        question = "How much energy do they bring?",
        choices = listOf(
            PersonaChoice(
                "lively", "Lively & animated",
                "Bring lively, animated energy to your delivery - inflection moves, it doesn't stay flat.",
            ),
            PersonaChoice(
                "calm", "Calm & steady",
                "Keep your energy calm and steady - grounded, nothing that spikes or rushes.",
            ),
            PersonaChoice(
                "understated", "Understated & dry",
                "Keep your energy understated and dry - low-key delivery even when the words are excited.",
            ),
        ),
    ),
)

/**
 * Builds the delivery-notes paragraph appended to the system instruction. The
 * "Delivery notes" framing exists to keep this from being read as MORE
 * character text - it's how the persona should be voiced, not additional
 * personality, so it must not blend into the persona sentences it follows.
 * Empty selections yield an empty string, so an untouched picker changes
 * nothing about today's delivery.
 */
fun assembleVoiceStyle(selections: Map<String, PersonaSelection>): String {
    val body = VOICE_STYLE_STAGES.mapNotNull { stage ->
        selections[stage.key]?.let { stage.fragmentFor(it).takeIf { f -> f.isNotBlank() } }
    }
    if (body.isEmpty()) return ""
    return "Delivery notes, separate from your personality - this is HOW you sound, not who you " +
        "are: " + body.joinToString(" ")
}

private fun PersonaStage.fragmentFor(sel: PersonaSelection): String =
    if (sel.choiceKey == CUSTOM_KEY) sel.customText.trim().ifBlank { "" }.let { if (it.isBlank()) "" else ensureVoiceSentence(it) }
    else choices.firstOrNull { it.key == sel.choiceKey }?.fragment.orEmpty()

private fun ensureVoiceSentence(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    return if (t.last() in ".!?") t else "$t."
}
