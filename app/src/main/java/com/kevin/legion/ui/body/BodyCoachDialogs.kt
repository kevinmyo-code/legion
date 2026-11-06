@file:Suppress("FunctionNaming", "MagicNumber", "LongMethod")

package com.kevin.legion.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.bodycoach.BodyCoachWrites
import com.kevin.legion.bodycoach.ProfileWire
import com.kevin.legion.bodycoach.SavedMealWire
import com.kevin.legion.meals.MealController
import com.kevin.legion.ui.theme.soft.SoftColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The hands paths for the fat-loss loop's engine writes (`.scratch/fat-loss/` ticket 21, ADR 0035):
 * a waist measurement, the body profile (with the weekly line's off switch), and saved meals. Each
 * calls [BodyCoachWrites], the same REST views the voice tools write through, or, for logging a
 * saved meal, [MealController.logSavedMeal], the path `log_meal` takes. Every dialog shows the
 * write's own sentence; nothing here says "saved" on its own authority.
 */

/** "36.5" to 36.5, or null. Accepts a comma decimal. */
internal fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

@Composable
fun LogWaistDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waist by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val parsed = parseNumber(waist)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log waist") },
        text = {
            Column {
                OutlinedTextField(
                    value = waist,
                    onValueChange = { waist = it },
                    label = { Text("Waist (inches)") },
                    enabled = !busy && result == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "Around the navel, relaxed, same time each week. Saved on the engine.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftColors.text3,
                    modifier = Modifier.padding(top = 4.dp),
                )
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(enabled = !busy && parsed != null, onClick = {
                    busy = true
                    scope.launch {
                        result = BodyCoachWrites.forApp(context).logWaist(parsed!!).sentence
                        busy = false
                    }
                }) { Text("Log") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val SEXES = listOf("male" to "Male", "female" to "Female")
private val ACTIVITY = listOf(
    "sedentary" to "Desk, little exercise",
    "light" to "Light, 1-3 days",
    "moderate" to "Moderate, 3-5 days",
    "very" to "Very, 6-7 days",
)

@Composable
private fun ChoiceChips(options: List<Pair<String, String>>, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                (if (on) "Selected: " else "") + label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) SoftColors.onPrimaryContainer else SoftColors.text2,
                modifier = Modifier
                    .background(if (on) SoftColors.primaryContainer else SoftColors.cardHigh, RoundedCornerShape(50))
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(key) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The body profile, private to this member (ADR 0052): height, sex, birth year, activity, the rate
 * and protein per kg the estimate uses, and the Monday weekly line on or off (ruling 7, compulsion
 * test (d)). Only changed fields are sent; the engine says what was saved.
 */
@Composable
fun BodyProfileDialog(current: ProfileWire?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var heightIn by remember { mutableStateOf(current?.heightCm?.let { "%.1f".format(it / 2.54) } ?: "") }
    var sex by remember { mutableStateOf(current?.sex) }
    var birthYear by remember { mutableStateOf(current?.birthYear?.toString() ?: "") }
    var activity by remember { mutableStateOf(current?.activityLevel) }
    var rate by remember { mutableStateOf(current?.targetRatePct?.toString() ?: "0.75") }
    var protein by remember { mutableStateOf(current?.proteinGPerKg?.toString() ?: "2.0") }
    var weeklyOn by remember { mutableStateOf(current?.weeklyLineOn ?: true) }
    var goalWeight by remember { mutableStateOf(current?.goalWeightLbs?.let { "%.1f".format(it) } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val editable = !busy && result == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Body profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Private to you. Used only for the energy estimate.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftColors.text3,
                )
                OutlinedTextField(heightIn, { heightIn = it }, label = { Text("Height (inches)") }, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 8.dp))
                Text("Sex (the formula needs it)", style = MaterialTheme.typography.labelMedium, color = SoftColors.text2, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                ChoiceChips(SEXES, sex, editable) { sex = it }
                OutlinedTextField(birthYear, { birthYear = it }, label = { Text("Birth year") }, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 8.dp))
                Text("Activity", style = MaterialTheme.typography.labelMedium, color = SoftColors.text2, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                ChoiceChips(ACTIVITY, activity, editable) { activity = it }
                OutlinedTextField(rate, { rate = it }, label = { Text("Rate, % of bodyweight a week (0.25-1.0)") }, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(protein, { protein = it }, label = { Text("Protein, g per kg (1.6-2.2)") }, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(goalWeight, { goalWeight = it }, label = { Text("Goal or milestone weight (lb)") }, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monday weight line", style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
                        Text(if (weeklyOn) "On: one line at 8 am" else "Off", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                    }
                    Switch(checked = weeklyOn, onCheckedChange = { weeklyOn = it }, enabled = editable)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    val fields = buildJsonObject {
                        parseNumber(heightIn)?.takeIf { it > 0 }?.let { put("height_in", it) }
                        sex?.let { put("sex", it) }
                        birthYear.trim().toIntOrNull()?.let { put("birth_year", it) }
                        activity?.let { put("activity_level", it) }
                        parseNumber(rate)?.let { put("target_rate_pct", it) }
                        parseNumber(protein)?.let { put("protein_g_per_kg", it) }
                        parseNumber(goalWeight)?.takeIf { it > 0 }?.let { put("goal_weight_lbs", it) }
                        put("weekly_line_on", weeklyOn)
                    }
                    scope.launch {
                        result = BodyCoachWrites.forApp(context).putProfile(fields).sentence
                        busy = false
                    }
                }) { Text("Save") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Saved meals (ruling 3, "mostly voice + saved meals"): log one, delete one, or add one. Logging goes
 * through [MealController.logSavedMeal], the same path "log my usual breakfast" takes, so it queues
 * offline. Adding and deleting need the engine and say so when it is down. Figures are estimates.
 */
@Composable
fun SavedMealsDialog(meals: List<SavedMealWire>, note: String?, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    val canAdd = name.isNotBlank() && kcal.trim().toIntOrNull() != null && parseNumber(protein) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved meals") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (meals.isEmpty()) {
                    Text(note ?: "No saved meals yet.", style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
                }
                meals.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
                            val figures = listOfNotNull(m.caloriesKcal?.let { "$it kcal" }, m.proteinG?.let { "${it.toInt()} g protein" })
                            Text((figures + "estimate").joinToString(", "), style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                        }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                result = MealController.logSavedMeal(context, m).message
                                busy = false
                                onChanged()
                            }
                        }) { Text("Log") }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                result = BodyCoachWrites.forApp(context).deleteSavedMeal(m.id).sentence
                                busy = false
                                onChanged()
                            }
                        }) { Text("Delete", color = SoftColors.onAlert) }
                    }
                }
                if (meals.isNotEmpty() && note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                }
                Text("Add a saved meal", style = MaterialTheme.typography.labelMedium, color = SoftColors.text2, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Usual breakfast") }, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(kcal, { kcal = it }, label = { Text("kcal") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(protein, { protein = it }, label = { Text("Protein g") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Text("Your own estimate of the meal; it is logged as an estimate.", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 4.dp))
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && canAdd, onClick = {
                busy = true
                scope.launch {
                    val out = BodyCoachWrites.forApp(context).saveMeal(name.trim(), kcal.trim().toInt(), parseNumber(protein)!!, null)
                    result = out.sentence
                    if (out.ok) {
                        name = ""
                        kcal = ""
                        protein = ""
                    }
                    busy = false
                    onChanged()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
