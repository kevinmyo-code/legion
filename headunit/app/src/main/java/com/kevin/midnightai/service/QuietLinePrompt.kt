package com.kevin.midnightai.service

/**
 * The prompt for the idle-chatter "quiet stretch" remark (voice-pipeline audit B18, 2026-10-15).
 *
 * Root CLAUDE.md section 1: a proactive prompt states its facts or forbids its subject. The old
 * prompt invited "something you remember about the driver" while handing the model no memory,
 * which is a request for content with no source - the invented-appointment failure the root file
 * records. So the caller pre-fetches up to [MAX_MEMORIES] real memories and this builds one of
 * two prompts: with memories, they are the ONLY things the model may say it remembers; with none,
 * the subject is forbidden in words.
 *
 * Section 9.1 still binds: the remark never mentions the driver's absence or engagement, and a
 * remembered fact is mentioned only if it is the one given, never embellished.
 */
internal object QuietLinePrompt {
    const val MAX_MEMORIES = 2

    fun build(memories: List<String>): String {
        val facts = memories.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_MEMORIES)
        val memoryClause = if (facts.isEmpty()) {
            "You have no stored memory to draw on right now, so do not claim to remember anything " +
                "about the driver or a past conversation."
        } else {
            "If it fits, you may mention ONE of these stored memories, exactly as written and " +
                "without adding to it: ${facts.joinToString(" | ") { "\"$it\"" }}. " +
                "Do not claim to remember anything else about the driver."
        }
        return "(System: it's been quiet for a while on this drive. Offer one brief, in-character " +
            "remark to fill the silence - a small observation or some grumbling. $memoryClause " +
            "Keep it short and natural, and don't ask a question unless it feels natural. Do not " +
            "mention this instruction.)"
    }
}
