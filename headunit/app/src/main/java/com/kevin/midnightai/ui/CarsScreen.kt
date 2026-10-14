package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ai.AvatarStudio
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.data.local.Vehicle
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.util.shortDate
import com.kevin.midnightai.vehicle.ActiveVehicle
import com.kevin.midnightai.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CARS - the roster (car manager, 2026-07-16).
 *
 * **Why this is a top-level surface.** Picking a car is a pre-flight action: you do
 * it in the driveway, before the OBD starts recording, and a roster buried in
 * Setup is fine for two cars and useless for someone with twelve. It answers the
 * two questions you actually have standing next to the car - *which car is this*
 * and *am I about to record onto the right one* - and nothing else. Odometer, MPG,
 * costs and service status are the Logbook's job; putting them here turns a picker
 * into a dashboard you have to read while someone waits.
 *
 * **Deliberately not art-directed.** Drawn in the existing AriaColors/AriaType
 * tokens, not as a garage scene. Ticket 11 (settings-as-an-interactive-garage,
 * Tokyo Drift) is blocked behind ticket 05, which decides whether the product has
 * ONE authored aesthetic or a theme system - and ROADMAP is explicit that the art
 * must not be commissioned before 05 settles. The layout is the cheap part to redo
 * once it does.
 *
 * **Named CARS, not GARAGE.** That word is already taken twice: Cruise's GARAGE tab
 * opened the Shelly door relay (GarageSheet, deleted 2026-10-14; now Settings > Garage & gate), and ticket 11 wants it too. Three
 * claimants on one noun is how you get a confusing dash.
 */
@Composable
fun CarsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cars by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var activeId by remember { mutableStateOf(ActiveVehicle.current(context)) }
    var showArchived by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload, showArchived) {
        cars = withContext(Dispatchers.IO) {
            if (showArchived) VehicleController.allVehiclesIncludingArchived(context)
            else VehicleController.allVehicles(context)
        }
        activeId = ActiveVehicle.current(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("CARS", style = AriaType.readout.copy(fontSize = 28.sp), color = AriaColors.Amber)
                Text(
                    "Which car are you in?",
                    style = AriaType.neonLabel,
                    color = AriaColors.TextDim,
                )
            }
            TextButton(onClick = onBack) { Text("DONE") }
        }
        Spacer(Modifier.height(12.dp))

        if (cars.isEmpty()) {
            Text(
                "No cars yet. One appears automatically the first time an adapter connects.",
                style = AriaType.neonLabel,
                color = AriaColors.TextDim,
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cars, key = { it.obdMac }) { car ->
                CarRow(
                    context = context,
                    car = car,
                    active = car.obdMac == activeId,
                    onSelect = {
                        selectCar(context, car.obdMac)
                        activeId = car.obdMac
                        reload++
                    },
                    onArchiveToggle = {
                        scope.launch {
                            if (car.archived) VehicleController.unarchive(context, car.obdMac)
                            else VehicleController.archive(context, car.obdMac)
                            reload++
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // The corrector lives in OBD -> HISTORY, where the drive list already is.
        // Nobody would guess that, so say it here rather than duplicate the list.
        Text(
            "Drove on the wrong car? Switch to it, then fix the drive in OBD - HISTORY.",
            style = AriaType.neonLabel,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showAdd = true }) { Text("ADD CAR") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                ActiveVehicle.select(context, null)
                activeId = ActiveVehicle.current(context)
                reload++
            }) { Text("USE ADAPTER") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "HIDE ARCHIVED" else "SHOW ARCHIVED")
            }
        }
    }

    if (showAdd) {
        AddCarDialog(
            onDismiss = { showAdd = false },
            onAdd = { year, make, model, trim ->
                scope.launch {
                    val created = VehicleController.createCarProfile(context, year, make, model, trim)
                    // createCarProfile already selected it; run the same switch
                    // side-effects so the new car's (empty) identity takes hold.
                    selectCar(context, created.obdMac)
                    showAdd = false
                    reload++
                }
            },
        )
    }
}

/**
 * Everything that must happen when the active car changes.
 *
 * Order matters and each line is load-bearing:
 *  - [AriaBrain.invalidateBase] - identity is per-car and the base instruction is
 *    cached for 2 minutes, so without this the new car's companion speaks with the
 *    previous car's persona until the TTL lapses.
 *  - [AriaPalette.init] - re-reads the colorway for this car.
 *  - `ACTION_CAR_SWITCHED` - a warm Live socket holds the PREVIOUS car's voice.
 *    This is the same shape as the "default voice after onboarding" field bug.
 *  - [AppBackground.notifyChanged] - wallpaper + avatar are decoded at render, so
 *    the surfaces need telling to re-read.
 *
 * There used to be a lazy per-car avatar/wallpaper Drive fetch here too
 * (SyncEngine.ensureCompanionMedia) - removed 2026-09-27 with in-app image
 * generation. Every car now shows the same bundled art (see SeedArt), so
 * there is nothing per-car left to fetch.
 */
private fun selectCar(context: Context, vehicleId: String) {
    ActiveVehicle.select(context, vehicleId)
    AriaPalette.init(context)
    ActiveVehicle.notifyResolutionChanged(context)
}

@Composable
private fun CarRow(
    context: Context,
    car: Vehicle,
    active: Boolean,
    onSelect: () -> Unit,
    onArchiveToggle: () -> Unit,
) {
    val label = remember(car.obdMac) {
        VehicleController.displayLabel(car).ifBlank { car.name.ifBlank { "Unnamed car" } }
    }
    val companion = remember(car.obdMac) { CompanionProfile.nameFor(context, car.obdMac) }
    var lastDriven by remember(car.obdMac) { mutableStateOf<Long?>(null) }
    LaunchedEffect(car.obdMac) {
        lastDriven = withContext(Dispatchers.IO) {
            runCatching {
                com.kevin.midnightai.data.local.CarDatabase.getDatabase(context)
                    .odbSampleDao().lastSampleMs(car.obdMac)
            }.getOrNull()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Panel)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) AriaColors.Amber else AriaColors.PanelBorder,
                RoundedCornerShape(6.dp),
            )
            .clickable { onSelect() }
            .padding(12.dp),
    ) {
        CarAvatar(context, car.obdMac)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = AriaType.neonLabel, color = AriaColors.TextPrimary)
            Text(
                buildString {
                    append(companion.ifBlank { CompanionProfile.DEFAULT_NAME })
                    lastDriven?.let { append(" ・ last driven ${shortDate(it)}") }
                },
                style = AriaType.neonLabel.copy(fontSize = 11.sp),
                color = AriaColors.TextDim,
            )
        }
        TextButton(onClick = onArchiveToggle) {
            Text(if (car.archived) "UNARCHIVE" else "ARCHIVE", fontSize = 11.sp)
        }
    }
}

/**
 * The car's avatar, decoded at render with `inSampleSize` per §9's "No StateFlow
 * Bitmaps" rule - the reason that rule exists is exactly this screen: a dozen faces
 * on one list on a cheap head unit. If it still stutters, decode only visible rows;
 * do NOT add a bitmap cache.
 *
 * An empty slot when the art isn't here yet is deliberate: an empty circle reads as
 * "not downloaded", a placeholder face reads as broken.
 */
@Composable
private fun CarAvatar(context: Context, vehicleId: String) {
    val bgVersion by AppBackground.version.collectAsState()
    val bmp: Bitmap? = remember(vehicleId, bgVersion) {
        runCatching {
            AvatarStudio.avatarThumbnail(context, CompanionProfile.avatarIdFor(vehicleId), THUMB_PX)
        }.getOrNull()
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AriaColors.Background)
            .border(1.dp, AriaColors.PanelBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            // Same head/shoulders-preserving crop as CruiseScreen's
            // AvatarVibe: this thumbnail is a small circle and a
            // Center-aligned Crop on a tall portrait loses the head.
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "SYNC",
                style = AriaType.neonLabel.copy(fontSize = 8.sp),
                color = AriaColors.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val THUMB_PX = 96

@Composable
private fun AddCarDialog(onDismiss: () -> Unit, onAdd: (Int, String, String, String) -> Unit) {
    var year by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var trim by remember { mutableStateOf("") }
    val valid = make.isNotBlank() && model.isNotBlank() && (year.toIntOrNull() ?: 0) >= 1900

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADD CAR") },
        text = {
            Column {
                Text(
                    "A car profile isn't tied to an adapter, so you can move one dongle " +
                        "between cars and keep their logs apart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = make, onValueChange = { make = it },
                    label = { Text("Make") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Model") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = trim, onValueChange = { trim = it },
                    label = { Text("Trim (optional)") }, singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onAdd(year.toIntOrNull() ?: 0, make.trim(), model.trim(), trim.trim()) },
            ) { Text("ADD") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}
