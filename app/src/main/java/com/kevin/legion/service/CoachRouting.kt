package com.kevin.legion.service

/**
 * Deterministic rails for the Body coach's voice routing (Kevin's first voice session with body
 * coach 2, A25, 2026-11-06). Every one of these was a mis-route a tool description alone did not
 * stop, so the check sits where the call lands, on the words the user said this turn
 * ([GeminiLiveSession.userTextThisTurn] holds the partial transcript before the tool call arrives).
 * Pure, so a JVM test pins each one.
 *
 * - "Okay, lock it in. Lock that in." reached `set_meal_target` with the estimate's figure, which
 *   made it the user's own target and bypassed the draft plan ([setMealTargetRefusal]).
 * - "How about my workouts?" and "What are the workout sessions like?" reached `ask_goals` and
 *   came back with an old goal ([isWorkoutQuestion]).
 * - "No, what's on DA?" (Day A) opened the agenda ([namesProgrammeDay]).
 */
object CoachRouting {

    // "lock it in", "lock that in", "lock in the plan", "lock this one in", "lock my plan in".
    private val LOCK = Regex(
        """\block(?:\s+(?:it|that|this|the|my|this\s+one|that\s+one|the\s+plan|my\s+plan|the\s+draft))?\s+in\b""" +
            """|\block\s+(?:it|that|the\s+plan|my\s+plan)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [text] asks to lock something in. */
    fun saysLockIn(text: String?): Boolean = text != null && LOCK.containsMatchIn(text)

    /**
     * Why `set_meal_target` must not run, or null when it may. "Lock it in" is the plan's lock;
     * `set_meal_target` is only for a number the user says as their own, so a lock phrase with no
     * such number in the same words is refused, naming the right call. "Lock in 2000 calories"
     * still sets 2000: the user said the number.
     */
    fun setMealTargetRefusal(userText: String?, calories: Int?): String? {
        val ownNumber = calories != null && saysNumber(userText.orEmpty(), calories)
        if (!saysLockIn(userText) || ownNumber) return null
        return "Nothing was set. \"Lock it in\" locks the draft plan: call ask_engine with intent " +
            "\"record\" to lock_body_plan; it writes the plan's target. set_meal_target is only for " +
            "a calorie figure the user says as their own."
    }

    /** "1808", "1,808" or "1 808" in [text]. */
    internal fun saysNumber(text: String, value: Int): Boolean {
        val digits = Regex("""\d[\d,\s]*\d|\d""").findAll(text).map { it.value.filter(Char::isDigit) }
        return digits.any { it == value.toString() }
    }

    // A workout word, as a word: "workouts", "the sessions", "my programme", "Day A".
    private val WORKOUT = Regex(
        """\b(?:work\s?outs?|sessions?|programmes?|programs?|training|exercises?|lifts?|lifting""" +
            """|sets|reps|routine|gym)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val GOAL = Regex("""\bgoals?\b""", RegexOption.IGNORE_CASE)

    /**
     * True when a question sent to `ask_goals` is about the training programme, not a goal: it
     * names a workout word or a programme day and never the word "goal". Such a question is run
     * by the body route (`get_todays_workout`, `get_workout_programme`) instead.
     */
    fun isWorkoutQuestion(question: String?): Boolean {
        if (question.isNullOrBlank() || GOAL.containsMatchIn(question)) return false
        return WORKOUT.containsMatchIn(question) || namesProgrammeDay(question)
    }

    // "Day A", "day-B", "day eh", "day bee"; not "one day a week".
    private val DAY_WORD = Regex(
        """\bday[\s-]?(?:a|b|eh|ay|bee)\b(?!\s+(?:week|month|year|day|time|few|lot|bit|couple|little|while|half)\b)""",
        RegexOption.IGNORE_CASE,
    )

    // "DA", "DB", "D A", "D-B": the letters as speech recognition writes them, upper case only, so
    // the Spanish or Portuguese "da" is not a workout day.
    private val DAY_LETTERS = Regex("""\bD[\s.-]?[AB]\b""")

    /** True when [text] names a programme day: Day A / Day B / DA / DB / "day eh". */
    fun namesProgrammeDay(text: String?): Boolean =
        text != null && (DAY_WORD.containsMatchIn(text) || DAY_LETTERS.containsMatchIn(text))

    /** Engine tools whose success writes today's meal target on the engine. */
    val TARGET_WRITES = setOf("lock_body_plan", "accept_energy_proposal")

    /** True when an `ask_engine` tool call just wrote the meal target, so the phone pulls its copy. */
    fun movesTheMealTarget(answer: EngineToolAnswer): Boolean = !answer.isError && answer.tool in TARGET_WRITES

    private val AGENDA = Regex(
        """\b(?:agenda|reminders?|appointments?|calendar|schedule|due)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why `show_agenda_modal` must not open, or null when it may: the user named a programme day
     * ("what's on DA?") and nothing agenda-shaped.
     */
    fun agendaRefusal(userText: String?): String? {
        if (!namesProgrammeDay(userText) || AGENDA.containsMatchIn(userText.orEmpty())) return null
        return "Not opened. \"Day A\" or \"Day B\" (\"DA\", \"DB\") is a day of the workout " +
            "programme, not the agenda: call ask_body for the programme (get_workout_programme)."
    }
}
