package com.kevin.legion.grades

/** A scripted [GradesBackend]: set [coursesResult] / [needResult] per test; calls are recorded. */
class FakeGradesBackend : GradesBackend {
    var coursesResult: GradesOutcome<List<CourseGrade>> = GradesOutcome.Ok(emptyList())
    var needResult: GradesOutcome<NeedAnswer> = GradesOutcome.Ok(NeedAnswer("needed", "", false))
    val needCalls = mutableListOf<List<String?>>()

    override suspend fun courses() = coursesResult

    override suspend fun whatINeed(
        courseId: Long,
        target: String,
        assignment: String?,
        group: String?,
        othersAt: String?,
    ): GradesOutcome<NeedAnswer> {
        needCalls += listOf(courseId.toString(), target, assignment, group, othersAt)
        return needResult
    }

    companion object {
        fun assignment(
            id: Long,
            name: String = "Quiz $id",
            points: Double? = 20.0,
            score: Double? = null,
            graded: Boolean = score != null,
            excused: Boolean = false,
            missing: Boolean = false,
            late: Boolean = false,
        ) = AssignmentGrade(id, name, null, points, score, null, graded, excused, missing, late, true)

        fun course(
            id: Long,
            name: String = "Course $id",
            score: Double? = 91.5,
            grade: String? = "A-",
            assignments: List<AssignmentGrade> = emptyList(),
        ) = CourseGrade(
            id, name, null, score, grade,
            if (score == null && grade == null) "Canvas shows no total for this course." else "Canvas says $grade.",
            null, null, null, assignments,
        )
    }
}
