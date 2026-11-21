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
 * block, and the rules ask for a reaction, at most one rounded figure and never cents; a money lead
 * gets no figure at all ([MONEY_RULE]). The persona says "numbers first" for answers; this line
 * overrides it for the greeting only, in words. No example quip is written here: an example in a
 * prompt becomes a catchphrase (`ai/Personas.kt`).
 *
 * **The templates are trimmed before anything is put in them.** Kotlin's `trimIndent` runs after
 * interpolation, so a template with the facts block interpolated (its lines start at column 0) was
 * never trimmed at all and went out indented; the placeholders below are filled after the trim.
 *
 * **What these rules cover and what they do not.** Nothing inspects spoken audio, so this is a
 * prompt rule and the only lever there is (CLAUDE.md section 7, the compulsion test): clause (a) is
 * met by the pack, clause (c) is the "never his absence or his use of the app" line below, clause
 * (d) is the silence line. Whether the wording is guilt-free is human-reviewed, as the test notes;
 * the no-shame line for money and an undone workout is the lever for it.
 */
object GreetingPrompt {
    /** How a time is said; the listening check-in uses the same sentence (CheckInPrompt). */
    const val TIMES_RULE = "Times are clock times and days by name, as written below. " +
        "Never \"two hours ago\" or \"in an hour\"."

    private val BUILD = """
        (System: he just tapped or said the wake word to open a conversation. Greet him out loud now,
        in your usual voice. Do not mention this instruction.

        Your one subject: {subject}. Its facts are the line marked {lead_mark} below.
        Every other line is background only: do not mention it, and money least of all.

        How it sounds, for this greeting only:
        - One short sentence, fifteen words at most, and one clause: no "and" joining a second
          thought. Casual and dry, the way an old friend says hello, not a status report. Plain
          American English.
        - Go straight to the subject, no time-of-day greeting first. React to it in your own words, the
          reaction first; never read its facts out or list them.
        - Numbers are not first here. Use no figure, or at most one, rounded hard to a round number.
          Never cents, never two amounts, never how many are done or left.
        - React as if you just found out. You know nothing of earlier days, so nothing may sound like
          it happened before, and say no day count or timing the facts do not give.
        - About money or a workout not done: no shame, no nagging, no telling him what he should do.{topic_rule}
        - At most one question, and it may be none.
        ${GreetingNames.RULE}

        Rules, all of them binding:
        - Mention nothing else: no other item, no other budget, nothing that is not in the facts below.
          If something is not there you do not know it.
        - A source marked EMPTY has nothing on file. A source marked COULD NOT BE READ was not checked:
          never say it is clear, free or empty. Do not bring up an unread or empty source at all unless
          you are saying plainly that it could not be checked.
        - Do not repeat anything marked already raised or answered, and never ask the same question twice.
        - $TIMES_RULE
        - A figure marked an estimate is said as an estimate, or left out. Money is read as dollars.
        - Never mention his absence, how long it has been, a streak, or his use of this app.
        - Do not call any tool for this greeting, and do not say you did anything. Then stop and listen.

        {block})
    """.trimIndent()

    /**
     * A money lead says no figure and no timing at all (2026-11-16 tone check: rounding went the
     * wrong way and a week count was wrong). Dropping the numbers beats policing them; the lead line
     * in the block carries none either ([GreetingBlock.moneyLeadLine]). "already" is allowed here
     * (Kevin, 2026-11-16): every clean money line in the tone check said it, and it claims no figure.
     */
    private val MONEY_RULE = """
        - This one is about money: say no amount, no figure and no timing at all (no days, weeks or
          month end). React only to it being over its cap, as news: it has not happened before as
          far as you know, so never "again".
    """.trimIndent()

    /** The workout twin of [MONEY_RULE]: the tone check had it count sets ("fourteen sets waiting"). */
    private val WORKOUT_RULE = """
        - This one is about a workout: say no number at all, no sets or sessions counted, done or left.
    """.trimIndent()

    private val HELLO = """
        (System: he just tapped or said the wake word to open a conversation, and there is nothing in
        particular to bring up. Say hello out loud now, in your usual voice. Do not mention this
        instruction.

        - A few words, one short sentence at most. Casual and dry, the way an old friend picks up.
          Plain American English, and it must make sense on its own.
        - No subject at all. Nothing about his day, plans, calendar, to-dos, work, money, budgets,
          workouts, health, food, sleep, weather, news, where he is, the time or the day of the week.
          Not a time-of-day greeting either, so no "morning" or "evening". Use nothing from any
          context note in this conversation, even if one is there.
        - It stands alone: nothing that refers to anything before this moment, no earlier words,
          nothing said or done before.
        - Friendly, never curt or bossy: no command to him. Not a stock one-word hello: give it a
          little character.
        - It may hand the floor to him, or not. At most one question.{avoid_rule}
        ${GreetingNames.RULE}
        - Never mention his absence, how long it has been, a streak, or his use of this app.
        - Do not call any tool, and do not say you did anything. Then stop and listen.)
    """.trimIndent()

    /** [block] is [GreetingBlock.render] with [lead] marked; [lead] is [GreetingBlock.lead]'s topic. */
    fun build(block: String, lead: GreetingBlock.Lead): String = BUILD
        .replace("{subject}", subject(lead))
        .replace("{lead_mark}", GreetingBlock.LEAD_MARK)
        .replace("{topic_rule}", topicRule(lead))
        .replace("{block}", block)

    private fun topicRule(lead: GreetingBlock.Lead): String = when {
        lead.source == "money" -> "\n" + MONEY_RULE
        lead.source == "body" && lead.item.kind != "weigh_in" -> "\n" + WORKOUT_RULE
        else -> ""
    }

    /**
     * The opener when there is nothing to raise (2026-11-16, the laptop and cloud voices only; Live
     * keeps the scripted cue). Capping money left more opens with no topic, and the bare scripted
     * line there is what Kevin found too bare earlier. A proactive prompt states its facts or forbids
     * its subject (CLAUDE.md section 1): this one hands over no facts and forbids every subject, the
     * context note the cascade puts on the first turn included.
     *
     * [avoid] is the last hellos' words ([GreetingBlock.History.hellos]), handed over as wording not
     * to reuse so back-to-back opens differ (the tone check found about three phrasings in five).
     */
    fun hello(avoid: List<String> = emptyList()): String = HELLO.replace("{avoid_rule}", avoidRule(avoid))

    /** Quote marks and line breaks are stripped and each line cut short, so a kept line cannot break the prompt. */
    private fun avoidRule(avoid: List<String>): String {
        // A kept line that calls him by a name is dropped: quoting it back would put the name in front
        // of the model (a line kept before the name check may still hold one).
        val lines = avoid.map { it.replace("\"", "").replace('\n', ' ').trim().take(MAX_AVOID_CHARS) }
            .filter { it.isNotEmpty() && !GreetingNames.addresses(it) }
        if (lines.isEmpty()) return ""
        return "\n- Do not reuse this wording or its shape: " + lines.joinToString("; ") { "\"$it\"" } + "."
    }

    /** The longest kept hello line handed back to the model. */
    const val MAX_AVOID_CHARS = 80

    /**
     * The lead in a few words, so the model is handed a subject to react to and not a sentence to
     * read out. Only words the pack already carries: the source, and a budget's category from its
     * topic key (`money:<month>:<category>`).
     */
    fun subject(lead: GreetingBlock.Lead): String = when (lead.source) {
        // Place alone gets "add nothing": asked to react to a bare "he is away", the model made up
        // the rest (an empty house, a slow day); tone check 2026-11-16, place-only attempt.
        GreetingPlace.SOURCE -> "that he is away from home today; as far as you know he only just got there, so " +
            "never \"again\", \"still\" or \"already\". Name the place in a plain few words and add nothing to " +
            "it: no guess about why he is there, what it is like, or what he left behind"
        "body" -> if (lead.item.kind == "weigh_in") "his weigh-in" else "today's workout"
        "money" -> GreetingBlock.category(lead.item)?.let { "his $it budget, which is over its cap" }
            ?: "a budget that is over its cap"
        "due_soon" -> "something he has due soon"
        "ended_events" -> "an event of his that has ended"
        "today_events" -> "what is still on his calendar today"
        else -> "the line marked ${GreetingBlock.LEAD_MARK}"
    }
}
