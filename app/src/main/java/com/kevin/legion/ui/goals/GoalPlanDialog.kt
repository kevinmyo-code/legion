package com.kevin.legion.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.advisor.GoalPlan
import com.kevin.legion.advisor.GoalPlanAgent
import com.kevin.legion.advisor.GoalPlanResult
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * "Generate plan from a goal" (ticket 07, `goal-plans`) - [GoalPlanAgent] and its voice tools
 * (`generate_goal_plan`/`accept_goal_plan`) shipped with no screen at all (tickets 02/03), which
 * is exactly the gap ADR 0035 names as its clearest case: generating a plan is a capability
 * reachable only by voice, and it fails in exactly the place a plan actually gets made - sitting
 * down with a moment to think, not talking to a phone.
 *
 * **Both paths call the same functions, never a UI copy of the flow.** [generate] is
 * [GoalPlanAgent.generate], unchanged from what `generate_goal_plan`'s dispatch calls.
 * [acceptCurrentPlan] is [GoalPlanAgent.acceptWholePlan] - see that function's own doc comment for
 * why it is not a second implementation of what the voice path's separate `set_meal_target`/
 * `set_sleep_target`/`set_goal`/`accept_goal_plan` tool calls do, only a different (single, code-
 * driven rather than model-driven) caller of the exact same underlying writers.
 *
 * **Every failure the voice path words honestly, this dialog words the same way** - the message
 * shown for every non-success [GoalPlanResult] is read straight out of
 * [LiveToolbox.mapGoalPlanResult]'s own `"message"` field, the identical JSON `generate_goal_plan`
 * hands back to the model to speak. Two copies of "the key is rate-limited, try in a minute" is
 * exactly the kind of drift ADR 0035 warns a second implementation produces, even a copy that
 * merely repeats the words rather than the logic.
 *
 * **One consent for the whole plan (settled decision 14).** [PlanDialogState.Proposed] is the one
 * screen the user sees before accepting - there is no per-field "apply this one?" step, and tapping
 * ACCEPT is the one action that calls [acceptCurrentPlan] once, for everything the plan proposed.
 *
 * **The honesty line says once, at generation, that this is a starting point.** That line already
 * lives inside [GoalPlan.rationale] ([GoalPlanAgent.SYSTEM_INSTRUCTION]'s own HONESTY clause) - this
 * dialog does not add a second one, it just shows the rationale plainly where the model put it.
 */
@Composable
fun GoalPlanButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Text(
        "+ GENERATE PLAN",
        style = LegionType.stamp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable { open = true },
    )

    if (open) {
        GoalPlanDialog(onDismiss = { open = false })
    }
}

private sealed class PlanDialogState {
    object EnteringGoal : PlanDialogState()
    object Generating : PlanDialogState()
    data class Proposed(val plan: GoalPlan) : PlanDialogState()
    data class GenerateFailed(val message: String) : PlanDialogState()
    object Accepting : PlanDialogState()
    data class Accepted(val plan: GoalPlan) : PlanDialogState()
}

@Composable
private fun GoalPlanDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var goalText by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<PlanDialogState>(PlanDialogState.EnteringGoal) }
    val sem = LocalLegionSemantics.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate a plan") },
        text = {
            Column {
                when (val s = state) {
                    is PlanDialogState.EnteringGoal, is PlanDialogState.GenerateFailed -> {
                        OutlinedTextField(
                            value = goalText,
                            onValueChange = { goalText = it },
                            label = { Text("Goal, in your own words") },
                        )
                        Text(
                            "e.g. \"lose fat, gain muscle\" or \"I only have kettlebells\"",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (s is PlanDialogState.GenerateFailed) {
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    PlanDialogState.Generating, PlanDialogState.Accepting -> {
                        CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
                        Text(if (state is PlanDialogState.Generating) "Working on a plan..." else "Applying...")
                    }
                    is PlanDialogState.Proposed -> ProposedPlanBody(s.plan)
                    is PlanDialogState.Accepted -> AcceptedPlanBody(s.plan)
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is PlanDialogState.EnteringGoal, is PlanDialogState.GenerateFailed -> {
                    TextButton(
                        enabled = goalText.isNotBlank(),
                        onClick = {
                            val text = goalText.trim()
                            state = PlanDialogState.Generating
                            scope.launch {
                                state = when (val outcome = GoalPlanAgent().generate(context, text)) {
                                    is GoalPlanResult.Success -> PlanDialogState.Proposed(outcome.plan)
                                    else -> PlanDialogState.GenerateFailed(
                                        LiveToolbox.mapGoalPlanResult(outcome).optString("message"),
                                    )
                                }
                            }
                        },
                    ) { Text("Generate") }
                }
                is PlanDialogState.Proposed -> {
                    TextButton(onClick = {
                        state = PlanDialogState.Accepting
                        scope.launch {
                            val accepted = GoalPlanAgent().acceptWholePlan(context, s.plan)
                            state = PlanDialogState.Accepted(accepted)
                        }
                    }) { Text("Accept") }
                }
                PlanDialogState.Generating, PlanDialogState.Accepting -> {}
                is PlanDialogState.Accepted -> TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (state !is PlanDialogState.Accepted) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * The proposal: [GoalPlan.rationale] plainly (the one place the "starting point" honesty line
 * lives), then every field the plan actually proposed, then every refusal in its own words - never
 * silently dropped (CLAUDE.md §4 rule 5/settled decisions 9-10).
 */
@Composable
private fun ProposedPlanBody(plan: GoalPlan) {
    val sem = LocalLegionSemantics.current
    Column {
        Text(plan.rationale, style = MaterialTheme.typography.bodyMedium)
        plan.mealTarget?.let {
            Text(
                "Meal target: ${it.caloriesKcal} kcal / ${formatOneDecimal(it.proteinG)}g protein",
                style = LegionType.stamp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        plan.sleepTarget?.let {
            Text("Sleep target: ${formatOneDecimal(it.hours)}h", style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp))
        }
        plan.pendingWorkoutGoal?.let {
            Text("Workout: $it", style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp))
        }
        plan.goals.forEach { g ->
            Text("Goal: ${g.statement}", style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp))
        }
        plan.refusals.forEach { r ->
            Text(
                "Not proposed - $r",
                style = LegionType.stamp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "Tapping Accept applies everything above in one step.",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** After [GoalPlanAgent.acceptWholePlan] returns - [plan] is now the ACCEPTED copy, whose
 * [GoalPlan.workoutPlanMessage] (if a workout piece was proposed) already says, honestly, whether
 * that one piece landed or not - see [GoalPlanAgent.accept]'s own doc comment. */
@Composable
private fun AcceptedPlanBody(plan: GoalPlan) {
    Column {
        Text("Plan applied.", style = MaterialTheme.typography.bodyMedium)
        plan.mealTarget?.let { Text("Meal target set: ${it.caloriesKcal} kcal / ${formatOneDecimal(it.proteinG)}g protein", style = LegionType.stamp, modifier = Modifier.padding(top = 8.dp)) }
        plan.sleepTarget?.let { Text("Sleep target set: ${formatOneDecimal(it.hours)}h", style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp)) }
        plan.workoutPlanMessage?.let { Text(it, style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp)) }
        if (plan.goals.isNotEmpty()) {
            Text("${plan.goals.size} goal(s) set.", style = LegionType.stamp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun formatOneDecimal(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
