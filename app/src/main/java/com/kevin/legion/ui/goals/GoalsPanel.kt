package com.kevin.legion.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.goals.GoalController
import com.kevin.legion.goals.GoalProgress
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The GOALS panel (ticket 19,
 * `.scratch/aspect-advisors/issues/19-build-goal-tools-and-panel.md`) - one per aspect surface
 * ([com.kevin.legion.ui.BodyScreen] `aspect = "bio"`, [com.kevin.legion.ui.NotesScreen]
 * `aspect = "log"`, [com.kevin.legion.ui.LedgerScreen] `aspect = "cred"`,
 * [com.kevin.legion.ui.FleetScreen] `aspect = "fleet"`), all four reading and writing through
 * [GoalController] so a goal set here and a goal set by the `set_goal` Live tool can never drift.
 *
 * **Deliberately self-contained**, unlike every other panel on these four screens. The rest of
 * each screen follows the "one state holder in the top-level `@Composable`, everything else plain
 * UI" split (`compose-state-holder-ui-split`, cited on [com.kevin.legion.ui.BodyScreen]'s own doc
 * comment) - but goals are not part of any of those screens' own batched `LaunchedEffect(Unit)`
 * loads, and a save/close here has to re-read immediately rather than wait for that screen's next
 * unrelated reload. Carrying its own `LocalContext`/`LaunchedEffect`/coroutine scope keeps every
 * call site to one line (`GoalsPanel(aspect = "...")`) with zero coupling to that screen's own
 * `UiState` class, at the cost of being the one panel on each screen that manages its own load.
 *
 * **Read-AND-edit, unlike the rest of these four screens.** [com.kevin.legion.ui.BodyScreen]'s own
 * doc comment states its posture as "read-only... voice is how [things] get WRITTEN" - goals are
 * the deliberate exception ticket 19 calls for (`.scratch/aspect-advisors/issues/02-goal-store.md`
 * answer call 5: "voice tools plus a GOALS panel... matches how targets already work, set by voice
 * AND screen").
 *
 * **The revision trail is never rendered here** - this ticket's own open design question,
 * answered: [Goal]'s `lineageId`/`supersedesId` trail exists for the ADVISOR's digest (a goal that
 * quietly got easier over several revisions is exactly what makes that trail worth reading -
 * `.scratch/aspect-advisors/issues/02-goal-store.md` answer call 4), which is a coaching audience
 * that benefits from seeing every step. A human looking at their OWN goal already knows its
 * history; re-showing every superseded row on this panel is the noise the ticket's own question
 * warns about, and this panel's only reads ([GoalController.currentGoals]) never touch
 * [com.kevin.legion.data.local.GoalDao.history] at all. If a "how has this goal changed" affordance
 * is ever wanted it is a new, separate build, not a default state of this one.
 *
 * **Prose vs. measurable, told apart WITHOUT colour** (this ticket's other open question): every
 * row carries a [DeckTag] reading the literal word `TARGET` or `PROSE` - both [DeckTagStyle.OUTLINE_MUTED],
 * the SAME style/colour, on purpose. The distinction lives entirely in the word, matching CLAUDE.md
 * §4 rule 5's "say it in words" posture extended from estimates to this shape too - a colour-blind
 * reading of this row loses nothing.
 */
@Composable
fun GoalsPanel(aspect: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var goals by remember(aspect) { mutableStateOf<List<Goal>>(emptyList()) }
    // quant-viz ticket 08: one resolved current-value/fraction per goal id, keyed alongside [goals]
    // rather than resolved inline in [GoalRow] - a goal with an unresolvable/unknown metricKey maps
    // to no entry at all (see [resolveMetric]'s doc comment), which [GoalRow] reads as "fall through
    // to prose", never a guessed figure.
    var progressByGoal by remember(aspect) { mutableStateOf<Map<Long, MetricResolution>>(emptyMap()) }
    var loaded by remember(aspect) { mutableStateOf(false) }
    // Bumped after every write so the LaunchedEffect below re-reads without this panel needing
    // its own duplicate reload function at every call site.
    var refreshToken by remember(aspect) { mutableStateOf(0) }

    LaunchedEffect(aspect, refreshToken) {
        val loadedGoals = GoalController.currentGoals(context, aspect)
        goals = loadedGoals
        progressByGoal = loadedGoals.mapNotNull { g -> resolveMetric(context, g)?.let { g.id to it } }.toMap()
        loaded = true
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }
    val sem = LocalLegionSemantics.current

    DeckPane(
        header = "Goals",
        headerAccent = if (goals.isNotEmpty()) "${goals.size} ACTIVE" else null,
        modifier = modifier,
    ) {
        if (loaded && goals.isEmpty()) {
            GapEmptyRow(label = "No goals set", message = "Say \"set a goal\" or add one below.")
        } else {
            goals.forEach { goal ->
                GoalRow(goal = goal, resolution = progressByGoal[goal.id], modifier = Modifier.clickable { editingGoal = goal })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            // GENERATE PLAN is BIO-only (ticket 07) - GoalPlanAgent itself refuses every other
            // aspect (settled decision 12, "goals may only carry aspect = bio for now"), so
            // offering the button on cred/log/fleet would open a dialog that can only ever fail.
            if (aspect == "bio") {
                GoalPlanButton(modifier = Modifier.padding(end = 16.dp))
            }
            Text(
                "+ ADD GOAL",
                style = LegionType.stamp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showAddDialog = true },
            )
        }
    }

    if (showAddDialog) {
        GoalEditDialog(
            aspect = aspect,
            existing = null,
            onDismiss = { showAddDialog = false },
            onSaved = {
                showAddDialog = false
                refreshToken++
            },
        )
    }

    editingGoal?.let { goal ->
        GoalEditDialog(
            aspect = aspect,
            existing = goal,
            onDismiss = { editingGoal = null },
            onSaved = {
                editingGoal = null
                refreshToken++
            },
            onClosed = {
                editingGoal = null
                refreshToken++
            },
        )
    }
}

/**
 * One goal's resolved current value (quant-viz ticket 08), keyed off [Goal.metricKey] - see
 * [resolveMetric]'s doc comment for exactly which keys resolve and why an unknown one resolves to
 * no [MetricResolution] at all rather than a guess.
 *
 * [Accumulation] (`savings_balance_cents`) is the ONLY variant [GoalRow] puts a [DeckMeter] under -
 * a fill fraction toward a ceiling is truthful for money accumulating toward a target.
 * [Reading] (`bodyweight_kg`) is words-only: with no recorded baseline on [Goal], a loss goal's
 * "fraction complete" is not a number this app can compute honestly, and a meter that fills as
 * weight RISES would celebrate the wrong direction (CLAUDE.md §4's "say what you don't know"
 * posture, read onto direction rather than provenance).
 */
private sealed class MetricResolution {
    /** [currentCents] backs the words line's exact Long-cents label; [fraction] backs the meter (null only when [Goal.targetValue] itself was null/non-positive - see [GoalProgress.accumulationProgress]). */
    data class Accumulation(val currentCents: Long, val fraction: Float?) : MetricResolution()
    data class Reading(val currentValue: Double, val unit: String) : MetricResolution()
}

/**
 * Resolves [goal]'s current value the SAME way [com.kevin.legion.advisor.digest.CredDigestBuilder]
 * does for `savings_balance_cents` - both read [GoalProgress.savingsBalanceCents], never a second,
 * possibly-drifted query (ticket 08 spec: "reusing the exact reads CredDigestBuilder... already
 * performs"). `bodyweight_kg` reads [com.kevin.legion.data.local.BodyweightLogDao.mostRecent]
 * directly - CredDigestBuilder has no bodyweight reasoning to reuse (that lives in
 * `BioDigestBuilder`, a different file this ticket does not touch).
 *
 * Returns `null` for a goal with no [Goal.metricKey], no [Goal.targetValue], an unresolvable key
 * (nothing yet logged for it), or a key this app does not know how to read - EVERY one of those
 * falls through to [GoalRow]'s plain prose rendering, never a guessed figure (ticket 08 spec: "any
 * other key present in the DB but not resolvable -> fall through to prose... never guess").
 */
private suspend fun resolveMetric(context: Context, goal: Goal): MetricResolution? {
    if (goal.metricKey == null || goal.targetValue == null) return null
    val db = CarDatabase.getDatabase(context)
    return when (goal.metricKey) {
        "savings_balance_cents" -> GoalProgress.savingsBalanceCents(context)?.let { (cents, _) ->
            // Fraction geometry only - the printed words line below always uses formatCents on the
            // Long [cents] itself, never this Double division (CLAUDE.md §4 rule three).
            MetricResolution.Accumulation(currentCents = cents, fraction = GoalProgress.accumulationProgress(cents / 100.0, goal.targetValue))
        }
        "bodyweight_kg" -> db.bodyweightLogDao().mostRecent()?.let { log ->
            MetricResolution.Reading(currentValue = log.weightValue, unit = log.weightUnit)
        }
        else -> null
    }
}

/**
 * One goal, one row. [DeckRow.value] carries the number (when there is one) plus a deadline
 * suffix; [DeckRow.tag] carries the PROSE/TARGET word - see [GoalsPanel]'s doc comment for why
 * that is a word, never a colour.
 *
 * [resolution] (quant-viz ticket 08), when non-null, adds a `NOW <current> -> TARGET <target>
 * <unit>` words line beneath the row - see [MetricResolution]'s own doc comment for which variant
 * additionally draws a [DeckMeter] and why the other does not.
 */
@Composable
private fun GoalRow(goal: Goal, resolution: MetricResolution? = null, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val measurable = goal.targetValue != null
    val valueText = buildString {
        if (measurable) {
            append(formatGoalNumber(goal.targetValue!!))
            goal.unit?.let { append(" ").append(it.uppercase()) }
        } else {
            append("—") // em dash: a prose goal has no figure to show, and this says so.
        }
        goal.deadlineEpoch?.let { append(" · BY ").append(formatGoalDeadline(it)) }
    }
    Column(modifier.fillMaxWidth()) {
        DeckRow(
            label = goal.statement,
            value = valueText,
            tag = { DeckTag(if (measurable) "TARGET" else "PROSE", DeckTagStyle.OUTLINE_MUTED) },
        )
        if (resolution != null) {
            val nowText = when (resolution) {
                is MetricResolution.Accumulation -> formatCents(resolution.currentCents)
                is MetricResolution.Reading -> "${formatGoalNumber(resolution.currentValue)} ${resolution.unit.uppercase()}"
            }
            val targetText = buildString {
                append(formatGoalNumber(goal.targetValue!!))
                goal.unit?.let { append(" ").append(it.uppercase()) }
            }
            Text(
                "NOW $nowText -> TARGET $targetText",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (resolution is MetricResolution.Accumulation && resolution.fraction != null) {
                DeckMeter(fraction = resolution.fraction, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

/** `175.0` reads worse than `175`; a goal's target is as likely to be a whole number as a decimal. */
private fun formatGoalNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private val GOAL_DEADLINE_DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
private val GOAL_DEADLINE_EDIT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")

private fun formatGoalDeadline(epochMs: Long): String =
    LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(GOAL_DEADLINE_DISPLAY)

/**
 * Add (when [existing] is null) or edit (when it isn't) one goal. Plain M3 [AlertDialog] +
 * [OutlinedTextField] rather than any MILSPEC-bespoke chrome - same posture as
 * [com.kevin.legion.ui.notes.ItemEditDialog], which the same file's own doc comment settles as
 * "simple first": typed text fields for the optional target number/unit/deadline, no bespoke
 * number stepper or date-picker dialog host. The fields still render in MILSPEC colours because
 * [MaterialTheme.colorScheme] is global, not because this dialog reaches for a deck-styled input.
 *
 * `metric_key` is intentionally NOT editable here. It is a code-meaningful key
 * ([Goal]'s doc comment: `bodyweight_kg`, `savings_balance_cents`, ...) that deterministic digest
 * code matches against - a typo typed into this dialog would silently stop a goal's progress math
 * without any error, and there is no validation surface here to catch it. `set_goal`'s voice path
 * is the only writer of `metricKey`, matching CLAUDE.md's llm-vs-app-computed split in spirit even
 * though a metric key itself is neither an LLM guess nor gated data.
 */
@Composable
private fun GoalEditDialog(
    aspect: String,
    existing: Goal?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onClosed: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statement by remember(existing?.id) { mutableStateOf(existing?.statement.orEmpty()) }
    var targetText by remember(existing?.id) { mutableStateOf(existing?.targetValue?.let { formatGoalNumber(it) }.orEmpty()) }
    var unit by remember(existing?.id) { mutableStateOf(existing?.unit.orEmpty()) }
    var deadlineText by remember(existing?.id) {
        mutableStateOf(existing?.deadlineEpoch?.let { formatGoalDeadline(it) }.orEmpty())
    }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }

    val parsedTarget = targetText.trim().let { if (it.isBlank()) null else it.toDoubleOrNull() }
    val targetParseFailed = targetText.isNotBlank() && parsedTarget == null
    val parsedDeadline = deadlineText.trim().let {
        if (it.isBlank()) null else runCatching { LocalDate.parse(it, GOAL_DEADLINE_EDIT) }.getOrNull()
    }
    val deadlineParseFailed = deadlineText.isNotBlank() && parsedDeadline == null
    val canSave = statement.isNotBlank() && !targetParseFailed && !deadlineParseFailed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add goal" else "Edit goal") },
        text = {
            Column {
                OutlinedTextField(value = statement, onValueChange = { statement = it }, label = { Text("Statement") })
                Text(
                    "Required. A number is optional - most goals are prose only.",
                    style = LegionType.stamp,
                    color = LocalLegionSemantics.current.faint,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Target number (optional)") },
                    isError = targetParseFailed,
                )
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit (optional)") })
                OutlinedTextField(
                    value = deadlineText,
                    onValueChange = { deadlineText = it },
                    label = { Text("Deadline MM/DD/YYYY (optional)") },
                    isError = deadlineParseFailed,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                scope.launch {
                    val outcome = GoalController.setGoal(
                        context = context,
                        aspect = aspect,
                        statement = statement.trim(),
                        targetValue = parsedTarget,
                        unit = unit.trim().ifBlank { null },
                        metricKey = existing?.metricKey, // carried forward untouched - see doc comment above
                        deadlineEpoch = parsedDeadline?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                        revises = existing,
                    )
                    when (outcome) {
                        is GoalController.SetOutcome.Created, is GoalController.SetOutcome.Revised, is GoalController.SetOutcome.Unchanged -> onSaved()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null && onClosed != null) {
                    TextButton(onClick = {
                        scope.launch {
                            GoalController.closeByLineage(context, existing.lineageId, "achieved")
                            onClosed()
                        }
                    }) { Text("Achieved") }
                    TextButton(onClick = {
                        scope.launch {
                            GoalController.closeByLineage(context, existing.lineageId, "abandoned")
                            onClosed()
                        }
                    }) { Text("Abandon") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ------------------------------------------------------------------------- previews
//
// L11 gate (CLAUDE.md §8): render before building further on this. [GoalRow] is plain
// data-in/nothing-touched - no Context, no CarDatabase, no coroutine - so it previews the same way
// ThemePreview.kt's demos do, without a LocalInspectionMode guard (Midnight AI's L1 lesson).
// [GoalsPanel] itself is NOT previewed here: it owns LocalContext/CarDatabase reads the moment it
// composes, the same reason ThemePreview.kt's own doc comment gives for staying at the primitive
// level rather than a real screen. **Rendering these previews was not performed in this execution
// environment** (no Compose preview renderer available here, same gap ThemePreview.kt's doc
// comment names as "deferred to the on-device ship pass") - carried forward explicitly per L11,
// not silently. Written and reviewed by eye against the DeckRow/DeckTag contract; on-device
// confirmation is an open follow-up, not assumed done.

@Composable
private fun GoalsPanelPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            DeckPane(header = "Goals", headerAccent = "3 ACTIVE") {
                GoalRow(Goal(lineageId = 1, aspect = "bio", statement = "ship the deck"))
                GoalRow(
                    Goal(
                        lineageId = 2, aspect = "cred", statement = "save 30k by 2028",
                        targetValue = 30000.0, unit = "usd",
                        deadlineEpoch = LocalDate.of(2028, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    ),
                )
                GoalRow(Goal(lineageId = 3, aspect = "bio", statement = "get to 175 lbs", targetValue = 175.0, unit = "lbs"))
            }
        }
    }
}

@Composable
private fun GoalsPanelEmptyPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckPane(header = "Goals") {
            GapEmptyRow(label = "No goals set", message = "Say \"set a goal\" or add one below.")
        }
    }
}

@Preview(name = "GOALS panel - prose + measurable + deadline", showBackground = true)
@Composable
private fun PreviewGoalsPanelMixed() {
    LegionTheme { GoalsPanelPreviewContent() }
}

@Preview(name = "GOALS panel - empty state", showBackground = true)
@Composable
private fun PreviewGoalsPanelEmpty() {
    LegionTheme { GoalsPanelEmptyPreviewContent() }
}

/** The Oppo A17K's narrow-width case, same one ThemePreview.kt checks. */
@Preview(name = "GOALS panel - 320dp narrow", widthDp = 320, showBackground = true)
@Composable
private fun PreviewGoalsPanelNarrow() {
    LegionTheme { GoalsPanelPreviewContent() }
}
