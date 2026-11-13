package com.kevin.legion.backend.engine

import com.kevin.legion.grades.AssignmentGrade
import com.kevin.legion.grades.CourseGrade
import com.kevin.legion.grades.GradeWords
import com.kevin.legion.grades.GradesBackend
import com.kevin.legion.grades.GradesOutcome
import com.kevin.legion.grades.NeedAnswer
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val GRADES_PATH = "/api/grades/"
private const val HTTP_FORBIDDEN = 403

private val gradesJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private fun parseMs(stamp: String): Long = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

/** `AssignmentSerializer` in `server/grades/views.py`. */
@Serializable
private data class AssignmentRow(
    val id: Long,
    val name: String,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("points_possible") val pointsPossible: Double? = null,
    val score: Double? = null,
    val grade: String? = null,
    val graded: Boolean = false,
    val excused: Boolean = false,
    val missing: Boolean = false,
    val late: Boolean = false,
    val counts: Boolean = true,
) {
    fun toModel() = AssignmentGrade(
        id, name, dueAt?.let(::parseMs), pointsPossible, score, grade, graded, excused, missing, late, counts,
    )
}

/** `CourseGradeSerializer` in `server/grades/views.py`. */
@Serializable
private data class CourseRow(
    @SerialName("canvas_course_id") val courseId: Long,
    val course: String,
    @SerialName("course_code") val courseCode: String? = null,
    @SerialName("canvas_current_score") val currentScore: Double? = null,
    @SerialName("canvas_current_grade") val currentGrade: String? = null,
    @SerialName("canvas_says") val says: String = "",
    @SerialName("current_worked_out_percent") val workedOut: Double? = null,
    @SerialName("worked_out_note") val workedOutNote: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    val assignments: List<AssignmentRow> = emptyList(),
) {
    fun toModel() = CourseGrade(
        courseId, course, courseCode, currentScore, currentGrade, says, workedOut, workedOutNote,
        readAt?.let(::parseMs), assignments.map { it.toModel() },
    )
}

@Serializable
private data class CourseListBody(val results: List<CourseRow> = emptyList())

/** `NeedSerializer`: only the fields the screen shows; [words] is the engine's whole answer. */
@Serializable
private data class NeedBody(val status: String, val words: String, val estimate: Boolean = false)

/**
 * [GradesBackend] over `/api/grades/` with the device token [EngineHttp] attaches. Unreachable stays
 * unreachable, a 4xx is the engine's own sentence, a 2xx that does not decode is unconfirmed.
 */
class DjangoGradesBackend(private val http: EngineHttp) : GradesBackend {

    override suspend fun courses(): GradesOutcome<List<CourseGrade>> =
        gradesOutcome(http.get(GRADES_PATH)) { body ->
            gradesJson.decodeFromString(CourseListBody.serializer(), body).results.map { it.toModel() }
        }

    override suspend fun whatINeed(
        courseId: Long,
        target: String,
        assignment: String?,
        group: String?,
        othersAt: String?,
    ): GradesOutcome<NeedAnswer> {
        val query = buildMap {
            put("target", target.trim())
            assignment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("assignment", it) }
            group?.trim()?.takeIf { it.isNotEmpty() }?.let { put("group", it) }
            othersAt?.trim()?.takeIf { it.isNotEmpty() }?.let { put("others_at", it) }
        }
        return gradesOutcome(http.get("$GRADES_PATH$courseId/what-i-need", query)) { body ->
            val need = gradesJson.decodeFromString(NeedBody.serializer(), body)
            NeedAnswer(need.status, need.words, need.estimate)
        }
    }
}

private fun <T> gradesOutcome(result: Result<EngineOk>, decode: (String) -> T): GradesOutcome<T> {
    val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
    return when {
        // An engine from before grades: said in words, never its HTML page.
        engineRouteMissing(result) -> GradesOutcome.Refused(GradeWords.NOT_ON_ENGINE, HTTP_NOT_FOUND)
        result.isSuccess -> try {
            GradesOutcome.Ok(decode(result.getOrThrow().body))
        } catch (e: SerializationException) {
            GradesOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        } catch (e: DateTimeParseException) {
            GradesOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        }
        failure is EngineFailure.Unreachable -> GradesOutcome.Unreachable(failure.message)
        failure is EngineFailure.Unauthorized -> GradesOutcome.Refused(
            failure.body.takeIf { failure.status == HTTP_FORBIDDEN && it.isNotBlank() }
                ?.let(::engineRefusalSentence) ?: failure.sentence,
            failure.status,
        )
        failure is EngineFailure.Refused -> GradesOutcome.Refused(engineRefusalSentence(failure.body), failure.status)
        failure is EngineFailure.Malformed -> GradesOutcome.Unconfirmed(failure.sentence)
        else -> GradesOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
    }
}
