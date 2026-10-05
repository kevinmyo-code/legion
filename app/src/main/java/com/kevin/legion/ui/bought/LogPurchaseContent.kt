package com.kevin.legion.ui.bought

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate

/**
 * "Log something bought" - the hands path for the `bought_log` tool's `log` action (ADR 0035), and
 * the same fields: item, date (today by default), optional store, price, note, and a private toggle.
 *
 * **The price field says what a price here is** ("entered by hand, never checked against the
 * bank"), because it is never reconciled and never summed into Money (purchase-log map notes).
 * **A failed save says so in words and keeps what was typed**: [LogFormState.error] is the whole
 * explanation, and nothing on this screen reports a save that did not return 2xx.
 */
@Composable
fun LogPurchaseContent(form: LogFormState, today: Int, callbacks: LogCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        LogTopBar(onBack = callbacks.onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LogFields(form, today, callbacks.onEdit)
            PrivateRow(form.isPrivate) { v -> callbacks.onEdit { it.copy(isPrivate = v) } }
            form.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SoftColors.onAlert) }
            Button(
                onClick = callbacks.onSave,
                enabled = !form.saving && form.item.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (form.saving) "Saving..." else "Log it") }
        }
    }
}

@Composable
private fun LogTopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        Text(
            "Log something bought",
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun LogFields(form: LogFormState, today: Int, onEdit: ((LogFormState) -> LogFormState) -> Unit) {
    FormField(form.item, "What was bought") { v -> onEdit { it.copy(item = v) } }
    FormField(form.dateText, "Bought on (YYYY-MM-DD)") { v -> onEdit { it.copy(dateText = v) } }
    Row {
        TextButton(onClick = { onEdit { it.copy(dateText = dayText(today)) } }) { Text("Today") }
        TextButton(onClick = { onEdit { it.copy(dateText = dayText(today - 1)) } }) { Text("Yesterday") }
    }
    FormField(form.store, "Store (optional)") { v -> onEdit { it.copy(store = v) } }
    FormField(
        value = form.price,
        label = "Price (optional)",
        support = "Entered by hand. Never checked against the bank or counted in Money.",
        keyboard = KeyboardType.Decimal,
    ) { v -> onEdit { it.copy(price = v) } }
    FormField(form.note, "Quantity or note (optional)", singleLine = false) { v -> onEdit { it.copy(note = v) } }
}

@Composable
private fun FormField(
    value: String,
    label: String,
    support: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = support?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrivateRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Private", style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
            Text(
                "Only you can see it. Left off, the whole household can.",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text2,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun dayText(epochDay: Int): String = LocalDate.ofEpochDay(epochDay.toLong()).toString()
