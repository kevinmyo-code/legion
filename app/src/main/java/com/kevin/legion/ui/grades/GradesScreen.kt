package com.kevin.legion.ui.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.backend.engine.DjangoGradesBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.grades.AssignmentGrade
import com.kevin.legion.grades.CourseGrade
import com.kevin.legion.grades.GradeWords
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * `grades` (daily-loops ticket 03): the hands path for the `canvas_grades` and `what_i_need` engine
 * tools (ADR 0035). Courses as ruled rows with Canvas's own grade; a course opens to its
 * assignments and a "What do I need?" form. Figures and sentences are the engine's; a failed read
 * says what did not load and when the data shown was read.
 */
@Composable
fun GradesScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel: GradesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { GradesViewModel(DjangoGradesBackend(EngineHttp(EngineConfig(context)))) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    SoftTheme {
        GradesContent(
            state = state,
            callbacks = GradesCallbacks(
                onBack = onBack,
                onRetry = viewModel::refresh,
                onSelect = viewModel::select,
                onTarget = viewModel::setTarget,
                onAssignment = viewModel::setAssignment,
                onGroup = viewModel::setGroup,
                onOthersAt = viewModel::setOthersAt,
                onAsk = viewModel::askNeed,
            ),
        )
    }
}

data class GradesCallbacks(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSelect: (Long?) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onAssignment: (String) -> Unit = {},
    val onGroup: (String) -> Unit = {},
    val onOthersAt: (String) -> Unit = {},
    val onAsk: () -> Unit = {},
)

/** Plain UI: state in, callbacks out. */
@Composable
fun GradesContent(state: GradesUiState, callbacks: GradesCallbacks) {
    val course = state.selected
    // Back from a course returns to the list; back from the list leaves the screen.
    BackHandler(enabled = course != null) { callbacks.onSelect(null) }
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        DeckScreenHeader(
            title = "Grades",
            onBack = { if (course != null) callbacks.onSelect(null) else callbacks.onBack() },
        )
        state.error?.let { message ->
            Line(message, Modifier.testTag("grades-error"), strong = true)
            TextButton(onClick = callbacks.onRetry) { Text("Try again") }
        }
        state.lastReadAt?.let { Line("Read at $it.", Modifier.testTag("grades-read-at")) }
        val courses = state.courses
        when {
            courses == null && state.loading -> Line("Reading your grades from the engine...")
            courses == null -> Unit // the error above is the whole answer; never an empty list for it
            course != null -> CourseDetail(course, state, callbacks)
            courses.isEmpty() -> Line("The engine has no Canvas courses on file for you.")
            else -> CourseList(courses, callbacks)
        }
    }
}

@Composable
private fun CourseList(courses: List<CourseGrade>, callbacks: GradesCallbacks) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(courses, key = { it.courseId }) { course ->
            Column(
                Modifier.fillMaxWidth()
                    .clickable { callbacks.onSelect(course.courseId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("grades-course-${course.courseId}"),
            ) {
                Text(course.course, color = SoftColors.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    GradeWords.headline(course),
                    color = SoftColors.text2,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (course.hidesTotal) Modifier.testTag("grades-no-total") else Modifier,
                )
            }
            HorizontalDivider(color = SoftColors.outline)
        }
    }
}

@Composable
private fun CourseDetail(course: CourseGrade, state: GradesUiState, callbacks: GradesCallbacks) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                course.course,
                style = MaterialTheme.typography.titleMedium,
                color = SoftColors.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Line(GradeWords.headline(course))
            if (course.canvasSays.isNotBlank()) Line(course.canvasSays)
            course.workedOutPercent?.let {
                Line("Worked out from the scores on file, not Canvas's: ${GradeWords.number(it)}%.")
            }
            course.workedOutNote?.let { Line(it) }
        }
        item { NeedForm(state, callbacks) }
        item { Section("Assignments") }
        if (course.assignments.isEmpty()) item { Line("No assignments are on file for this course.") }
        items(course.assignments, key = { it.id }) { AssignmentRow(it) }
    }
}

@Composable
private fun NeedForm(state: GradesUiState, callbacks: GradesCallbacks) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Section("What do I need?", Modifier)
        Field(state.needTarget, callbacks.onTarget, "Target: a letter (B) or a percent (85)")
        Field(state.needAssignment, callbacks.onAssignment, "One assignment (optional)")
        Field(state.needGroup, callbacks.onGroup, "One assignment group (optional)")
        Field(state.needOthersAt, callbacks.onOthersAt, "Assume other ungraded work scores this percent (optional)")
        TextButton(onClick = callbacks.onAsk, enabled = !state.needBusy) { Text("Work it out") }
        when (val need = state.need) {
            is NeedResult.Answer -> Text(
                need.words,
                color = SoftColors.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("grades-need-answer"),
            )
            is NeedResult.Failed -> Text(
                need.sentence,
                color = SoftColors.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("grades-need-failed"),
            )
            null -> if (state.needBusy) Line("Asking the engine...", Modifier)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AssignmentRow(a: AssignmentGrade) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("grades-assignment-${a.id}")) {
        Text(a.name, color = SoftColors.text, style = MaterialTheme.typography.bodyLarge)
        Text(GradeWords.assignmentLine(a), color = SoftColors.text2, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(color = SoftColors.outline)
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier.padding(start = 16.dp)) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = SoftColors.text,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (strong) SoftColors.text else SoftColors.text2,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
