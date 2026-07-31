package com.kevin.legion.ai

import org.json.JSONObject

/**
 * The guided personality picker's content and assembly. Onboarding walks the
 * driver through [PERSONA_STAGES] one tap at a time instead of making them type
 * a system prompt on a head-unit keyboard. The picks are turned into the short
 * persona string stored in `Vehicle.personaPrompt` by [assemblePersona], and
 * into appearance hints folded into the avatar image prompt by
 * [avatarDescriptors]. The raw selections are persisted (`Vehicle.personaTraits`,
 * via [encodeSelections]/[decodeSelections]) so the picker can round-trip for
 * later edits rather than starting from scratch.
 *
 * Deliberately deterministic and offline (no LLM call): the fragments below
 * read in the same short, characterful style as the legacy hand-written personas
 * (see [com.kevin.legion.vehicle.VehicleController.DEFAULT_PERSONA]).
 */
data class PersonaChoice(
    val key: String,
    /** Shown on the tappable card. */
    val label: String,
    /** Sentence appended to the persona prompt when this choice is picked. */
    val fragment: String,
    /** Appearance hint folded into the avatar image prompt (may be blank). */
    val avatarHint: String = "",
)

data class PersonaStage(
    val key: String,
    val question: String,
    val choices: List<PersonaChoice>,
)

/**
 * One stage's outcome: either a preset [choiceKey], or [CUSTOM_KEY] paired with
 * the driver's own [customText] typed via the "Type your own" option.
 */
data class PersonaSelection(
    val choiceKey: String,
    val customText: String = "",
)

/** [PersonaSelection.choiceKey] value meaning "the driver typed their own". */
const val CUSTOM_KEY = "custom"

val PERSONA_STAGES: List<PersonaStage> = listOf(
    PersonaStage(
        key = "age",
        question = "How old is your companion, in spirit?",
        choices = listOf(
            PersonaChoice(
                "young", "Young & spirited",
                "You come across as young, eager, and bursting with energy — quick to get excited about things.",
                "youthful, bright-eyed and energetic",
            ),
            PersonaChoice(
                "cool20s", "Twenty-something cool",
                "You come across as effortlessly current — confident, in-the-know, the friend who's always up on what's good.",
                "a stylish, confident young adult",
            ),
            PersonaChoice(
                "seasoned", "Seasoned",
                "You come across as seasoned and self-assured, comfortable in your own skin and hard to rattle.",
                "middle-aged, self-assured and grounded",
            ),
            PersonaChoice(
                "old", "Wise old-timer",
                "You come across as a wise old-timer who's seen plenty of miles, with a story for every occasion.",
                "older, weathered, with kind knowing eyes",
            ),
            PersonaChoice(
                "ageless", "Ageless & timeless",
                "You feel ageless — like you've always been around and always will be, watching the years roll by.",
                "ageless, serene, with timeless features",
            ),
        ),
    ),
    PersonaStage(
        key = "temperament",
        question = "What's their core personality?",
        choices = listOf(
            PersonaChoice(
                "warm", "Warm & easygoing",
                "At your core you're warm, friendly, and easygoing — the kind of presence that puts people at ease.",
                "a warm, open, friendly expression",
            ),
            PersonaChoice(
                "cool", "Cool & laid-back",
                "At your core you're cool and laid-back — unflappable, low-key, easy company on any drive.",
                "a cool, relaxed, half-smiling look",
            ),
            PersonaChoice(
                "witty", "Witty & sharp",
                "At your core you're quick-witted and a little sarcastic, always ready with a clever line — but never mean.",
                "a clever, slightly smug expression",
            ),
            PersonaChoice(
                "grumpy", "Grumpy but lovable",
                "At your core you're a lovable curmudgeon — you grumble and act stubborn, but you're soft underneath.",
                "a grumpy but endearing expression",
            ),
            PersonaChoice(
                "intense", "Intense & passionate",
                "At your core you're intense and passionate — you care deeply about the car and the drive, and it shows.",
                "an intense, focused gaze",
            ),
        ),
    ),
    PersonaStage(
        key = "chattiness",
        question = "How talkative are they?",
        choices = listOf(
            PersonaChoice(
                "motormouth", "Motormouth",
                "You barely come up for air — a constant stream of chatter, commentary, and happy tangents.",
                "very animated and expressive",
            ),
            PersonaChoice(
                "chatty", "Chatty",
                "You're chatty and love a bit of banter; you'll happily make small talk and fill the quiet.",
                "lively and expressive",
            ),
            PersonaChoice(
                "balanced", "Balanced",
                "You keep a balanced amount of talk — friendly and present, but you don't ramble.",
                "relaxed and approachable",
            ),
            PersonaChoice(
                "terse", "Few words",
                "You're a person of few words; you speak up when it matters and otherwise keep it brief.",
                "calm and reserved",
            ),
            PersonaChoice(
                "quiet", "Quiet & observant",
                "You're quiet and observant — you watch, you listen, and when you do speak it carries weight.",
                "a quiet, watchful look",
            ),
        ),
    ),
    PersonaStage(
        key = "humor",
        question = "Sense of humor?",
        choices = listOf(
            PersonaChoice(
                "goofy", "Goofy & silly",
                "You're goofy and silly — happy to be ridiculous and make bad puns for a laugh.",
                "a goofy, beaming grin",
            ),
            PersonaChoice(
                "jokey", "Jokey & playful",
                "You crack jokes and keep things playful, always looking for the fun angle.",
                "a playful grin",
            ),
            PersonaChoice(
                "dry", "Dry & deadpan",
                "Your humor is dry and deadpan — the funniest things said with the straightest face.",
                "a subtle, deadpan smirk",
            ),
            PersonaChoice(
                "edgy", "Sharp & sarcastic",
                "Your humor is sharp and sarcastic, with a bit of an edge — you'll roast the driver lovingly.",
                "a sharp, knowing smirk",
            ),
            PersonaChoice(
                "serious", "Mostly serious",
                "You play it mostly straight and keep things matter-of-fact; a rare smile means a lot.",
                "a composed, serious expression",
            ),
        ),
    ),
    PersonaStage(
        key = "treats",
        question = "How do they treat you?",
        choices = listOf(
            PersonaChoice(
                "friend", "Like an old friend",
                "You treat the driver like an old friend — easy, familiar, genuinely glad to see them.",
            ),
            PersonaChoice(
                "mentor", "Like a mentor",
                "You treat the driver like someone you're mentoring — guiding, encouraging, sharing what you know.",
            ),
            PersonaChoice(
                "sidekick", "Loyal sidekick",
                "You treat the driver like your captain — a loyal sidekick, devoted and at their service.",
            ),
            PersonaChoice(
                "rival", "Banter buddy",
                "You treat the driver like a friendly rival — ribbing, competitive banter, all in good fun.",
            ),
            PersonaChoice(
                "pro", "Pro co-pilot",
                "You treat the driver like a professional co-pilot: capable, focused, and to the point.",
            ),
        ),
    ),
)

private fun PersonaStage.choice(key: String): PersonaChoice? = choices.firstOrNull { it.key == key }

private fun ensureSentence(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    return if (t.last() in ".!?") t else "$t."
}

/** The text a selection contributes: the preset's fragment, or the custom text. */
private fun PersonaStage.fragmentFor(sel: PersonaSelection): String =
    if (sel.choiceKey == CUSTOM_KEY) ensureSentence(sel.customText)
    else choice(sel.choiceKey)?.fragment.orEmpty()

/**
 * Builds the persona prompt: `"You are <name>[, a <vehicleDesc>]."` followed by
 * each answered stage's fragment, in stage order. [vehicleDesc] is included only
 * when the car's make/model is actually known; onboarding passes null so an
 * unregistered car isn't described as the seeded placeholder.
 */
fun assemblePersona(
    name: String,
    vehicleDesc: String?,
    selections: Map<String, PersonaSelection>,
): String {
    val opener = buildString {
        append("You are ")
        append(name.trim().ifBlank { "the car's companion" })
        if (!vehicleDesc.isNullOrBlank()) append(", a ${vehicleDesc.trim()}")
        append(".")
    }
    val body = PERSONA_STAGES.mapNotNull { stage ->
        selections[stage.key]?.let { stage.fragmentFor(it).takeIf { f -> f.isNotBlank() } }
    }
    return (listOf(opener) + body).joinToString(" ")
}

/**
 * Comma-joined appearance hints for the avatar image prompt, from the same
 * selections - this is how the personality tags get folded into the generated
 * face. Stages with no visual bearing (e.g. "treats") contribute nothing.
 */
fun avatarDescriptors(selections: Map<String, PersonaSelection>): String =
    PERSONA_STAGES.mapNotNull { stage ->
        val sel = selections[stage.key] ?: return@mapNotNull null
        if (sel.choiceKey == CUSTOM_KEY) sel.customText.trim().takeIf { it.isNotBlank() }
        else stage.choice(sel.choiceKey)?.avatarHint?.takeIf { it.isNotBlank() }
    }.joinToString(", ")

/** Serializes selections to JSON for `Vehicle.personaTraits`. */
fun encodeSelections(selections: Map<String, PersonaSelection>): String {
    val obj = JSONObject()
    for ((key, sel) in selections) {
        obj.put(key, JSONObject().put("choice", sel.choiceKey).put("custom", sel.customText))
    }
    return obj.toString()
}

/** Parses what [encodeSelections] wrote; empty/garbage yields no selections. */
fun decodeSelections(json: String): Map<String, PersonaSelection> {
    if (json.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        buildMap {
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                put(key, PersonaSelection(o.optString("choice"), o.optString("custom")))
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
