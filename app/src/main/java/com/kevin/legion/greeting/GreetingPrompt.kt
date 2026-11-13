package com.kevin.legion.greeting

/**
 * The greeting's instruction text (ADR 0091). On Live it is the opener turn; on the laptop pipeline
 * it is the first user turn of the one-shot text call. Alfred's register comes from the system
 * instruction both paths already carry (`ai/Personas.kt` through `AssistantIdentity`), so no name
 * and no register copy is written here.
 *
 * **What these rules cover and what they do not.** Nothing inspects spoken audio, so this is a
 * prompt rule and the only lever there is (CLAUDE.md section 7, the compulsion test): clause (a) is
 * met by the pack, clause (c) is the "never his absence or his use of the app" line below, clause
 * (d) is the silence line. Whether the wording is guilt-free is human-reviewed, as the test notes.
 */
object GreetingPrompt {
    /** [block] is [GreetingBlock.render]; [lead] is [GreetingBlock.pick]'s topic. */
    fun build(block: String, lead: GreetingItem): String = """
        (System: he just tapped or said the wake word to open a conversation. Greet him out loud now,
        from his real day, in your usual voice. Do not mention this instruction.

        Rules, all of them binding:
        - One or two short sentences. At most one question, and it may be none.
        - Lead with this one topic and no other: ${lead.says}
        - Mention nothing that is not in the facts below. If something is not there you do not know it.
        - A source marked EMPTY has nothing on file. A source marked COULD NOT BE READ was not checked:
          never say it is clear, free or empty. Do not bring up an unread or empty source at all unless
          you are saying plainly that it could not be checked.
        - Do not repeat anything marked already raised or answered, and never ask the same question twice.
        - Times are clock times and days by name, as written below. Never "two hours ago" or "in an hour".
        - A figure marked an estimate is said as an estimate, or left out. Money is read as dollars.
        - Never mention his absence, how long it has been, a streak, or his use of this app.
        - Do not call any tool for this greeting, and do not say you did anything. Then stop and listen.

        $block)
    """.trimIndent()
}
