package com.kevin.legion.grades

import java.math.BigDecimal

/**
 * Canvas grades as the engine serves them (`server/grades/views.py`, daily-loops ticket 03). Every
 * figure is the engine's: this phone never works a grade out. Scores are held as the numbers the
 * engine sent and only ever formatted for display ([GradeWords.number]), never added or compared.
 */
data class AssignmentGrade(
    val id: Long,
    val name: String,
    val dueAtMs: Long?,
    val pointsPossible: Double?,
    val score: Double?,
    val grade: String?,
    val graded: Boolean,
    val excused: Boolean,
    val missing: Boolean,
    val late: Boolean,
    /** False when Canvas leaves it out of the grade. */
    val counts: Boolean,
)

data class CourseGrade(
    val courseId: Long,
    val course: String,
    val courseCode: String?,
    /** Canvas's own figure; null (with a null grade) when the course hides its totals. */
    val canvasCurrentScore: Double?,
    val canvasCurrentGrade: String?,
    /** Canvas's totals, or that it shows none, in the engine's words. */
    val canvasSays: String,
    /** Worked out on the engine from the scores on file; never Canvas's. */
    val workedOutPercent: Double?,
    val workedOutNote: String?,
    val readAtMs: Long?,
    val assignments: List<AssignmentGrade>,
) {
    /** A course that hides its totals has no score and no grade: unreadable, never zero. */
    val hidesTotal: Boolean get() = canvasCurrentScore == null && canvasCurrentGrade.isNullOrBlank()
}

/** The engine's answer to "what do I need": [words] is shown verbatim and is the whole answer. */
data class NeedAnswer(
    /** needed, guaranteed, impossible or nothing_left. */
    val status: String,
    val words: String,
    val estimate: Boolean,
)

/** The four ways an engine call can end, the [com.kevin.legion.watches.WatchOutcome] shape. */
sealed interface GradesOutcome<out T> {
    data class Ok<T>(val value: T) : GradesOutcome<T>
    data class Unreachable(val sentence: String) : GradesOutcome<Nothing>
    /** A 4xx: [sentence] is the engine's own words (403 recruiter, 404, 400 "Nothing was worked out"). */
    data class Refused(val sentence: String, val status: Int = 0) : GradesOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : GradesOutcome<Nothing>

    /** The sentence for any non-Ok outcome. */
    val failureSentence: String?
        get() = when (this) {
            is Ok -> null
            is Unreachable -> sentence
            is Refused -> sentence
            is Unconfirmed -> sentence
        }
}

/** The engine's `/api/grades/` routes, behind a seam so the screen's state is tested with a fake. */
interface GradesBackend {
    suspend fun courses(): GradesOutcome<List<CourseGrade>>

    suspend fun whatINeed(
        courseId: Long,
        target: String,
        assignment: String?,
        group: String?,
        othersAt: String?,
    ): GradesOutcome<NeedAnswer>
}

/** Words the screen says; kept out of the composables so a test reads them without a UI. */
object GradeWords {
    const val NO_TOTAL = "Canvas shows no total"
    const val NOT_GRADED = "not graded yet"
    const val NOT_ON_ENGINE =
        "Grades are not available yet: the engine has not been updated for them, so nothing about " +
            "grades was read."

    /** A figure as the engine sent it: no trailing zeros, no exponent, and no arithmetic. */
    fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** Canvas's current grade and score for a list row, or the hidden-total sentence. */
    fun headline(course: CourseGrade): String {
        val grade = course.canvasCurrentGrade?.takeIf { it.isNotBlank() }
        val score = course.canvasCurrentScore?.let { number(it) + "%" }
        return when {
            grade != null && score != null -> "$grade, $score"
            grade != null -> grade
            score != null -> score
            else -> NO_TOTAL
        }
    }

    /** One assignment's state in words: its score, or excused, or not graded yet, then missing and late. */
    fun assignmentLine(a: AssignmentGrade): String {
        val points = a.pointsPossible?.let { number(it) }
        val main = when {
            a.excused -> "excused"
            a.graded && a.score != null -> if (points != null) "${number(a.score)} / $points" else number(a.score)
            a.graded && !a.grade.isNullOrBlank() -> a.grade
            else -> NOT_GRADED + (points?.let { " ($it points)" } ?: "")
        }
        val flags = buildList {
            if (a.missing) add("missing")
            if (a.late) add("late")
            if (!a.counts) add("left out of the grade by Canvas")
        }
        return (listOf(main) + flags).joinToString(", ")
    }
}
