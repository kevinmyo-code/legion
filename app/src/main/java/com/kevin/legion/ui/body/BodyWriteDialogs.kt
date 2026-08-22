package com.kevin.legion.ui.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.meals.MealController
import com.kevin.legion.sleep.SleepController
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.workouts.WorkoutController
import kotlinx.coroutines.launch

/**
 * Ticket 03 (`.scratch/command-center/issues/03-body-writes-by-hand.md`), ADR 0035: the hands
 * path for every write [BodyScreen][com.kevin.legion.ui.BodyScreen] could previously only take by
 * voice. Every dialog below calls the EXACT SAME controller function `LiveToolbox`'s matching
 * voice tool dispatches to - traced from `service/LiveToolbox.kt` before writing this file:
 *
 * - `log_meal` -> [MealController.logMeal] -> [LogMealDialog]
 * - `set_meal_target` -> [MealController.setTarget] -> [SetMealTargetDialog]
 * - `log_sleep` -> [SleepController.logSleep] -> [LogSleepDialog]
 * - `set_sleep_target` -> [SleepController.setTarget] -> [SetSleepTargetDialog]
 * - `log_bodyweight` -> [WorkoutController.logBodyweight] -> [LogBodyweightDialog]
 * - `log_workout_set` -> [WorkoutController.logSet] -> [LogWorkoutSetDialog]
 *
 * None of these re-implements what the controller does - same "one path, not a UI copy" posture
 * [com.kevin.legion.ui.goals.GoalPlanDialog]'s own doc comment states for the goal-plan flow. Each
 * dialog owns its own tiny bit of local UI state (the text fields, `Composing`/`Failed`) and calls
 * `onDone` once the write has actually landed, which the caller uses to bump `BodyScreen`'s reload
 * key - never invented in this file, since [BodyScreen][com.kevin.legion.ui.BodyScreen] is still the
 * one state holder for what gets re-read from Room afterward.
 *
 * **No score, no streak, no percentage anywhere in this file** (CLAUDE.md sec 7). Every macro
 * field a controller can only estimate is spoken by the controller itself as an estimate
 * (`MealController.logMeal`'s own return string already says "(estimate)") - nothing here adds a
 * second estimate on top of it.
 */

// ---------------------------------------------------------------------- meal

@Composable
fun LogMealDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a meal") },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What did you eat") },
                    enabled = !busy,
                )
                Text(
                    "e.g. \"chicken burrito bowl\" - macros come back as an estimate, same as the voice path.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && description.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call log_meal's dispatch makes: MealController.logMeal(context, description).
                            result = MealController.logMeal(context, description.trim())
                            busy = false
                        }
                    },
                ) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SetMealTargetDialog(
    currentCalories: Int?,
    currentProteinG: Double?,
    currentCarbsG: Double?,
    currentFatG: Double?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var calories by remember { mutableStateOf(currentCalories?.toString() ?: "") }
    var protein by remember { mutableStateOf(currentProteinG?.let(::formatPlain) ?: "") }
    var carbs by remember { mutableStateOf(currentCarbsG?.let(::formatPlain) ?: "") }
    var fat by remember { mutableStateOf(currentFatG?.let(::formatPlain) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current
    val parsedCalories = calories.toIntOrNull()
    val parsedProtein = protein.toDoubleOrNull()
    val parsedCarbs = carbs.toDoubleOrNull()
    val parsedFat = fat.toDoubleOrNull()
    val valid = parsedCalories != null && parsedProtein != null && parsedCarbs != null && parsedFat != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily meal target") },
        text = {
            Column {
                OutlinedTextField(value = calories, onValueChange = { calories = it }, label = { Text("Calories (kcal)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = protein, onValueChange = { protein = it }, label = { Text("Protein (g)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = carbs, onValueChange = { carbs = it }, label = { Text("Carbs (g)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = fat, onValueChange = { fat = it }, label = { Text("Fat (g)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 6.dp))
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && valid,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call set_meal_target's dispatch makes: MealController.setTarget(...).
                            result = MealController.setTarget(context, parsedCalories!!, parsedProtein!!, parsedCarbs!!, parsedFat!!)
                            busy = false
                        }
                    },
                ) { Text("Set") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --------------------------------------------------------------------- sleep

@Composable
fun LogSleepDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hours by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current
    val parsedHours = hours.toDoubleOrNull()
    val parsedQuality = quality.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log sleep") },
        text = {
            Column {
                OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("Hours slept") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = quality, onValueChange = { quality = it }, label = { Text("Quality 1-5 (optional)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 6.dp))
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && parsedHours != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call log_sleep's dispatch makes: SleepController.logSleep(...).
                            // No date override here - a hand-typed log is always for tonight's own
                            // wake-date, same as an un-dated spoken log.
                            result = SleepController.logSleep(
                                context = context,
                                durationHours = parsedHours!!,
                                quality = if (quality.isNotBlank()) parsedQuality else null,
                                notes = null,
                                sleepDateOverride = null,
                            )
                            busy = false
                        }
                    },
                ) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SetSleepTargetDialog(currentHours: Double?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hours by remember { mutableStateOf(currentHours?.let(::formatPlain) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current
    val parsedHours = hours.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nightly sleep target") },
        text = {
            Column {
                OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("Hours") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && parsedHours != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call set_sleep_target's dispatch makes: SleepController.setTarget(...).
                            result = SleepController.setTarget(context, parsedHours!!)
                            busy = false
                        }
                    },
                ) { Text("Set") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --------------------------------------------------------------------- mass

@Composable
fun LogBodyweightDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var weight by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("lbs") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current
    val parsedWeight = weight.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log bodyweight") },
        text = {
            Column {
                OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Weight") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(Modifier.padding(top = 8.dp)) {
                    UnitChoice("lbs", unit == "lbs", enabled = !busy) { unit = "lbs" }
                    UnitChoice("kg", unit == "kg", enabled = !busy, modifier = Modifier.padding(start = 16.dp)) { unit = "kg" }
                }
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && parsedWeight != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call log_bodyweight's dispatch makes: WorkoutController.logBodyweight(...).
                            result = WorkoutController.logBodyweight(context, parsedWeight!!, unit)
                            busy = false
                        }
                    },
                ) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnitChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Text(
        label.uppercase(),
        style = LegionType.stamp,
        color = if (selected) sem.data else sem.faint,
        modifier = modifier.clickable(enabled = enabled) { onClick() },
    )
}

// ----------------------------------------------------------------- training

@Composable
fun LogWorkoutSetDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exercise by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("") }
    var reps by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("lbs") }
    var busy by remember { mutableStateOf(false) }
    var outcomeMessage by remember { mutableStateOf<String?>(null) }
    var outcomeSuccess by remember { mutableStateOf(false) }
    val sem = LocalLegionSemantics.current
    val parsedSets = sets.toIntOrNull()
    val parsedReps = reps.toIntOrNull()
    val parsedWeight = weight.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a set") },
        text = {
            Column {
                OutlinedTextField(value = exercise, onValueChange = { exercise = it }, label = { Text("Exercise") }, enabled = !busy)
                OutlinedTextField(value = sets, onValueChange = { sets = it }, label = { Text("Sets") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = reps, onValueChange = { reps = it }, label = { Text("Reps (optional)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Weight (optional)") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 6.dp))
                if (weight.isNotBlank()) {
                    Row(Modifier.padding(top = 8.dp)) {
                        UnitChoice("lbs", unit == "lbs", enabled = !busy) { unit = "lbs" }
                        UnitChoice("kg", unit == "kg", enabled = !busy, modifier = Modifier.padding(start = 16.dp)) { unit = "kg" }
                    }
                }
                outcomeMessage?.let {
                    Text(it, style = LegionType.stamp, color = if (outcomeSuccess) sem.data else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (outcomeMessage == null || !outcomeSuccess) {
                TextButton(
                    enabled = !busy && exercise.isNotBlank() && parsedSets != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Same call log_workout_set's dispatch makes: WorkoutController.logSet(...).
                            val outcome = WorkoutController.logSet(
                                context = context,
                                exercise = exercise.trim(),
                                sets = parsedSets!!,
                                reps = if (reps.isNotBlank()) parsedReps else null,
                                weightValue = if (weight.isNotBlank()) parsedWeight else null,
                                weightUnit = if (weight.isNotBlank()) unit else null,
                            )
                            outcomeMessage = outcome.message
                            outcomeSuccess = outcome.success
                            busy = false
                        }
                    },
                ) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (outcomeMessage == null || !outcomeSuccess) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -------------------------------------------------------------------- delete

/**
 * Per-row delete, ticket 03 build item 3. `undo_last_log` picks the single most recent row across
 * ALL FOUR streams and deletes THAT ONE via `WorkoutController.deleteSetLog`/`deleteBodyweightLog`/
 * `SleepController.deleteSleepLog`/`MealController.deleteMealLog` (traced in
 * `service/LiveToolbox.kt`'s `undoLastLog`) - each of those four delete functions already takes the
 * SPECIFIC row to delete, not "the most recent", so tapping a row and calling the same function with
 * THAT row reaches the identical code path undo does; only the selection (voice picks newest-across-
 * streams, a tap picks whichever row was tapped) differs. No new DAO call was needed.
 */
@Composable
fun DeleteLogDialog(subtitle: String, onDelete: suspend () -> String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sem = LocalLegionSemantics.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this log?") },
        text = {
            Column {
                Text(subtitle, style = LegionType.stamp, color = sem.faint)
                result?.let { Text(it, style = LegionType.stamp, color = sem.data, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            result = onDelete()
                            busy = false
                        }
                    },
                ) { Text("DELETE", color = sem.quarantined) }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --------------------------------------------------------------------- misc

/** "150" stays "150", not "150.0" - a driver typing back a round number should see a round number. */
private fun formatPlain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
