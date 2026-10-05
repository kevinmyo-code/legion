package com.kevin.legion.meditations

/**
 * The two word lists [MeditationsSearch] reads: what to ignore, and how modern words reach Long's.
 * Split out of it because they are data, they are the part that gets tuned, and a long list of
 * strings should not sit between a reader and the scoring.
 */
internal object MeditationsVocabulary {

    /** A way into the text: any of [triggers] in the question adds [words] to the search at half weight. */
    class Concept(val triggers: List<String>, val words: List<String>)

    /**
     * Words that carry no topic, in the question's English and in Long's ("thou", "thy").
     * Long's pronouns are here too so that they never score: they are in every section.
     */
    val STOP: Set<String> = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does", "for",
        "from", "had", "has", "have", "he", "her", "him", "his", "how", "i", "if", "in", "into", "is", "it", "its",
        "me", "my", "of", "on", "or", "our", "she", "should", "so", "that", "the", "their", "them", "then", "there",
        "these", "they", "this", "those", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why",
        "will", "with", "would", "you", "your", "thou", "thee", "thy", "thine", "art", "dost", "hast", "doth",
        "shalt", "wilt", "tell", "say", "says", "about", "any", "some", "more", "most", "just", "very", "really",
        "marcus", "emperor", "meditations", "book", "passage", "quote", "something", "anything", "get", "got",
        "need", "want", "make", "know", "feel", "going", "way", "thing", "things", "like", "much", "many", "ve",
        "ll", "re", "dont", "don't", "im", "i'm", "us",
    )

    /**
     * Modern word or phrase to Long's vocabulary. Each entry was written by reading how Long
     * actually words the topic (grep the asset), not from a thesaurus. A multi-word trigger
     * matches the question as a phrase; a single word matches by stem.
     */
    val CONCEPTS: List<Concept> = listOf(
        Concept(
            listOf(
                "stress", "stressed", "anxious", "anxiety", "worry", "worried", "overwhelmed", "overwhelm", "panic",
                "nervous",
            ),
            listOf("trouble", "perturbation", "disturb", "vexed", "tranquillity", "fear", "uneasiness", "pressure"),
        ),
        Concept(
            listOf("angry", "furious", "rage", "mad", "irritated", "annoyed", "resentment", "resent", "temper"),
            listOf("anger", "wrath", "irritable", "offended", "passion", "temper", "gentle"),
        ),
        Concept(
            listOf("afraid", "scared", "terrified", "fear", "frightened"),
            listOf("fear", "afraid", "terror", "courage"),
        ),
        Concept(
            listOf("die", "dying", "dead", "mortal", "mortality", "funeral", "afterlife", "death"),
            listOf("death", "depart", "dissolution", "mortal", "perish", "nature", "life"),
        ),
        Concept(
            listOf(
                "morning", "wake", "waking", "awake", "bed", "alarm", "lazy", "unmotivated", "procrastinate",
                "procrastinating", "sluggish", "tired",
            ),
            listOf("morning", "rising", "bed-clothes", "work", "human", "unwillingly", "sleep", "delay"),
        ),
        Concept(
            listOf(
                "what others think", "what people think", "other people", "reputation", "approval", "judge",
                "judged", "criticism", "criticised", "embarrassed", "embarrassment", "popular", "opinion",
            ),
            listOf("opinion", "praise", "fame", "neighbours", "judgment", "applause", "honor", "ruling"),
        ),
        Concept(
            listOf(
                "insult", "insulted", "rude", "difficult people", "annoying", "coworker", "colleague", "boss",
                "jerk", "enemy", "betrayed", "betray", "revenge", "forgive", "forgiveness", "grudge",
            ),
            listOf(
                "busybody", "ungrateful", "wrong-doer", "injure", "avenging", "kindly", "forbear", "ignorance",
                "kinsman",
            ),
        ),
        Concept(
            listOf("job", "career", "duty", "responsibility", "task", "chores", "motivation", "discipline", "effort"),
            listOf("work", "duty", "act", "labor", "exertion", "action"),
        ),
        Concept(
            listOf("money", "wealth", "rich", "possessions", "stuff", "luxury", "buy", "spending", "shopping"),
            listOf("riches", "wealth", "possess", "luxury", "wanting", "little", "simplicity"),
        ),
        Concept(
            listOf("lonely", "loneliness", "alone", "solitude", "isolated"),
            listOf("retire", "solitude", "retreat", "soul", "social", "companions"),
        ),
        Concept(
            listOf("grief", "grieving", "mourn", "mourning", "loss", "lost", "sad", "sadness", "sorrow", "bereaved"),
            listOf("grief", "loss", "sorrow", "lose", "child", "friend", "lamenting", "affliction"),
        ),
        Concept(
            listOf("pain", "hurt", "hurting", "sick", "illness", "disease", "suffer", "suffering", "ache", "chronic"),
            listOf("pain", "disease", "sickness", "suffering", "endure", "flesh", "ill"),
        ),
        Concept(
            listOf(
                "happy", "happiness", "content", "contentment", "peace", "calm", "serenity", "meaning", "purpose",
                "fulfilled",
            ),
            listOf("tranquillity", "happy", "content", "quiet", "serene", "good", "flows"),
        ),
        Concept(
            listOf(
                "control", "accept", "acceptance", "fate", "unfair", "luck", "chance", "unlucky", "uncertainty",
                "uncertain", "out of my hands",
            ),
            listOf("power", "fate", "nature", "assigned", "providence", "lot", "external", "events"),
        ),
        Concept(
            listOf(
                "success", "ambition", "ambitious", "ego", "pride", "status", "power", "famous", "vain", "arrogant",
                "legacy", "remembered",
            ),
            listOf("fame", "vain-glory", "ambition", "arrogant", "posthumous", "forgetfulness", "oblivion", "empire"),
        ),
        Concept(
            listOf("body", "health", "exercise", "food", "eating", "diet", "flesh", "appetite"),
            listOf("flesh", "body", "health", "temperate", "eating", "bodily"),
        ),
        Concept(
            listOf(
                "distracted", "distraction", "focus", "attention", "phone", "news", "scrolling", "gossip",
                "curiosity", "multitask", "busy",
            ),
            listOf("unnecessary", "present", "wander", "over-curious", "purpose", "useless", "few"),
        ),
        Concept(
            listOf("honest", "honesty", "truth", "lie", "lying", "trust", "integrity"),
            listOf("truth", "true", "lie", "speak", "false", "honest"),
        ),
        Concept(
            listOf("habit", "habits", "practice", "routine", "character", "becoming", "improve", "improvement"),
            listOf("habitual", "habit", "practice", "thoughts", "character", "dyed", "improve"),
        ),
        Concept(
            listOf(
                "traffic", "commute", "delayed", "delay", "stuck", "blocked", "obstacle", "obstacles", "setback",
                "setbacks", "failure", "failed", "mistake", "regret",
            ),
            listOf("obstacle", "impediment", "hindrance", "furtherance", "action", "road", "wrong"),
        ),
        Concept(
            listOf(
                "family", "children", "kids", "marriage", "wife", "husband", "parent", "parents", "father",
                "mother", "friend", "friends", "love", "relationship", "relationships",
            ),
            listOf("kin", "children", "wife", "father", "mother", "friends", "affection", "love", "kinsmen"),
        ),
        Concept(
            listOf(
                "change", "changing", "impermanence", "transient", "temporary", "fleeting", "time", "passing",
                "past", "future", "present", "age", "aging", "old",
            ),
            listOf(
                "change", "flux", "stream", "river", "time", "present", "future", "past", "transformation",
                "perish",
            ),
        ),
        Concept(
            listOf("universe", "cosmos", "god", "gods", "providence", "nature", "logos", "reason", "rational"),
            listOf("universe", "nature", "reason", "gods", "providence", "whole", "world"),
        ),
        Concept(
            listOf("complain", "complaining", "blame", "blaming", "victim", "harm", "harmed"),
            listOf("complaint", "harmed", "harm", "opinion", "judgment"),
        ),
        Concept(
            listOf("justice", "fair", "ethics", "ethical", "moral", "morality", "good man", "virtue", "virtuous"),
            listOf("justice", "good", "virtue", "social", "act", "honest", "right"),
        ),
    )
}
