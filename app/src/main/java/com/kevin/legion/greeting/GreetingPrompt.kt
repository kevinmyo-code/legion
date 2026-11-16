package com.kevin.legion.greeting

/**
 * The greeting's instruction text (ADR 0091). On Live it is the opener turn; on the laptop and cloud
 * voices it is the first user turn of the one-shot text call. Alfred's register comes from the system
 * instruction both paths already carry (`ai/Personas.kt` through `AssistantIdentity`), so no name
 * and no register copy is written here.
 *
 * **Casual, not a report (Kevin, 2026-11-16: the greetings read like a report; he wants them
 * casual, dry-witty and short).** The lead used to be pasted in as "lead with this
 * topic: <the fact text>", and with the persona's "numbers first" the model read the fact out, cents
 * and all. Now the lead is a few-word subject ([subject]) and a `[LEAD]` mark on its line in the
 * block, and the rules ask for a reaction, at most one rounded figure and never cents. The persona
 * says "numbers first" for answers; this line overrides it for the greeting only, in words. No
 * example quip is written here: an example in a prompt becomes a catchphrase (`ai/Personas.kt`).
 *
 * **What these rules cover and what they do not.** Nothing inspects spoken audio, so this is a
 * prompt rule and the only lever there is (CLAUDE.md section 7, the compulsion test): clause (a) is
 * met by the pack, clause (c) is the "never his absence or his use of the app" line below, clause
 * (d) is the silence line. Whether the wording is guilt-free is human-reviewed, as the test notes;
 * the no-shame line for money and an undone workout is the lever for it.
 */
object GreetingPrompt {
    /** [block] is [GreetingBlock.render] with [lead] marked; [lead] is [GreetingBlock.lead]'s topic. */
    fun build(block: String, lead: GreetingBlock.Lead): String = """
        (System: he just tapped or said the wake word to open a conversation. Greet him out loud now,
        in your usual voice. Do not mention this instruction.

        Your one subject: ${subject(lead)}. Its facts are the line marked ${GreetingBlock.LEAD_MARK} below.
        Every other line is background only: do not mention it, and money least of all.

        How it sounds, for this greeting only:
        - One short sentence, fifteen words at most. Casual and dry, the way an old friend says hello,
          not a status report. Plain American English.
        - Go straight to the subject, no time-of-day greeting first. React to it in your own words, the
          reaction first; never read its facts out or list them.
        - Numbers are not first here. Use no figure, or at most one, rounded hard to a round number.
          Never cents, never two amounts, never how many are done or left.
        - React as if you just found out. You know nothing of earlier days, so nothing may sound like
          it happened before, and say no day count or timing the facts do not give.
        - About money or a workout not done: no shame, no nagging, no telling him what he should do.
        - At most one question, and it may be none.

        Rules, all of them binding:
        - Mention nothing else: no other item, no other budget, nothing that is not in the facts below.
          If something is not there you do not know it.
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

    /**
     * The opener when there is nothing to raise (2026-11-16). Capping money left more opens with no
     * topic, and the bare scripted line there is what Kevin found too bare earlier. A proactive
     * prompt states its facts or forbids its subject (CLAUDE.md section 1): this one hands over no
     * facts and forbids every subject, the context note the cascade puts on the first turn included.
     */
    fun hello(): String = """
        (System: he just tapped or said the wake word to open a conversation, and there is nothing in
        particular to bring up. Say hello out loud now, in your usual voice. Do not mention this
        instruction.

        - A few words, one short sentence at most. Casual and dry, the way an old friend picks up.
          Plain American English, and it must make sense on its own.
        - No subject at all. Nothing about his day, plans, calendar, to-dos, work, money, budgets,
          workouts, health, food, sleep, weather, news, where he is, the time or the day of the week.
          Not a time-of-day greeting either, so no "morning" or "evening". Use nothing from any
          context note in this conversation, even if one is there.
        - Friendly, never curt or bossy. Not a stock one-word hello: give it a little character.
        - It may hand the floor to him, or not. At most one question.
        - Never mention his absence, how long it has been, a streak, or his use of this app.
        - Do not call any tool, and do not say you did anything. Then stop and listen.)
    """.trimIndent()

    /**
     * The lead in a few words, so the model is handed a subject to react to and not a sentence to
     * read out. Only words the pack already carries: the source, and a budget's category from its
     * topic key (`money:<month>:<category>`).
     */
    fun subject(lead: GreetingBlock.Lead): String = when (lead.source) {
        // Place alone gets "add nothing": asked to react to a bare "he is away", the model made up
        // the rest (an empty house, a slow day); tone check 2026-11-16, place-only attempt.
        GreetingPlace.SOURCE -> "that he is away from home today. Name the place in a plain few words and " +
            "add nothing to it: no guess about why he is there, what it is like, or what he left behind"
        "body" -> if (lead.item.kind == "weigh_in") "his weigh-in" else "today's workout"
        "money" -> lead.item.topic.substringAfter(':', "").substringAfter(':', "").takeIf { it.isNotBlank() }
            ?.let { "his $it budget, which is over its cap" } ?: "a budget that is over its cap"
        "due_soon" -> "something he has due soon"
        "ended_events" -> "an event of his that has ended"
        "today_events" -> "what is still on his calendar today"
        else -> "the line marked ${GreetingBlock.LEAD_MARK}"
    }
}
