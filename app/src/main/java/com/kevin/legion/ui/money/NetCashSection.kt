@file:Suppress("FunctionNaming", "MagicNumber")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp.

package com.kevin.legion.ui.money

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.finance.NetCashFloor
import com.kevin.legion.finance.NetCashText
import com.kevin.legion.finance.NetCashWire
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.assistant.TrendChart
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType
import java.time.Instant
import java.time.ZoneId

/**
 * Net cash on the Money screen (Kevin, 2026-11-16, ADR 0094): the working, set as Typeset matter
 * (square, ruled, ink; the accent only for the low-cash flag, always with its words). Checking, each
 * card with its last four and "of which pending", a rule, net cash, the as-of time; then the 30-day
 * line (posted basis, said under it) and the floor he sets. Every figure is the engine's
 * ([NetCashWire]); nothing here adds a part to another.
 */
@Composable
internal fun NetCashSection(
    read: FinanceRead<NetCashWire>?,
    floorCents: Long?,
    onSetFloor: (Long?) -> Unit,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val model = NetCashText.model(read, floorCents, now, zone)
    val body = read?.valueOrNull
    val ink = if (model.flag != null) SoftColors.caution else SoftColors.text
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        Text("NET CASH", style = TypesetType.caps, color = SoftColors.text)
        HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(model.figure, style = TypesetType.fig, color = ink, maxLines = 1)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(model.label, style = TypesetType.bodyStrong, color = ink)
                Text(model.line, style = TypesetType.smallItalic, color = SoftColors.text2)
            }
        }
        model.savedNote?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
        if (body != null && model.hasFigure) {
            Working(body)
        } else if (body != null) {
            body.missing.forEach { m ->
                val ending = m.mask?.let { " ending $it" }.orEmpty()
                RuledRow("${m.label}$ending", "no balance", m.reason)
            }
        }
        if (body != null && model.hasFigure) {
            NetCashText.trend(body, floorCents)?.let { card ->
                Text("LAST 30 DAYS", style = TypesetType.caps, color = SoftColors.text, modifier = Modifier.padding(top = 12.dp))
                TrendChart(card)
            }
            Text(NetCashText.trendNote(body), style = TypesetType.smallItalic, color = SoftColors.text2)
        }
        Floor(floorCents, onSetFloor)
    }
}

@Composable
private fun Working(body: NetCashWire) {
    NetCashText.rows(body).forEach { RuledRow(it.label, it.value, it.note) }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
    val currency = body.currency ?: "USD"
    body.netCents?.let { RuledRow("Net cash", FinanceText.exact(it, currency), null, strong = true) }
    body.pendingCents?.takeIf { it != 0L }?.let {
        Text(
            "Of which pending at the bank: ${FinanceText.exact(it, currency)}",
            style = TypesetType.small,
            color = SoftColors.text2,
        )
    }
}

@Composable
private fun RuledRow(label: String, value: String, note: String?, strong: Boolean = false) {
    val style = if (strong) TypesetType.bodyStrong else TypesetType.body
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = style,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = style, color = SoftColors.text, maxLines = 1, softWrap = false)
        }
        note?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
    }
    if (!strong) HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

/** The low-cash floor (optional). With none, only below zero is marked. */
@Composable
private fun Floor(floorCents: Long?, onSetFloor: (Long?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val words = floorCents?.let { "Low-cash floor: ${FinanceText.money(it)}. Below it, net cash is marked here." }
        ?: "No low-cash floor set: net cash is marked only below zero."
    Text(words, style = TypesetType.small, color = SoftColors.text2, modifier = Modifier.padding(top = 10.dp))
    if (!editing) {
        Row {
            TextButton(if (floorCents == null) "Set a floor" else "Change floor") {
                typed = floorCents?.let { FinanceText.bare(it).replace(",", "") }.orEmpty()
                editing = true
            }
            if (floorCents != null) TextButton("Clear floor") { onSetFloor(null) }
        }
        return
    }
    val parsed = NetCashFloor.parse(typed)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Floor in dollars", style = TypesetType.small) },
            singleLine = true,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = typed.isNotBlank() && parsed == null,
            modifier = Modifier.width(180.dp),
        )
        TextButton("Save") {
            if (parsed != null) {
                onSetFloor(parsed)
                editing = false
            }
        }
        TextButton("Cancel") { editing = false }
    }
    if (typed.isNotBlank() && parsed == null) {
        Text("That is not an amount: type dollars, like 500.", style = TypesetType.small, color = SoftColors.caution)
    }
}

@Composable
private fun TextButton(words: String, onClick: () -> Unit) {
    Text(
        words,
        style = TypesetType.small.copy(textDecoration = TextDecoration.Underline),
        color = SoftColors.text,
        modifier = Modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = words, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}
