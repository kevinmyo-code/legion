package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.vehicle.GarageController
import com.kevin.midnightai.vehicle.GarageDoorConfig
import com.kevin.midnightai.vehicle.GarageException
import kotlinx.coroutines.launch

/**
 * The Cruise garage sheet - structural sibling of [DtcSheet]. Lists the
 * driver's enabled garage/gate doors, each a single "ACTIVATE" button behind
 * a confirm dialog. Action-only, no status: a handheld garage remote is a
 * single-button toggle, so this never claims a door is opening or closing,
 * only that it's been triggered (see [com.kevin.midnightai.vehicle.GarageOpener]'s
 * KDoc).
 */
@Composable
internal fun GarageSheet(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val doors = remember { GarageController.configuredDoors(context) }
    var pending by remember { mutableStateOf<GarageDoorConfig?>(null) }
    var triggeringId by remember { mutableStateOf<String?>(null) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background.copy(alpha = 0.97f)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Header: title + close, same bar-and-katakana shape as DtcSheet.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(14.dp).background(AriaColors.StampRed))
                Spacer(Modifier.width(8.dp))
                Text("GARAGE", style = AriaType.neonLabel.copy(fontSize = 13.sp), color = AriaColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "ガレージ",
                    style = AriaType.neonLabel.copy(fontSize = 10.sp, letterSpacing = 2.sp),
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.weight(1f))
                GarageSheetAction("CLOSE") { onDismiss() }
            }
            Spacer(Modifier.height(14.dp))

            when {
                doors.isEmpty() -> GarageCenterNote(
                    "NO DOORS SET UP",
                    sub = "Add a garage relay in Settings to use this.",
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(doors, key = { it.id }) { door ->
                            DoorCard(
                                door = door,
                                busy = triggeringId == door.id,
                                onActivateTap = { pending = door },
                            )
                        }
                    }
                    statusLine?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            it,
                            style = AriaType.readout.copy(fontSize = 13.sp),
                            color = if (statusIsError) AriaColors.ScanRed else AriaColors.Patina,
                        )
                    }
                }
            }
        }
    }

    // Confirm gate lives here, at this call site - GarageController.activate
    // itself has no confirm awareness (see its KDoc).
    pending?.let { door ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Trigger the ${door.friendlyName} door?") },
            text = {
                Text(
                    "This sends one pulse, like pressing a garage remote once. " +
                        "I can't tell whether it'll open or close it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = door
                    pending = null
                    triggeringId = target.id
                    statusLine = null
                    scope.launch {
                        try {
                            GarageController.activate(context, target)
                            statusIsError = false
                            statusLine = "Triggered ${target.friendlyName}."
                        } catch (e: GarageException) {
                            statusIsError = true
                            statusLine = e.message ?: "Couldn't reach the garage relay."
                        } finally {
                            triggeringId = null
                        }
                    }
                }) { Text("TRIGGER") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("CANCEL") }
            },
        )
    }
}

/** One door: name + a single ACTIVATE button, dimmed while a pulse is in flight. */
@Composable
private fun DoorCard(door: GarageDoorConfig, busy: Boolean, onActivateTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Panel.copy(alpha = 0.8f))
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            door.friendlyName,
            style = AriaType.readout.copy(fontSize = 16.sp),
            color = AriaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (busy) "TRIGGERING…" else "ACTIVATE",
            style = AriaType.neonLabel.copy(fontSize = 11.sp),
            color = if (busy) AriaColors.TextDim else AriaColors.Background,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (busy) Color.Transparent else AriaColors.Amber)
                .let { if (busy) it.border(1.dp, AriaColors.TextDim.copy(alpha = 0.5f), RoundedCornerShape(6.dp)) else it }
                .let { if (!busy) it.clickable { onActivateTap() } else it }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GarageCenterNote(text: String, sub: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = AriaType.neonLabel.copy(fontSize = 13.sp), color = AriaColors.TextDim)
        sub?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = AriaType.readout.copy(fontSize = 13.sp), color = AriaColors.TextDim)
        }
    }
}

@Composable
private fun GarageSheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel,
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
