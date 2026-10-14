package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.MidnightEvents
import com.kevin.midnightai.ai.AvatarStudio
import com.kevin.midnightai.data.PhotoAlbum
import com.kevin.midnightai.data.PhotoAlbumStore
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.SpendGate
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.vehicle.VehicleController
import com.kevin.midnightai.vehicle.VehicleSpecController
import com.kevin.midnightai.vehicle.VinDecoder
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.BuildSheetController
import com.kevin.midnightai.data.local.BuildEntry
import com.kevin.midnightai.data.local.MonthlyRecap
import com.kevin.midnightai.data.local.YearlyWrapped
import com.kevin.midnightai.data.local.VehicleSpec
import com.kevin.midnightai.data.local.LibraryTrack
import com.kevin.midnightai.data.local.Mixtape
import com.kevin.midnightai.data.local.MixtapeTrack
import com.kevin.midnightai.data.local.MaintenanceItem
import com.kevin.midnightai.data.local.Vehicle
import com.kevin.midnightai.media.MixtapeLibrary
import com.kevin.midnightai.media.MixtapePlayer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kevin.midnightai.util.LogbookExporter
import com.kevin.midnightai.util.ShareEntry
import com.kevin.midnightai.util.shortDate
import kotlin.math.abs

/**
 * The car's living logbook, rendered as a vintage ring-binder flipbook (parked
 * mode). It merges the service history ([com.kevin.midnightai.data.local.ServiceRecord])
 * and the build sheet ([com.kevin.midnightai.data.local.BuildEntry]) into one
 * chronological run of aged-paper pages the driver swipes through - oldest first,
 * so page one is where it all began.
 *
 * Money is private: per-entry costs only show when [SpendGate] is unlocked, and
 * the grand total on the cover is likewise gated (and hidden otherwise).
 * Aesthetic follows the design tokens (aged paper + sepia ink + stamp red + chrome
 * binder rings); see [com.kevin.midnightai.ui.theme.AriaColors] / [AriaType].
 */
private data class LogItem(
    val date: Long,
    val title: String,
    val category: String,
    val mileage: Int?,
    val cost: Double?,
    val detail: String,
    val photoPath: String = "",
)

private const val ENTRIES_PER_PAGE = 4

private enum class BinderTab(val label: String) {
    // TO-DO was retired 2026-10-13 with the car-task voice tools; to-dos live in the LEGION engine.
    LOG("LOG"), MODS("MODS"), SPECS("SPECS"), ALBUM("ALBUM"), RECAP("RECAP"), MIX("MIX"), DUE("DUE"),
}

/**
 * The car's binder (parked mode) - one place for everything, switched by
 * file-folder TABS down the right edge: LOG (the service + build history
 * flipbook), MODS (the logged build), and the rest. Album-cover
 * "binder" styling per the design references (FM STATION / Eizin Suzuki).
 */
@Composable
fun LogbookScreen(context: Context, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(BinderTab.LOG) }
    Box(Modifier.fillMaxSize().background(AriaColors.Background)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    BinderTab.LOG -> LogTab(context)
                    BinderTab.MODS -> ModsTab(context)
                    BinderTab.SPECS -> SpecsTab(context)
                    BinderTab.ALBUM -> AlbumTab(context)
                    BinderTab.RECAP -> RecapTab(context)
                    BinderTab.MIX -> MixTab(context)
                    BinderTab.DUE -> DueTab(context)
                }
            }
            TabRail(selected = tab, onSelect = { tab = it })
        }
        Text(
            text = "‹ BACK",
            style = AriaType.neonLabel,
            color = AriaColors.Amber,
            modifier = Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onBack() }
                .padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

/** The LOG tab: the chronological flipbook of service + build entries. */
@Composable
private fun LogTab(context: Context) {
    var items by remember { mutableStateOf<List<LogItem>?>(null) }
    var carImage by remember { mutableStateOf<Bitmap?>(null) }
    var carLabel by remember { mutableStateOf("") }
    val unlocked = remember { SpendGate.isUnlocked(context) }
    val title = remember { logbookTitle(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        items = loadLogbook(context)
        val vehicle = VehicleController.currentVehicle(context)
        carLabel = VehicleController.displayLabel(vehicle)
        // Polaroid: the driver's real car photo if they uploaded one. No
        // generated fallback any more (removed 2026-09-27 with in-app image
        // generation) - an empty polaroid slot until they add a real photo.
        carImage = AvatarStudio.carImage(context)
    }

    Box(Modifier.fillMaxSize()) {
        when (val loaded = items) {
            null -> CenterNote("Opening the logbook…", AriaColors.TextDim)
            else -> if (loaded.isEmpty()) {
                // Even with nothing logged, open on the front cover with the car's
                // polaroid (the driver's own uploaded photo, if any) so the logbook
                // always greets you with the car, not a bare note.
                Box(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 64.dp)) {
                    CoverPage(title, 0, carImage, carLabel, Modifier.fillMaxSize())
                }
            } else {
                Flipbook(loaded, title, carImage, carLabel, unlocked)

                // Share card: amber label in top-right, only when entries exist.
                val capturedLabel = carLabel
                Text(
                    "SHARE",
                    style = AriaType.neonLabel,
                    color = AriaColors.Amber,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            scope.launch(Dispatchers.IO) {
                                val shareEntries = loaded.take(5).map { item ->
                                    ShareEntry(item.title, shortDate(item.date), item.category)
                                }
                                LogbookExporter.share(context, capturedLabel, shareEntries)
                            }
                        }
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
        }
    }
}

/** File-folder tabs down the right edge, one per binder section. */
@Composable
private fun TabRail(selected: BinderTab, onSelect: (BinderTab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(46.dp)
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (t in BinderTab.entries) {
            val color = tabColor(t)
            val active = t == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .background(if (active) color else color.copy(alpha = 0.5f))
                    .clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    style = AriaType.stamp.copy(fontSize = 12.sp),
                    color = AriaColors.Background,
                    softWrap = true,
                    modifier = Modifier
                        // Rotation is a draw-time transform only - Compose still
                        // measures the Text against the cell's pre-rotation
                        // (narrow) width, so a label like "RECAP"/"SPECS"/"ALBUM"
                        // wraps against 46dp and the second line gets clipped by
                        // the cell's fixed height. Swap width/height constraints
                        // before measuring so it wraps against the CELL'S HEIGHT
                        // (its true visual width once rotated) instead.
                        .layout { measurable, constraints ->
                            val rotated = constraints.copy(
                                minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
                            )
                            val placeable = measurable.measure(rotated)
                            layout(placeable.height, placeable.width) {
                                placeable.place(
                                    x = -(placeable.width / 2 - placeable.height / 2),
                                    y = -(placeable.height / 2 - placeable.width / 2),
                                )
                            }
                        }
                        .graphicsLayer { rotationZ = -90f },
                )
            }
        }
    }
}

private fun tabColor(tab: BinderTab): Color = when (tab) {
    BinderTab.LOG -> AriaColors.StampRed
    BinderTab.MODS -> AriaColors.SunsetGold
    BinderTab.SPECS -> AriaColors.Patina
    BinderTab.ALBUM -> AriaColors.NeonMagenta
    BinderTab.RECAP -> AriaColors.Amber
    BinderTab.MIX -> AriaColors.DeskWood
    BinderTab.DUE -> AriaColors.SunsetOrange
}

/** Aged-paper page scaffold (binder rings + title) shared by the paper tabs. */
@Composable
private fun BinderPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, top = 64.dp, end = 8.dp, bottom = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Paper)
            .border(1.dp, AriaColors.PaperAged, RoundedCornerShape(6.dp)),
    ) {
        BinderRings()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 6.dp, top = 18.dp, end = 18.dp, bottom = 14.dp),
        ) {
            Text(title, style = AriaType.stamp, color = AriaColors.InkFaded)
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** MODS / build tab: the logged build entries, with a quick add. */
@Composable
private fun ModsTab(context: Context) {
    val scope = rememberCoroutineScope()
    var mods by remember { mutableStateOf<List<BuildEntry>>(emptyList()) }
    var newText by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val unlocked = remember { SpendGate.isUnlocked(context) }
    LaunchedEffect(reload) { mods = BuildSheetController.history(context, "mod") }

    // One photo picker, retargeted per entry: tapping 📷 records which entry, then
    // the picked image is downscaled, saved, and attached to that entry.
    var pendingPhotoFor by remember { mutableStateOf<Long?>(null) }
    suspend fun attachPhoto(id: Long, bmp: Bitmap) {
        val saved = withContext(Dispatchers.IO) { AvatarStudio.saveLogbookPhoto(context, id, bmp) }
        if (saved != null) {
            CarDatabase.getDatabase(context).buildEntryDao().setPhoto(id, saved)
            reload++
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = pendingPhotoFor
        pendingPhotoFor = null
        if (uri != null && id != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            bmp?.let { attachPhoto(id, it) }
        }
    }
    // Camera capture (2026-07-12 dual-target work): same retarget-by-id
    // pattern as the gallery picker above, gated on hasCamera so a
    // camera-less head unit only ever sees the gallery glyph.
    val cameraCapture = rememberCameraCaptureLauncher(context) { bmp ->
        val id = pendingPhotoFor
        pendingPhotoFor = null
        if (id != null) scope.launch { attachPhoto(id, bmp) }
    }

    BinderPage("MODS & BUILD") {
        AddRow(value = newText, onValue = { newText = it }, hint = "Add a mod") {
            val t = newText.trim()
            if (t.isNotEmpty()) scope.launch {
                BuildSheetController.add(context, t, "mod", null)
                newText = ""; reload++
            }
        }
        Spacer(Modifier.height(8.dp))
        if (mods.isEmpty()) {
            Text("No mods logged yet.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
        } else {
            for (m in mods) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = AriaType.logbookHeading.copy(fontSize = 16.sp), color = AriaColors.Ink)
                        if (m.vendor.isNotBlank()) {
                            Text(m.vendor, style = AriaType.logbookBody.copy(fontSize = 12.sp), color = AriaColors.InkFaded)
                        }
                    }
                    // Attach / replace a photo for this entry.
                    Text(
                        if (m.photoPath.isNotBlank()) "📷✓" else "📷",
                        style = AriaType.stamp.copy(fontSize = 13.sp),
                        color = AriaColors.Patina,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                pendingPhotoFor = m.id
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    if (hasCamera(context)) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "📸",
                            style = AriaType.stamp.copy(fontSize = 13.sp),
                            color = AriaColors.Patina,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    pendingPhotoFor = m.id
                                    cameraCapture()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(dateStamp(m.date), style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.StampRed)
                    m.cost?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(if (unlocked) money(it) else "🔒", style = AriaType.logbookBody, color = AriaColors.Ink)
                    }
                }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
        }
    }
}

// --- DUE tab: the severe-service schedule (Part 3 on top of VehicleController's --
// Part 1/2 primitives: dueItems/unknownItems/nextService/formatRemaining). -------

/** One overdue row's "how far past" figures, in each anchor's own unit - never collapsed to one axis (see [computeOverdueInfo]). */
private data class OverdueInfo(val item: MaintenanceItem, val milesOver: Int?, val monthsOver: Int?)

/** One coming-up row's remaining figures, in each anchor's own unit - mirrors [VehicleController.NextService]'s per-axis shape but for every not-yet-due item, not just the soonest. */
private data class ComingInfo(val item: MaintenanceItem, val milesRemaining: Long?, val daysRemaining: Long?)

// Mirrors VehicleController's private MONTH_MS/DAY_MS - kept local rather than
// exposed, since this is presentation-layer arithmetic over the same anchors,
// not a second source of truth for what counts as due (that's isDue, reused
// as-is from VehicleController since it's `internal`, i.e. module-visible).
private const val DUE_MONTH_MS = 30L * 24 * 60 * 60 * 1000
private const val DUE_DAY_MS = 24L * 60 * 60 * 1000

/**
 * How far past due [item] is, on whichever axis (or both) actually crossed its
 * threshold. Deliberately does not collapse dual-axis overage into one number -
 * same reasoning as [VehicleController.computeNextService]'s doc: there's no
 * unit-free way to say "500 miles over" is worse or better than "2 months
 * over" without inventing a driving-pace rate, which Part 1 explicitly ruled
 * out. Returns nulls on an axis that isn't actually over (or has no anchor).
 */
private fun computeOverdueInfo(item: MaintenanceItem, currentMileage: Int, now: Long): OverdueInfo {
    val milesOver = if (item.intervalMiles != null && item.lastDoneMileage != null) {
        val over = currentMileage - item.lastDoneMileage - item.intervalMiles
        if (over >= 0) over else null
    } else null
    val monthsOver = if (item.intervalMonths != null && item.lastDoneDate != null) {
        val overMs = (now - item.lastDoneDate) - item.intervalMonths.toLong() * DUE_MONTH_MS
        if (overMs >= 0) (overMs / DUE_MONTH_MS).toInt() else null
    } else null
    return OverdueInfo(item, milesOver, monthsOver)
}

/** "2,400 mi over" / "3 mo over" / both joined - "due now" if an axis crossed but rounds to zero over. */
private fun overdueLine(info: OverdueInfo): String {
    val parts = listOfNotNull(
        info.milesOver?.let { if (it <= 0) "due now" else "${"%,d".format(it)} mi over" },
        info.monthsOver?.let { if (it <= 0) "due now" else "$it mo over" },
    )
    return parts.joinToString(" · ").ifBlank { "overdue" }
}

/**
 * How far off [item] is on whichever axis it's anchored on - not-yet-due only
 * (callers filter). [odometerUnset] mirrors [VehicleController.NextService]'s
 * flag: with no real odometer reading the mileage estimate is meaningless, so
 * the miles axis is skipped entirely rather than shown as a false countdown.
 */
private fun computeComingInfo(item: MaintenanceItem, currentMileage: Int, now: Long, odometerUnset: Boolean): ComingInfo {
    val milesRemaining = if (!odometerUnset && item.intervalMiles != null && item.lastDoneMileage != null) {
        (item.intervalMiles - (currentMileage - item.lastDoneMileage)).toLong()
    } else null
    val daysRemaining = if (item.intervalMonths != null && item.lastDoneDate != null) {
        val intervalMs = item.intervalMonths.toLong() * DUE_MONTH_MS
        val elapsedMs = now - item.lastDoneDate
        (intervalMs - elapsedMs) / DUE_DAY_MS
    } else null
    return ComingInfo(item, milesRemaining, daysRemaining)
}

/** "2,900 miles or 3 months, whichever comes first" / one axis only / "no interval yet" - reuses [VehicleController.formatRemaining] so the tab never disagrees with voice. */
private fun comingUpLine(info: ComingInfo): String {
    val miles = info.milesRemaining
    val days = info.daysRemaining
    return when {
        miles != null && days != null ->
            "${VehicleController.formatRemaining(miles, VehicleController.ScheduleUnit.MILES)} or " +
                "${VehicleController.formatRemaining(days, VehicleController.ScheduleUnit.DAYS)}, whichever comes first"
        miles != null -> VehicleController.formatRemaining(miles, VehicleController.ScheduleUnit.MILES)
        days != null -> VehicleController.formatRemaining(days, VehicleController.ScheduleUnit.DAYS)
        else -> "no interval yet"
    }
}

/**
 * DUE tab: the severe-service schedule, read straight off [MaintenanceItem]
 * rows via the DAO (the other binder tabs' convention - see MixTab/ModsTab
 * reading DAOs directly) plus [VehicleController]'s pure logic for what
 * counts as due/unknown. Voice (Zero) is the primary way to log service; this
 * tab is the hand-correction path, per Kevin's call.
 */
@Composable
private fun DueTab(context: Context) {
    val scope = rememberCoroutineScope()
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var items by remember { mutableStateOf<List<MaintenanceItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshResult by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MaintenanceItem?>(null) }

    LaunchedEffect(reload) {
        val v = VehicleController.currentVehicle(context)
        vehicle = v
        items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(v.obdMac)
        loaded = true
    }

    BinderPage("SEVERE-SERVICE SCHEDULE") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "severe-service schedule",
                style = AriaType.logbookBody.copy(fontSize = 12.sp),
                color = AriaColors.InkFaded,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (refreshing) "REFRESHING…" else "REFRESH SCHEDULE",
                style = AriaType.neonLabel,
                color = if (refreshing) AriaColors.TextDim else AriaColors.Patina,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !refreshing) {
                        val v = vehicle ?: return@clickable
                        refreshing = true
                        refreshResult = null
                        scope.launch {
                            val written = try {
                                VehicleController.refreshServiceIntervals(context, v)
                            } catch (e: Exception) {
                                MidnightEvents.maintenanceRefreshFailed(e)
                                -1
                            }
                            refreshing = false
                            refreshResult = when {
                                written > 0 -> "Updated $written item${if (written == 1) "" else "s"}."
                                written == 0 -> "No changes - the lookup didn't return anything new."
                                else -> "Couldn't reach the lookup. Check your connection and Gemini key, then try again."
                            }
                            reload++
                        }
                    }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            )
        }
        refreshResult?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = AriaType.logbookBody.copy(fontSize = 12.sp), color = AriaColors.InkFaded)
        }
        Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))

        if (!loaded) {
            Text("Loading…", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            return@BinderPage
        }

        if (items.isEmpty()) {
            Text(
                "No schedule yet. Register the car or hit REFRESH SCHEDULE to look one up.",
                style = AriaType.logbookBody, color = AriaColors.InkFaded,
            )
            return@BinderPage
        }

        val v = vehicle ?: return@BinderPage
        val mileage = VehicleController.currentMileage(v)
        val now = com.kevin.midnightai.time.TrustedClock.nowMs()
        val odometerUnset = v.odometerBaseline == 0

        val overdue = items.filter { VehicleController.isDue(it, mileage, now) }
        val unknown = items.filter { VehicleController.isUnknown(it) }
        val comingUp = items.filter { !VehicleController.isDue(it, mileage, now) && !VehicleController.isUnknown(it) }

        // Never-done items first (a known, actionable fact - see MaintenanceItem's
        // doc), then bucketed by which axis is over rather than cross-comparing
        // "miles over" against "months over" (see computeOverdueInfo's doc).
        val neverDone = overdue.filter { it.neverDone }.sortedBy { it.serviceName }
        val overdueInfos = overdue.filterNot { it.neverDone }.map { computeOverdueInfo(it, mileage, now) }
        val overdueByMiles = overdueInfos.filter { it.milesOver != null }.sortedByDescending { it.milesOver }
        val overdueByMonths = overdueInfos.filter { it.milesOver == null }.sortedByDescending { it.monthsOver ?: 0 }

        Text("OVERDUE", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.StampRed)
        Spacer(Modifier.height(8.dp))
        if (neverDone.isEmpty() && overdueByMiles.isEmpty() && overdueByMonths.isEmpty()) {
            Text("Nothing overdue.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
        } else {
            for (item in neverDone) {
                DueRow(item.serviceName, "never done", AriaColors.StampRed) { editing = item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
            // Labelled per-axis groups, NOT one concatenated list. Miles-over and
            // months-over cannot be ranked against each other without a
            // miles-per-day estimate, which is ruled out - and simply appending
            // one group after the other silently reads as urgency order, which
            // put an item 20 months overdue below one 50 miles over. Naming the
            // axis makes the incomparability visible instead of faking a rank.
            val splitOverdue = overdueByMiles.isNotEmpty() && overdueByMonths.isNotEmpty()
            if (splitOverdue) AxisLabel("by mileage")
            for (info in overdueByMiles) {
                DueRow(info.item.serviceName, overdueLine(info), AriaColors.StampRed) { editing = info.item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
            if (splitOverdue) AxisLabel("by time")
            for (info in overdueByMonths) {
                DueRow(info.item.serviceName, overdueLine(info), AriaColors.StampRed) { editing = info.item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("COMING UP", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.Patina)
        Spacer(Modifier.height(8.dp))
        if (odometerUnset) {
            Text(
                "Odometer isn't set, so mileage-based items can't be estimated yet - time-based items still show below.",
                style = AriaType.logbookBody.copy(fontSize = 12.sp), color = AriaColors.InkFaded,
            )
            Spacer(Modifier.height(6.dp))
        }
        val comingInfos = comingUp.map { computeComingInfo(it, mileage, now, odometerUnset) }
        val comingByMiles = comingInfos.filter { it.milesRemaining != null }.sortedBy { it.milesRemaining }
        val comingByDaysOnly = comingInfos.filter { it.milesRemaining == null && it.daysRemaining != null }.sortedBy { it.daysRemaining }
        val comingNoInterval = comingInfos.filter { it.milesRemaining == null && it.daysRemaining == null }
        if (comingByMiles.isEmpty() && comingByDaysOnly.isEmpty() && comingNoInterval.isEmpty()) {
            Text("Nothing further out.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
        } else {
            // Same per-axis split as OVERDUE above, for the same reason: appending
            // the time-only group after the mileage group rendered an inspection
            // due tomorrow below an oil change with 4,900 of 5,000 miles left.
            val splitComing = comingByMiles.isNotEmpty() && comingByDaysOnly.isNotEmpty()
            if (splitComing) AxisLabel("by mileage")
            for (info in comingByMiles) {
                DueRow(info.item.serviceName, comingUpLine(info), AriaColors.Patina) { editing = info.item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
            if (splitComing) AxisLabel("by time")
            for (info in comingByDaysOnly) {
                DueRow(info.item.serviceName, comingUpLine(info), AriaColors.Patina) { editing = info.item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
            for (info in comingNoInterval) {
                DueRow(info.item.serviceName, "no interval yet", AriaColors.InkFaded) { editing = info.item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("UNKNOWN", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
        Spacer(Modifier.height(8.dp))
        if (unknown.isEmpty()) {
            Text("Everything's accounted for.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
        } else {
            Text(
                "Uncle Moose can fill these in - just tell him when, or that it's never been done.",
                style = AriaType.logbookBody.copy(fontSize = 12.sp), color = AriaColors.InkFaded,
            )
            Spacer(Modifier.height(8.dp))
            for (item in unknown.sortedBy { it.serviceName }) {
                DueRow(item.serviceName, "—", AriaColors.InkFaded) { editing = item }
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
            }
        }
    }

    editing?.let { item ->
        MaintenanceEditDialog(
            item = item,
            currentMileage = vehicle?.let { VehicleController.currentMileage(it) } ?: 0,
            onDismiss = { editing = null },
            onSave = { mileageInput, dateInput, neverDoneInput ->
                scope.launch {
                    VehicleController.logPastServiceDirect(
                        context, item.serviceName,
                        mileage = mileageInput, date = dateInput, neverDone = neverDoneInput,
                    )
                    editing = null
                    reload++
                }
            },
            onClearToUnknown = {
                scope.launch {
                    CarDatabase.getDatabase(context).maintenanceItemDao().upsert(
                        item.copy(neverDone = false, lastDoneMileage = null, lastDoneDate = null)
                    )
                    editing = null
                    reload++
                }
            },
        )
    }
}

/** One tappable schedule row: service name + its state, in [stateColor]. */
@Composable
private fun DueRow(name: String, state: String, stateColor: Color, onClick: () -> Unit) {
    // Column, not Row: the state line runs to "2,900 miles or 90 days, whichever
    // comes first" on a dual-axis item, and as an unweighted sibling it claimed
    // its full intrinsic width first and squeezed a long service name into two
    // or three wrapped lines on a head unit. Stacked, each gets the full width.
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Text(name, style = AriaType.logbookBody, color = AriaColors.Ink)
        Text(state, style = AriaType.logbookBody.copy(fontSize = 12.sp), color = stateColor)
    }
}

/**
 * Sub-heading naming which axis the rows beneath it are ordered on. Shown only
 * when a section actually holds both kinds, because ordering across axes is not
 * possible without a miles-per-day estimate (ruled out) - so the label is what
 * stops a bare concatenation from reading as a single urgency ranking.
 */
@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = AriaType.logbookBody.copy(fontSize = 10.sp),
        color = AriaColors.InkFaded,
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * The hand-correction affordance: a mileage field, a date field (YYYY-MM-DD),
 * and a never-done toggle - voice is the primary path ([VehicleController.logPastServiceDirect]),
 * this dialog is the fallback. A separate CLEAR TO UNKNOWN action wipes both
 * anchors back to null - [VehicleController] has no such call (backfilling only
 * ever adds/replaces an anchor), so this writes the DAO directly, the same
 * convention MixTab/ModsTab already use for one-off DB edits from a screen.
 */
@Composable
private fun MaintenanceEditDialog(
    item: MaintenanceItem,
    currentMileage: Int,
    onDismiss: () -> Unit,
    onSave: (mileage: Int?, date: Long?, neverDone: Boolean) -> Unit,
    onClearToUnknown: () -> Unit,
) {
    var mileageText by remember { mutableStateOf(item.lastDoneMileage?.toString() ?: "") }
    var dateText by remember {
        mutableStateOf(item.lastDoneDate?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it)) } ?: "")
    }
    var neverDone by remember { mutableStateOf(item.neverDone) }

    val parsedDate = remember(dateText) {
        if (dateText.isBlank()) null else runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false }.parse(dateText)?.time
        }.getOrNull()
    }
    val dateError = dateText.isNotBlank() && parsedDate == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.serviceName) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverDone, onCheckedChange = { neverDone = it })
                    Text("Never been done", style = AriaType.logbookBody, color = AriaColors.Ink)
                }
                if (!neverDone) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mileageText,
                        onValueChange = { mileageText = it.filter(Char::isDigit) },
                        label = { Text("Last done at (mileage)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Last done on (YYYY-MM-DD)") },
                        singleLine = true,
                        isError = dateError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dateError) {
                        Text("Can't read that date - use YYYY-MM-DD.", style = AriaType.logbookBody.copy(fontSize = 11.sp), color = AriaColors.StampRed)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "CLEAR TO UNKNOWN",
                    style = AriaType.neonLabel,
                    color = AriaColors.TextDim,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onClearToUnknown() }.padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            val mileageValue = mileageText.toIntOrNull()
            val canSave = neverDone || mileageValue != null || (dateText.isNotBlank() && !dateError)
            Button(
                enabled = canSave,
                onClick = { onSave(mileageValue, if (neverDone) null else parsedDate, neverDone) },
            ) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}

/**
 * MIX tab: the mixtape manager - a city-pop desk with a drawer of imported
 * files (the general library) and a shelf of driver-assembled tapes built
 * from them. Sideloaded files (USB/SD) are imported once into the drawer;
 * removing a track from a tape only clears that tape's membership row, the
 * file and drawer entry survive until explicitly deleted from the drawer.
 * Playback goes through [MixtapePlayer], which is what makes a tape's cover
 * replace the cassette art on Cruise while it plays (CLAUDE.md sec 13).
 */
@Composable
private fun MixTab(context: Context) {
    val scope = rememberCoroutineScope()
    var mixtapes by remember { mutableStateOf<List<Mixtape>>(emptyList()) }
    var libraryTracks by remember { mutableStateOf<List<LibraryTrack>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var openMixtapeId by remember { mutableStateOf<Long?>(null) }
    var openTracks by remember { mutableStateOf<List<Pair<MixtapeTrack, LibraryTrack>>>(emptyList()) }
    var showNewTapeDialog by remember { mutableStateOf(false) }
    var newTapeName by remember { mutableStateOf("") }
    var pendingDeleteMixtape by remember { mutableStateOf<Mixtape?>(null) }
    var pendingDeleteTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    val activeMixtape by MixtapePlayer.activeMixtape.collectAsState()

    LaunchedEffect(reload) {
        val db = CarDatabase.getDatabase(context)
        mixtapes = db.mixtapeDao().getAll()
        libraryTracks = db.libraryTrackDao().getAll()
    }
    LaunchedEffect(openMixtapeId, reload) {
        openTracks = openMixtapeId?.let { tracksForMixtape(context, it) } ?: emptyList()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            MixtapeLibrary.importFiles(context, uris)
            reload++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, top = 64.dp, end = 8.dp, bottom = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.DeskWood)
            .border(1.dp, AriaColors.DeskWoodDark, RoundedCornerShape(6.dp))
            .padding(18.dp),
    ) {
        val id = openMixtapeId
        val tape = id?.let { openId -> mixtapes.firstOrNull { it.id == openId } }
        if (id == null || tape == null) {
            MixShelf(
                mixtapes = mixtapes,
                libraryCount = libraryTracks.size,
                onOpen = { openMixtapeId = it.id },
                onNewTape = { showNewTapeDialog = true },
                onImport = { importer.launch(arrayOf("audio/*")) },
                onDelete = { pendingDeleteMixtape = it },
                onPlay = { m -> scope.launch { MixtapePlayer.play(context, m.id) } },
            )
        } else {
            MixDetail(
                mixtape = tape,
                tracks = openTracks,
                isPlaying = activeMixtape?.id == id,
                onBack = { openMixtapeId = null },
                onPlay = { scope.launch { MixtapePlayer.play(context, id) } },
                onRemoveTrack = { libraryTrackId ->
                    scope.launch {
                        CarDatabase.getDatabase(context).mixtapeTrackDao().deleteMembership(id, libraryTrackId)
                        reload++
                    }
                },
                onAddFromLibrary = { showAddPicker = true },
            )
        }

        if (showAddPicker && id != null) {
            val onTape = openTracks.map { it.second.id }.toSet()
            LibraryPickerOverlay(
                libraryTracks = libraryTracks.filterNot { it.id in onTape },
                onPick = { track ->
                    scope.launch {
                        MixtapeLibrary.addToMixtape(CarDatabase.getDatabase(context), id, track.id)
                        reload++
                    }
                },
                onDeleteFromLibrary = { pendingDeleteTrack = it },
                onClose = { showAddPicker = false },
            )
        }
    }

    if (showNewTapeDialog) {
        AlertDialog(
            onDismissRequest = { showNewTapeDialog = false },
            title = { Text("Name this tape") },
            text = {
                OutlinedTextField(value = newTapeName, onValueChange = { newTapeName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newTapeName.trim()
                    showNewTapeDialog = false
                    newTapeName = ""
                    if (name.isNotEmpty()) scope.launch {
                        MixtapeLibrary.createMixtape(context, name)
                        reload++
                    }
                }) { Text("CREATE") }
            },
            dismissButton = { TextButton(onClick = { showNewTapeDialog = false }) { Text("CANCEL") } },
        )
    }

    pendingDeleteMixtape?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMixtape = null },
            title = { Text("Delete \"${m.name}\"?") },
            text = { Text("The tape goes away. Its tracks stay in the drawer.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteMixtape = null
                    scope.launch {
                        MixtapeLibrary.deleteMixtape(context, m.id)
                        if (openMixtapeId == m.id) openMixtapeId = null
                        reload++
                    }
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteMixtape = null }) { Text("CANCEL") } },
        )
    }

    pendingDeleteTrack?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTrack = null },
            title = { Text("Remove \"${t.title}\" from the drawer?") },
            text = { Text("This deletes the file and takes it off every tape.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteTrack = null
                    scope.launch {
                        MixtapeLibrary.deleteFromLibrary(context, t.id)
                        reload++
                    }
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTrack = null }) { Text("CANCEL") } },
        )
    }
}

private suspend fun tracksForMixtape(context: Context, mixtapeId: Long): List<Pair<MixtapeTrack, LibraryTrack>> {
    val db = CarDatabase.getDatabase(context)
    return db.mixtapeTrackDao().forMixtape(mixtapeId)
        .mapNotNull { m -> db.libraryTrackDao().getById(m.libraryTrackId)?.let { m to it } }
}

/** The shelf: driver-assembled tapes, plus entry points to create one and to import files. */
@Composable
private fun MixShelf(
    mixtapes: List<Mixtape>,
    libraryCount: Int,
    onOpen: (Mixtape) -> Unit,
    onNewTape: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Mixtape) -> Unit,
    onPlay: (Mixtape) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("MIXTAPES", style = AriaType.stamp, color = AriaColors.PaperMasthead)
        Spacer(Modifier.height(4.dp))
        Text(
            "$libraryCount file${if (libraryCount == 1) "" else "s"} in the drawer",
            style = AriaType.logbookBody.copy(fontSize = 12.sp),
            color = AriaColors.PaperMasthead.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNewTape) { Text("+ NEW TAPE") }
            Button(onClick = onImport) { Text("IMPORT FROM USB") }
        }
        Spacer(Modifier.height(16.dp))
        if (mixtapes.isEmpty()) {
            Text(
                "No tapes yet. Import some files, then build your first one.",
                style = AriaType.logbookBody,
                color = AriaColors.PaperMasthead.copy(alpha = 0.8f),
            )
        } else {
            for (m in mixtapes) {
                MixtapeCard(m, onOpen = { onOpen(m) }, onDelete = { onDelete(m) }, onPlay = { onPlay(m) })
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun MixtapeCard(mixtape: Mixtape, onOpen: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit) {
    // Loaded straight off disk (not via AvatarStudio) so the manager UI doesn't
    // depend on the cover-generation feature landing first - once a cover
    // exists at this path, it just starts showing up here.
    val cover = remember(mixtape.coverImagePath) {
        mixtape.coverImagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Paper)
            .clickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(AriaColors.PaperAged),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("▤", style = AriaType.stamp.copy(fontSize = 22.sp), color = AriaColors.InkFaded)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(mixtape.name, style = AriaType.logbookHeading.copy(fontSize = 16.sp), color = AriaColors.Ink, modifier = Modifier.weight(1f))
        Text(
            "▶", style = AriaType.stamp.copy(fontSize = 18.sp), color = AriaColors.Patina,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPlay() }.padding(8.dp),
        )
        Text(
            "🗑", style = AriaType.stamp.copy(fontSize = 14.sp), color = AriaColors.StampRed,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onDelete() }.padding(8.dp),
        )
    }
}

/** An open tape: its ordered tracklist, play, and remove-from-tape (keeps the drawer copy). */
@Composable
private fun MixDetail(
    mixtape: Mixtape,
    tracks: List<Pair<MixtapeTrack, LibraryTrack>>,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onRemoveTrack: (Long) -> Unit,
    onAddFromLibrary: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "‹ TAPES",
            style = AriaType.neonLabel,
            color = AriaColors.PaperMasthead,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onBack() }.padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                mixtape.name,
                style = AriaType.stamp.copy(fontSize = 20.sp),
                color = AriaColors.PaperMasthead,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onPlay) { Text(if (isPlaying) "PLAYING" else "▶ PLAY") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${tracks.size} track${if (tracks.size == 1) "" else "s"}",
            style = AriaType.logbookBody.copy(fontSize = 12.sp),
            color = AriaColors.PaperMasthead.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAddFromLibrary) { Text("+ ADD FROM DRAWER") }
        Spacer(Modifier.height(12.dp))
        if (tracks.isEmpty()) {
            Text(
                "Nothing on this tape yet.",
                style = AriaType.logbookBody,
                color = AriaColors.PaperMasthead.copy(alpha = 0.8f),
            )
        } else {
            for ((_, track) in tracks) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(AriaColors.Paper).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = AriaType.logbookBody, color = AriaColors.Ink)
                        if (track.artist.isNotBlank()) {
                            Text(track.artist, style = AriaType.logbookBody.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
                        }
                    }
                    Text(
                        "✕", style = AriaType.stamp.copy(fontSize = 14.sp), color = AriaColors.StampRed,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onRemoveTrack(track.id) }.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** Frame-clock slide-up (Motion.kt - see the DTC sheet in CruiseScreen for the same pattern). */
@Composable
private fun LibraryPickerOverlay(
    libraryTracks: List<LibraryTrack>,
    onPick: (LibraryTrack) -> Unit,
    onDeleteFromLibrary: (LibraryTrack) -> Unit,
    onClose: () -> Unit,
) {
    val enter = rememberRamp(220)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = size.height * (1f - enter)
                alpha = enter
            }
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Paper)
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("THE DRAWER", style = AriaType.stamp, color = AriaColors.Ink, modifier = Modifier.weight(1f))
                Text(
                    "CLOSE",
                    style = AriaType.neonLabel,
                    color = AriaColors.StampRed,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onClose() }.padding(6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (libraryTracks.isEmpty()) {
                    Text(
                        "Nothing left to add - every file in the drawer is already on this tape.",
                        style = AriaType.logbookBody,
                        color = AriaColors.InkFaded,
                    )
                } else {
                    for (t in libraryTracks) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(t) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = AriaType.logbookBody, color = AriaColors.Ink)
                                if (t.artist.isNotBlank()) {
                                    Text(t.artist, style = AriaType.logbookBody.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
                                }
                            }
                            Text(
                                "🗑",
                                style = AriaType.stamp.copy(fontSize = 13.sp),
                                color = AriaColors.StampRed,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onDeleteFromLibrary(t) }.padding(8.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

/**
 * SPECS tab: the build-details encyclopedia. VIN-decoded factory specs (engine,
 * drivetrain, body, plant), driver-entered paint/notes, and an on-request recall
 * check. Populated by decoding the VIN off the OBD port or a typed VIN.
 */
@Composable
private fun SpecsTab(context: Context) {
    val scope = rememberCoroutineScope()
    var spec by remember { mutableStateOf<VehicleSpec?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var obdConnected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var vinInput by remember { mutableStateOf("") }

    // Driver-entered fields (VIN can't supply factory paint).
    var paintColor by remember { mutableStateOf("") }
    var paintCode by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    var recalls by remember { mutableStateOf<List<VinDecoder.Recall>?>(null) }
    var checkingRecalls by remember { mutableStateOf(false) }
    var carConfirmed by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        obdConnected = ObdBluetoothManager.isConnected
        val s = VehicleSpecController.current(context)
        spec = s
        paintColor = s?.paintColor ?: ""
        paintCode = s?.paintCode ?: ""
        notes = s?.buildNotes ?: ""
        carConfirmed = VehicleController.isConfirmed(context)
        loaded = true
    }

    BinderPage("BUILD DETAILS") {
        if (!loaded) {
            Text("Loading…", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            return@BinderPage
        }

        // --- Populate controls ---
        if (obdConnected) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = VehicleSpecController.refreshFromObd(context)
                        busy = false
                        if (ok) reload++
                    }
                },
            ) { Text(if (busy) "Reading VIN…" else "Decode from car (OBD)") }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vinInput,
                onValueChange = { vinInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
                label = { Text("VIN (17 chars)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = vinInput.length == 17 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        VehicleSpecController.refreshFromVin(context, vinInput)
                        busy = false
                        reload++
                    }
                },
            ) { Text("DECODE") }
        }

        Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))

        // --- Decoded specs ---
        val s = spec
        if (s == null || s.decodedAt == 0L) {
            Text(
                "No specs decoded yet. Decode the VIN from the car or type it above. " +
                    "(Factory paint isn't in the VIN — add it below.)",
                style = AriaType.logbookBody, color = AriaColors.InkFaded,
            )
        } else {
            SpecRow("Engine", engineSummary(s))
            SpecRow("Horsepower", s.engineHp?.let { "$it hp" })
            SpecRow("Transmission", joinParts(s.transmissionSpeeds, s.transmissionStyle))
            SpecRow("Drivetrain", s.driveType)
            SpecRow("Fuel", s.fuelType)
            SpecRow("Body", joinParts(s.bodyClass, s.doors?.let { "$it-door" }))
            SpecRow("Series", s.series)
            SpecRow("Built in", joinParts(s.plantCity, s.plantCountry, sep = ", "))
            SpecRow("Manufacturer", s.manufacturer)
            SpecRow("VIN", s.vin)
        }

        Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))

        // --- Manual paint & notes ---
        Text("PAINT & NOTES (YOURS)", style = AriaType.stamp.copy(fontSize = 12.sp), color = AriaColors.Patina)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = paintColor, onValueChange = { paintColor = it },
            label = { Text("Factory paint color") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = paintCode, onValueChange = { paintCode = it },
            label = { Text("Paint code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = notes, onValueChange = { notes = it },
            label = { Text("Build notes") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch { VehicleSpecController.saveManual(context, paintColor, paintCode, notes); reload++ }
        }) { Text("SAVE") }

        Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))

        // --- Recalls (on request) ---
        // Recalls are keyed by year/make/model; without a confirmed car that would
        // be the default mascot seed (1998 Jeep Cherokee), so gate the lookup until
        // the driver has set the car's identity.
        Button(
            enabled = !checkingRecalls && carConfirmed,
            onClick = {
                checkingRecalls = true
                scope.launch { recalls = VehicleSpecController.recalls(context); checkingRecalls = false }
            },
        ) { Text(if (checkingRecalls) "Checking…" else "Check recalls") }
        if (!carConfirmed) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Set this car's year, make, and model first (AI Profile, or tell Uncle Moose) to check recalls.",
                style = AriaType.logbookBody, color = AriaColors.InkFaded,
            )
        }
        recalls?.let { rs ->
            Spacer(Modifier.height(8.dp))
            if (rs.isEmpty()) {
                Text("No open recalls found.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            } else {
                for (r in rs) {
                    Text(r.component.ifBlank { "Recall ${r.campaign}" },
                        style = AriaType.logbookHeading.copy(fontSize = 15.sp), color = AriaColors.StampRed)
                    if (r.summary.isNotBlank()) {
                        Text(r.summary, style = AriaType.logbookBody.copy(fontSize = 13.sp), color = AriaColors.Ink)
                    }
                    Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** One labeled spec line; renders nothing when the value is blank. */
@Composable
private fun SpecRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label.uppercase(),
            style = AriaType.stamp.copy(fontSize = 11.sp),
            color = AriaColors.Patina,
            modifier = Modifier.width(118.dp),
        )
        Text(value, style = AriaType.logbookBody, color = AriaColors.Ink, modifier = Modifier.weight(1f))
    }
}

/** "6 cyl · 3.0L · Inline" from whichever engine fields decoded. */
private fun engineSummary(s: VehicleSpec): String? = listOfNotNull(
    s.engineCylinders?.let { "$it cyl" },
    s.displacementL?.let { "${it}L" },
    s.engineConfig.ifBlank { null },
).joinToString(" · ").ifBlank { null }

/** Joins non-blank parts with [sep]; null if nothing. */
private fun joinParts(vararg parts: String?, sep: String = " "): String? =
    parts.filterNot { it.isNullOrBlank() }.joinToString(sep).ifBlank { null }

/** A paper-friendly "type + ADD" row used by the MODS tab. */
@Composable
private fun AddRow(value: String, onValue: (String) -> Unit, hint: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(hint) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAdd, enabled = value.isNotBlank()) { Text("ADD") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Flipbook(items: List<LogItem>, title: String, carImage: Bitmap?, carLabel: String, unlocked: Boolean) {
    val pages = items.chunked(ENTRIES_PER_PAGE)
    val pageCount = 1 + pages.size // cover + sheets
    val pager = rememberPagerState(pageCount = { pageCount })

    HorizontalPager(
        state = pager,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 64.dp),
    ) { page ->
        // Subtle page-flip: pages tilt and fade as they swipe past.
        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
        val leaf = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = offset * 14f
                cameraDistance = 14f * density
                alpha = 1f - abs(offset) * 0.25f
            }
        if (page == 0) {
            CoverPage(title, items.size, carImage, carLabel, leaf)
        } else {
            SheetPage(pages[page - 1], page, pages.size, unlocked, leaf)
        }
    }
}

/**
 * Album-cover styled cover (per the FM STATION / Eizin Suzuki reference): a colored
 * spine, a white header band with a blocky LOG/BOOK logo, a polaroid of the car in
 * the night-blue body, and a footer line.
 */
@Composable
private fun CoverPage(title: String, count: Int, carImage: Bitmap?, carLabel: String, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Background) // deep night-blue cover
            .border(2.dp, AriaColors.ChromeShadow, RoundedCornerShape(8.dp)),
    ) {
        // Colored album spine.
        Box(Modifier.fillMaxHeight().width(16.dp).background(AriaColors.StampRed))

        Column(Modifier.fillMaxSize()) {
            // White header band with the blocky two-tone logo (LOG | BOOK).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AriaColors.PaperMasthead)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoBlock("LOG", AriaColors.StampRed)
                LogoBlock("BOOK", AriaColors.Ink)
                Spacer(Modifier.weight(1f))
                Text("NIGHTTIME ED.", style = AriaType.stamp.copy(fontSize = 10.sp), color = AriaColors.Ink)
            }

            // Body: the car polaroid over the night cover.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (carImage != null) {
                    Polaroid(carImage, carLabel.ifBlank { title })
                    Spacer(Modifier.height(18.dp))
                }
                Text(
                    title,
                    style = AriaType.logbookHeading.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    color = AriaColors.Paper,
                )
                Text("Service & build history", style = AriaType.logbookBody, color = AriaColors.PaperAged)
            }

            // Footer band.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (count == 0) "no entries yet" else "$count entries",
                    style = AriaType.stamp.copy(fontSize = 11.sp),
                    color = AriaColors.PaperAged,
                )
                Text(
                    if (count == 0) "say \"log a new clutch\" to begin" else "swipe to begin →",
                    style = AriaType.stamp.copy(fontSize = 11.sp),
                    color = AriaColors.SunsetGold,
                )
            }
        }
    }
}

/** One bold two-tone logo block (e.g. red "LOG", dark "BOOK"). */
@Composable
private fun LogoBlock(text: String, bg: Color) {
    Text(
        text,
        style = AriaType.stamp.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier.background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A photo in a slightly-tilted white polaroid frame with an optional caption. */
@Composable
private fun Polaroid(image: Bitmap, caption: String, size: androidx.compose.ui.unit.Dp = 168.dp) {
    Column(
        modifier = Modifier
            .graphicsLayer { rotationZ = -3f }
            .background(AriaColors.PaperMasthead)
            .border(1.dp, AriaColors.PaperAged)
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = caption.ifBlank { "Photo" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(2.dp)),
        )
        if (caption.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                caption.uppercase(),
                style = AriaType.stamp.copy(fontSize = 11.sp),
                color = AriaColors.Ink,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

// --- ALBUM tab: every photo in the car's life, on one aged-paper spread ------

private data class AlbumPhoto(val path: String, val caption: String, val deletable: Boolean)

/** Gathers album photos + build-entry photos + the car/wallpaper images. */
private suspend fun collectAlbum(context: Context): List<AlbumPhoto> = withContext(Dispatchers.IO) {
    val out = mutableListOf<AlbumPhoto>()
    for (f in AvatarStudio.listAlbumPhotos(context)) {
        val stamp = f.name.removeSuffix(".png").toLongOrNull()
        out += AlbumPhoto(f.absolutePath, stamp?.let { shortDate(it) } ?: "", deletable = true)
    }
    for (e in BuildSheetController.history(context)) {
        if (e.photoPath.isNotBlank() && java.io.File(e.photoPath).exists()) {
            out += AlbumPhoto(e.photoPath, e.title, deletable = false)
        }
    }
    for ((name, caption) in listOf(
        "car/photo.png" to "THE CAR",
        "car/portrait.png" to "PORTRAIT",
        "background/companion_bg.png" to "WALLPAPER",
    )) {
        val f = java.io.File(context.filesDir, name)
        if (f.exists()) out += AlbumPhoto(f.absolutePath, caption, deletable = false)
    }
    out
}

private data class AlbumCardData(val album: PhotoAlbum, val coverPath: String?, val count: Int)

/**
 * ALBUM tab: a shelf of driver-made albums (trips, eras), plus an ALL PHOTOS
 * card for the flat collection (build shots, anything added). Albums are
 * filesystem-backed ([PhotoAlbumStore]). Cover art was AI-generated before
 * 2026-09-27; an existing album's cover still shows if one was saved, but
 * nothing generates a new one any more.
 */
@Composable
private fun AlbumTab(context: Context) {
    var reload by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<List<AlbumCardData>>(emptyList()) }
    var openAlbumId by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        cards = withContext(Dispatchers.IO) {
            PhotoAlbumStore.list(context).map {
                AlbumCardData(
                    it,
                    PhotoAlbumStore.coverFile(context, it.id).takeIf { f -> f.exists() }?.absolutePath,
                    PhotoAlbumStore.photoCount(context, it.id),
                )
            }
        }
    }

    when {
        showAll -> AllPhotosSpread(context, onBack = { showAll = false })
        openAlbumId != null -> AlbumDetail(context, openAlbumId!!, onBack = { openAlbumId = null; reload++ })
        else -> AlbumShelf(
            context = context,
            cards = cards,
            onOpenAll = { showAll = true },
            onOpenAlbum = { openAlbumId = it },
            onChanged = { reload++ },
        )
    }
}

/** The shelf: a "new album" action, an ALL PHOTOS card, then one card per album. */
@Composable
private fun AlbumShelf(
    context: Context,
    cards: List<AlbumCardData>,
    onOpenAll: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onChanged: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    val perRow = if (LocalSizeClass.current == SizeClass.COMPACT) 2 else 3

    BinderPage("PHOTO ALBUMS") {
        Text(
            "Group your photos into trips and eras, each with its own cover. Or open ALL PHOTOS " +
                "for build shots, the wallpaper, and anything you've added.",
            style = AriaType.logbookBody.copy(fontSize = 13.sp),
            color = AriaColors.InkFaded,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { naming = true }) { Text("+ NEW ALBUM") }
        Spacer(Modifier.height(16.dp))

        // ALL PHOTOS first, then each album; wrapped into rows.
        val entries = listOf<AlbumCardData?>(null) + cards
        for (row in entries.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (entry in row) {
                    if (entry == null) {
                        AlbumCard(coverPath = null, name = "ALL PHOTOS", count = null, onClick = onOpenAll)
                    } else {
                        AlbumCard(entry.coverPath, entry.album.name, entry.count) { onOpenAlbum(entry.album.id) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (naming) {
        AlbumNameDialog(
            title = "New album",
            initial = "",
            onConfirm = { name -> PhotoAlbumStore.create(context, name); naming = false; onChanged() },
            onDismiss = { naming = false },
        )
    }
}

/** A small polaroid-style card: cover (or a placeholder) + name + photo count. */
@Composable
private fun AlbumCard(coverPath: String?, name: String, count: Int?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(112.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .background(AriaColors.PaperMasthead)
                .border(1.dp, AriaColors.PaperAged)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = coverPath?.let { rememberScaledPhoto(it, 300) }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)),
                )
            } else {
                Text("◫", style = AriaType.stamp.copy(fontSize = 34.sp), color = AriaColors.InkFaded)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            name.uppercase(),
            style = AriaType.stamp.copy(fontSize = 11.sp),
            color = AriaColors.Ink,
            maxLines = 1,
        )
        count?.let {
            Text(
                "$it photo${if (it == 1) "" else "s"}",
                style = AriaType.logbookBody.copy(fontSize = 10.sp),
                color = AriaColors.InkFaded,
            )
        }
    }
}

/** One album's photo spread, with add / cover-art / rename / delete. */
@Composable
private fun AlbumDetail(context: Context, albumId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<PhotoAlbum?>(null) }
    var photos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var viewing by remember { mutableStateOf<AlbumPhoto?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val perRow = if (LocalSizeClass.current == SizeClass.COMPACT) 2 else 3

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            PhotoAlbumStore.list(context).firstOrNull { it.id == albumId } to
                PhotoAlbumStore.photos(context, albumId).map { AlbumPhoto(it.absolutePath, "", deletable = true) }
        }
        album = loaded.first
        photos = loaded.second
    }

    val a = album

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                bmp?.let { PhotoAlbumStore.addPhoto(context, albumId, it) }
            }
            reload++
        }
    }
    val camera = rememberCameraCaptureLauncher(context) { bmp ->
        scope.launch {
            withContext(Dispatchers.IO) { PhotoAlbumStore.addPhoto(context, albumId, bmp) }
            reload++
        }
    }

    Box(Modifier.fillMaxSize()) {
        BinderPage(a?.name?.uppercase() ?: "ALBUM") {
            Text(
                text = "‹ ALBUMS",
                style = AriaType.stamp.copy(fontSize = 12.sp),
                color = AriaColors.NeonMagenta,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("+ ADD PHOTO") }
                if (hasCamera(context)) {
                    Button(onClick = camera) { Text("TAKE PHOTO") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { renaming = true }) { Text("RENAME") }
                TextButton(onClick = { confirmDelete = true }) { Text("DELETE ALBUM") }
            }
            Spacer(Modifier.height(12.dp))
            if (photos.isEmpty()) {
                Text("No photos in this album yet.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            } else {
                for (row in photos.chunked(perRow)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (p in row) {
                            Box(Modifier.clickable { viewing = p }) { AlbumThumb(p) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        viewing?.let { photo ->
            AlbumViewer(
                context = context,
                photo = photo,
                albumId = albumId,
                onChanged = { reload++ },
                onClose = { viewing = null },
            )
        }
    }

    if (renaming && a != null) {
        AlbumNameDialog(
            title = "Rename album",
            initial = a.name,
            onConfirm = { name -> PhotoAlbumStore.rename(context, albumId, name); renaming = false; reload++ },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete album?") },
            text = { Text("This removes the album and its photos. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    PhotoAlbumStore.delete(context, albumId)
                    confirmDelete = false
                    onBack()
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun AlbumNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Album name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/** The flat "all photos" view: album files + build-entry photos + car/wallpaper images. */
@Composable
private fun AllPhotosSpread(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<AlbumPhoto?>(null) }
    LaunchedEffect(reload) { photos = collectAlbum(context) }
    val perRow = if (LocalSizeClass.current == SizeClass.COMPACT) 2 else 3

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                bmp?.let { AvatarStudio.saveAlbumPhoto(context, it) }
            }
            reload++
        }
    }
    val camera = rememberCameraCaptureLauncher(context) { bmp ->
        scope.launch {
            withContext(Dispatchers.IO) { AvatarStudio.saveAlbumPhoto(context, bmp) }
            reload++
        }
    }

    Box(Modifier.fillMaxSize()) {
        BinderPage("ALL PHOTOS") {
            Text(
                text = "‹ ALBUMS",
                style = AriaType.stamp.copy(fontSize = 12.sp),
                color = AriaColors.NeonMagenta,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Build shots, the wallpaper, and anything you add. Tap a photo to view it — " +
                    "or turn it into city-pop art.",
                style = AriaType.logbookBody.copy(fontSize = 13.sp),
                color = AriaColors.InkFaded,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("+ ADD PHOTO") }
                if (hasCamera(context)) {
                    Button(onClick = camera) { Text("TAKE PHOTO") }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (photos.isEmpty()) {
                Text("No photos yet.", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            } else {
                for (row in photos.chunked(perRow)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (p in row) {
                            Box(Modifier.clickable { viewing = p }) {
                                AlbumThumb(p)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        viewing?.let { photo ->
            AlbumViewer(
                context = context,
                photo = photo,
                onChanged = { reload++ },
                onClose = { viewing = null },
            )
        }
    }
}

/**
 * Recap cassettes (E5): a shelf of every past monthly recap, spine-out like
 * a bookshelf (Kevin's design vision, 2026-07-08) - pulling one off to
 * revisit it is meant to feel like a small occasion, not a plain list tap.
 * Notable months (a genuinely long drive, a rough patch with codes - see
 * [com.kevin.midnightai.vehicle.MonthlyRecapController]'s heuristics) get a
 * distinct spine color, surfacing the strategy doc's "rarity
 * common→legendary" concept in the archive itself rather than only at
 * generation time. Tapping a spine opens the full cassette: cover art (Side
 * A) with the real stats overlaid as text - never baked into the generated
 * image, which is unreliable at rendering legible numbers - and the
 * narrative below it (Side B).
 */
@Composable
private fun RecapTab(context: Context) {
    val scope = rememberCoroutineScope()
    var recaps by remember { mutableStateOf<List<MonthlyRecap>>(emptyList()) }
    var dailyLogs by remember { mutableStateOf<List<com.kevin.midnightai.data.local.DailyDriveLog>>(emptyList()) }
    var wrappedYears by remember { mutableStateOf<List<YearlyWrapped>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<MonthlyRecap?>(null) }
    var selectedDay by remember { mutableStateOf<com.kevin.midnightai.data.local.DailyDriveLog?>(null) }
    var wrapped by remember { mutableStateOf<YearlyWrapped?>(null) }
    var busy by remember { mutableStateOf(false) }
    val vehicleId = remember { com.kevin.midnightai.vehicle.ActiveVehicle.current(context) }

    LaunchedEffect(reload) {
        recaps = CarDatabase.getDatabase(context).monthlyRecapDao().getAll(vehicleId)
        dailyLogs = CarDatabase.getDatabase(context).dailyDriveLogDao().getRecent(vehicleId, 14)
        wrappedYears = CarDatabase.getDatabase(context).yearlyWrappedDao().getAll(vehicleId)
        loaded = true
    }

    val open = selected
    val year = wrapped
    val day = selectedDay
    if (open != null) {
        BinderPage("RECAP CASSETTE") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BackToShelfLink { selected = null }
                DeleteEntryAction("this recap") {
                    scope.launch {
                        CarDatabase.getDatabase(context).monthlyRecapDao().delete(open)
                        selected = null
                        reload++
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            RecapCassetteContent(open)
        }
        return
    }
    if (year != null) {
        BinderPage("${year.year} WRAPPED") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BackToShelfLink { wrapped = null }
                DeleteEntryAction("this Wrapped year") {
                    scope.launch {
                        CarDatabase.getDatabase(context).yearlyWrappedDao().delete(year)
                        wrapped = null
                        reload++
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            YearlyWrappedContent(year)
        }
        return
    }
    if (day != null) {
        BinderPage("DAILY LOG") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BackToShelfLink { selectedDay = null }
                DeleteEntryAction("this daily log") {
                    scope.launch {
                        CarDatabase.getDatabase(context).dailyDriveLogDao().delete(day)
                        selectedDay = null
                        reload++
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DailyLogContent(day)
        }
        return
    }

    BinderPage("RECAP CASSETTES") {
        if (!loaded) {
            Text("Loading…", style = AriaType.logbookBody, color = AriaColors.InkFaded)
            return@BinderPage
        }

        // Daily logs: single-colored flat chips, no cover art - a quick note,
        // not an occasion the way the monthly/yearly tiers below are.
        if (dailyLogs.isNotEmpty()) {
            Text("DAILY LOGS", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dailyLogs.forEach { l -> DailyLogChip(l, onClick = { selectedDay = l }) }
            }
            Spacer(Modifier.height(18.dp))
        }

        if (recaps.isEmpty()) {
            Text(
                "Your first recap arrives shortly after this car's first full month of driving.",
                style = AriaType.logbookBody, color = AriaColors.InkFaded,
            )
        } else {
            Text(
                "Pull one off the shelf to revisit it.",
                style = AriaType.logbookBody.copy(fontSize = 13.sp),
                color = AriaColors.InkFaded,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recaps.forEach { r -> RecapSpine(r, onClick = { selected = r }) }
            }
        }

        // Yearly Wrapped: the richest tier, its own row below the monthly shelf.
        if (wrappedYears.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("WRAPPED", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                wrappedYears.forEach { w -> YearlyWrappedSpine(w, onClick = { wrapped = w }) }
            }
        }

        // Debug-only: fabricates real Gemini-generated recaps/wrapped/daily
        // logs with made-up stats, so all three tiers can be previewed
        // without waiting a day (or a month, or a year) of real driving data.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            Text("DEBUG", style = AriaType.stamp.copy(fontSize = 10.sp), color = AriaColors.InkFaded)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            com.kevin.midnightai.vehicle.DailyDriveLogController.generateFake(context, vehicleId)
                            reload++
                            busy = false
                        }
                    },
                ) { Text(if (busy) "GENERATING…" else "+ TEST DAILY") }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            com.kevin.midnightai.vehicle.MonthlyRecapController.generateFake(context, vehicleId)
                            reload++
                            busy = false
                        }
                    },
                ) { Text(if (busy) "GENERATING…" else "+ TEST RECAP") }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            wrapped = com.kevin.midnightai.vehicle.YearlyWrappedController.generateFake(
                                context, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
                            )
                            reload++
                            busy = false
                        }
                    },
                ) { Text(if (busy) "GENERATING…" else "+ TEST WRAPPED") }
            }
        }
    }
}

@Composable
private fun BackToShelfLink(onClick: () -> Unit) {
    Text(
        "‹ BACK TO SHELF",
        style = AriaType.neonLabel,
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    )
}

/**
 * A "DELETE" action requiring two separate confirmations before anything
 * happens (Kevin's explicit request, so a stray tap can't wipe an entry):
 * first tap arms it (the label flips to a red "TAP AGAIN TO DELETE" warning,
 * tapping elsewhere on the label re-arms rather than deletes); the second
 * tap, while armed, opens a final [AlertDialog] - only *its* Delete button
 * actually calls [onConfirmedDelete]. [itemLabel] names what's being deleted
 * in the dialog copy (e.g. "this recap").
 */
@Composable
private fun DeleteEntryAction(itemLabel: String, onConfirmedDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    Text(
        if (armed) "TAP AGAIN TO DELETE" else "DELETE",
        style = AriaType.neonLabel,
        color = if (armed) AriaColors.StampRed else AriaColors.TextDim,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable {
                if (armed) { showDialog = true; armed = false } else { armed = true }
            }
            .padding(vertical = 4.dp),
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Delete $itemLabel?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                Button(onClick = { showDialog = false; onConfirmedDelete() }) { Text("DELETE") }
            },
            dismissButton = {
                Text(
                    "CANCEL",
                    style = AriaType.neonLabel,
                    color = AriaColors.TextDim,
                    modifier = Modifier
                        .clickable { showDialog = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )
    }
}

/** Formats a recap's year/month as "JULY 2026" (full) or "JUL '26" (spine-width). */
private fun recapMonthLabel(recap: MonthlyRecap, abbreviated: Boolean): String {
    val date = java.util.Calendar.getInstance().apply { set(recap.year, recap.month - 1, 1) }.time
    val pattern = if (abbreviated) "MMM ''yy" else "MMMM yyyy"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(date)
}

/** Same rotate-the-measured-box trick TabRail uses: swap width/height constraints before
 *  measuring so a spine's label wraps against its true visual width (its height,
 *  pre-rotation) instead of the narrow cell width. Shared by all 3 shelf tiers. */
private fun Modifier.rotateSpineLabel(): Modifier = this
    .layout { measurable, constraints ->
        val rotated = constraints.copy(
            minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
        )
        val placeable = measurable.measure(rotated)
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2),
            )
        }
    }
    .graphicsLayer { rotationZ = -90f }

/**
 * One cassette spine on the shelf: a narrow vertical case with a rotated
 * label, the way a real cassette rack reads sideways. A dimmed peek of the
 * cover art shows behind the label - Kevin's call: monthly/yearly spines
 * "give the spine more detail and art" (daily logs stay flat single-colored
 * by contrast, see [DailyLogChip]). Notable months get an amber border
 * instead of the plain paper tone - the "rarity" signal, visible at a
 * glance while scanning the shelf.
 */
@Composable
private fun RecapSpine(recap: MonthlyRecap, onClick: () -> Unit) {
    val cover = remember(recap.coverImagePath) { recap.coverImagePath?.let { AvatarStudio.loadRecapCover(it) } }
    val edge = if (recap.notable) AriaColors.Amber else AriaColors.PanelBorder
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(190.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AriaColors.Panel)
            .border(if (recap.notable) 2.dp else 1.dp, edge, RoundedCornerShape(4.dp))
            .clickable { onClick() },
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        Text(
            text = "${recapMonthLabel(recap, abbreviated = true)}  ·  ${recap.milesDriven.toInt()} MI" +
                if (recap.notable) "  ★" else "",
            style = AriaType.stamp.copy(fontSize = 11.sp),
            color = if (recap.notable) AriaColors.Amber else AriaColors.TextPrimary,
            softWrap = true,
            modifier = Modifier.align(Alignment.Center).rotateSpineLabel(),
        )
    }
}

/**
 * One Yearly Wrapped spine - the richest of the 3 tiers, so it's sized up
 * from the monthly [RecapSpine] and always carries the amber "occasion"
 * border, not just on notable years.
 */
@Composable
private fun YearlyWrappedSpine(wrapped: YearlyWrapped, onClick: () -> Unit) {
    val cover = remember(wrapped.coverImagePath) { wrapped.coverImagePath?.let { AvatarStudio.loadRecapCover(it) } }
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AriaColors.Panel)
            .border(2.dp, AriaColors.Amber, RoundedCornerShape(4.dp))
            .clickable { onClick() },
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }
        Text(
            text = "${wrapped.year} WRAPPED  ·  ${wrapped.milesDriven.toInt()} MI",
            style = AriaType.stamp.copy(fontSize = 12.sp),
            color = AriaColors.Amber,
            softWrap = true,
            modifier = Modifier.align(Alignment.Center).rotateSpineLabel(),
        )
    }
}

/** The full cassette: cover art (Side A, stats overlaid) + narrative (Side B). Shared by the shelf's detail view. */
@Composable
private fun RecapCassetteContent(r: MonthlyRecap) {
    Text(
        recapMonthLabel(r, abbreviated = false).uppercase(),
        style = AriaType.stamp.copy(fontSize = 16.sp),
        color = AriaColors.InkFaded,
    )
    Spacer(Modifier.height(10.dp))

    // Side A: cover art with the real stats overlaid as Compose text.
    val cover = remember(r.coverImagePath) { r.coverImagePath?.let { AvatarStudio.loadRecapCover(it) } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Panel)
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp)),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "Recap cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(10.dp),
        ) {
            Text(
                "${r.milesDriven.toInt()} mi  ·  ${r.driveCount} drives" +
                    (r.avgMpg?.let { "  ·  %.1f mpg".format(it) } ?: ""),
                style = AriaType.neonLabel.copy(fontSize = 12.sp),
                color = AriaColors.TextPrimary,
            )
            if (r.codeEventCount > 0 || r.serviceCount > 0) {
                Text(
                    "${r.codeEventCount} codes  ·  ${r.serviceCount} services logged",
                    style = AriaType.neonLabel.copy(fontSize = 10.sp),
                    color = AriaColors.TextDim,
                )
            }
            if (r.notable && r.notableReason != null) {
                Text(
                    "★ ${r.notableReason}",
                    style = AriaType.neonLabel.copy(fontSize = 10.sp),
                    color = AriaColors.Amber,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    // Side B: the narrative.
    Text("SIDE B", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
    Spacer(Modifier.height(6.dp))
    Text(r.narrative, style = AriaType.logbookBody, color = AriaColors.Ink)
}

/**
 * Yearly Wrapped (Sprint 6, v0): the same cover-with-overlaid-stats + narrative
 * shape as [RecapCassetteContent], scaled up to a full year.
 * [com.kevin.midnightai.vehicle.YearlyWrappedController] aggregates this from
 * the year's monthly recaps and persists it (like the other 2 tiers) - poster
 * export and the rest of Sprint 6 come later.
 */
@Composable
private fun YearlyWrappedContent(w: YearlyWrapped) {
    val cover = remember(w.coverImagePath) { w.coverImagePath?.let { AvatarStudio.loadRecapCover(it) } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Panel)
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp)),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "Wrapped cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(10.dp),
        ) {
            Text(
                "${w.milesDriven.toInt()} mi  ·  ${w.driveCount} drives" +
                    (w.avgMpg?.let { "  ·  %.1f mpg".format(it) } ?: ""),
                style = AriaType.neonLabel.copy(fontSize = 13.sp),
                color = AriaColors.TextPrimary,
            )
            Text(
                "${w.notableMonths} notable months  ·  ${w.codeEventCount} codes  ·  ${w.serviceCount} services",
                style = AriaType.neonLabel.copy(fontSize = 10.sp),
                color = AriaColors.TextDim,
            )
            w.longestDriveMiles?.let {
                Text(
                    "Longest drive: ${it.toInt()} mi",
                    style = AriaType.neonLabel.copy(fontSize = 10.sp),
                    color = AriaColors.TextDim,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("SIDE B", style = AriaType.stamp.copy(fontSize = 11.sp), color = AriaColors.InkFaded)
    Spacer(Modifier.height(6.dp))
    Text(w.narrative, style = AriaType.logbookBody, color = AriaColors.Ink)
}

/**
 * One daily log's chip on the shelf: a small flat single-colored block (no
 * cover art, no rotated spine label) - "MM/DD", a star if notable. Kevin's
 * call: daily stays a quick note, not an occasion the way pulling a monthly
 * cassette off the shelf is.
 */
@Composable
private fun DailyLogChip(log: com.kevin.midnightai.data.local.DailyDriveLog, onClick: () -> Unit) {
    val fill = if (log.notable) AriaColors.Amber.copy(alpha = 0.28f) else AriaColors.Patina.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .width(58.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${log.month}/${log.day}" + if (log.notable) " ★" else "",
            style = AriaType.neonLabel.copy(fontSize = 10.sp),
            color = if (log.notable) AriaColors.Amber else AriaColors.InkFaded,
        )
    }
}

/** A daily log's detail: no cover art, just the stats line and its one-sentence narrative. */
@Composable
private fun DailyLogContent(log: com.kevin.midnightai.data.local.DailyDriveLog) {
    val dateLabel = remember(log.year, log.month, log.day) {
        val date = java.util.Calendar.getInstance().apply { set(log.year, log.month - 1, log.day) }.time
        java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(date)
    }
    Text(dateLabel.uppercase(), style = AriaType.stamp.copy(fontSize = 14.sp), color = AriaColors.InkFaded)
    Spacer(Modifier.height(10.dp))
    Text(
        "${log.milesDriven.toInt()} mi  ·  ${log.driveCount} drives" +
            (log.avgMpg?.let { "  ·  %.1f mpg".format(it) } ?: ""),
        style = AriaType.neonLabel.copy(fontSize = 13.sp),
        color = AriaColors.Ink,
    )
    if (log.codeEventCount > 0) {
        Text(
            "${log.codeEventCount} trouble codes",
            style = AriaType.neonLabel.copy(fontSize = 11.sp),
            color = AriaColors.TextDim,
        )
    }
    if (log.notable && log.notableReason != null) {
        Text("★ ${log.notableReason}", style = AriaType.neonLabel.copy(fontSize = 11.sp), color = AriaColors.Amber)
    }
    Spacer(Modifier.height(14.dp))
    Text(log.narrative, style = AriaType.logbookBody, color = AriaColors.Ink)
}

/** One polaroid thumbnail, decoded downsampled so a big album stays cheap. */
@Composable
private fun AlbumThumb(photo: AlbumPhoto) {
    val bmp = rememberScaledPhoto(photo.path, 300)
    if (bmp != null) Polaroid(bmp, photo.caption, size = 108.dp)
}

/**
 * Full-screen photo view over the binder: dark backdrop, the photo centered,
 * and DELETE/CLOSE for album-owned photos. STYLIZE PHOTO and SET AS WALLPAPER
 * were removed 2026-09-27 along with in-app image generation - the wallpaper
 * is now a fixed bundled asset (see [SeedArt]), so "set as wallpaper" would
 * have silently done nothing (SeedArt always wins over a per-car file).
 */
@Composable
private fun AlbumViewer(
    context: Context,
    photo: AlbumPhoto,
    albumId: String? = null,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val bmp = rememberScaledPhoto(photo.path, 1280)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable { onClose() },
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = photo.caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 56.dp),
            )
        }
        if (photo.caption.isNotBlank()) {
            Text(
                photo.caption.uppercase(),
                style = AriaType.stamp,
                color = AriaColors.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (photo.deletable) {
                ViewerAction("DELETE", enabled = true) {
                    if (albumId != null) PhotoAlbumStore.deletePhoto(context, albumId, photo.path)
                    else AvatarStudio.deleteAlbumPhoto(context, photo.path)
                    onChanged()
                    onClose()
                }
            }
            ViewerAction("CLOSE", enabled = true) { onClose() }
        }
    }
}

@Composable
private fun ViewerAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel,
        color = if (enabled) AriaColors.Amber else AriaColors.TextDim,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Loads a photo downsampled to roughly [targetPx] on its longest side. */
@Composable
private fun rememberScaledPhoto(path: String, targetPx: Int): Bitmap? =
    androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / targetPx)
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = scale })
            }.getOrNull()
        }
    }.value

/** Loads a logbook-entry photo off the main thread; null until ready / if none. */
@Composable
private fun rememberPhoto(path: String): Bitmap? =
    androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = if (path.isBlank()) null else withContext(Dispatchers.IO) { AvatarStudio.loadPhoto(path) }
    }.value

@Composable
private fun SheetPage(entries: List<LogItem>, pageNum: Int, totalPages: Int, unlocked: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Paper)
            .border(1.dp, AriaColors.PaperAged, RoundedCornerShape(6.dp)),
    ) {
        BinderRings()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 6.dp, top = 18.dp, end = 18.dp, bottom = 14.dp),
        ) {
            Text("SERVICE & BUILD LOG", style = AriaType.stamp, color = AriaColors.InkFaded)
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(6.dp))
            for (e in entries) {
                LogEntryRow(e, unlocked)
                Spacer(Modifier.height(6.dp))
                Divider()
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "page $pageNum of $totalPages",
                style = AriaType.stamp.copy(fontSize = 11.sp),
                color = AriaColors.InkFaded,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun LogEntryRow(e: LogItem, unlocked: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = AriaType.logbookHeading.copy(fontSize = 17.sp), color = AriaColors.Ink)
            val sub = buildString {
                append(e.category.uppercase())
                e.mileage?.let { append("  ·  ${"%,d".format(it)} mi") }
            }
            Text(sub, style = AriaType.stamp.copy(fontSize = 11.sp), color = categoryColor(e.category))
            if (e.detail.isNotBlank()) {
                Text(e.detail, style = AriaType.logbookBody.copy(fontSize = 13.sp), color = AriaColors.InkFaded)
            }
            if (e.photoPath.isNotBlank()) {
                rememberPhoto(e.photoPath)?.let {
                    Spacer(Modifier.height(6.dp))
                    Polaroid(it, caption = "", size = 72.dp)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(dateStamp(e.date), style = AriaType.stamp.copy(fontSize = 12.sp), color = AriaColors.StampRed)
            e.cost?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (unlocked) money(it) else "🔒",
                    style = AriaType.logbookBody.copy(fontWeight = FontWeight.Bold),
                    color = if (unlocked) AriaColors.Ink else AriaColors.InkFaded,
                )
            }
        }
    }
}

/** The chrome ring-binder rail down the left edge. */
@Composable
private fun BinderRings() {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(26.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(AriaColors.Chrome)
                    .border(1.dp, AriaColors.ChromeShadow, CircleShape),
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AriaColors.PaperAged),
    )
}

@Composable
private fun CenterNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = AriaType.logbookBody, color = color)
    }
}

private fun categoryColor(category: String): androidx.compose.ui.graphics.Color = when (category.lowercase()) {
    "maintenance" -> AriaColors.Patina
    "mod" -> AriaColors.StampRed
    "repair" -> AriaColors.SunsetOrange
    else -> AriaColors.InkFaded
}

private fun money(amount: Double): String =
    if (amount == amount.toLong().toDouble()) "$${"%,d".format(amount.toLong())}"
    else "$${"%,.2f".format(amount)}"

private fun dateStamp(epochMs: Long): String = shortDate(epochMs)

private fun logbookTitle(context: Context): String {
    val name = CompanionProfile.name(context)
    return if (name.isNotBlank()) name else "My Car"
}

private suspend fun loadLogbook(context: Context): List<LogItem> = withContext(Dispatchers.IO) {
    val db = CarDatabase.getDatabase(context)
    val vehicleId = VehicleController.currentVehicle(context).obdMac
    val services = db.serviceRecordDao().getRecentForVehicle(vehicleId, 500).map {
        LogItem(it.date, it.serviceName, "maintenance", it.mileage.takeIf { m -> m > 0 }, it.cost, "")
    }
    val builds = db.buildEntryDao().getForVehicle(vehicleId).map {
        LogItem(
            date = it.date,
            title = it.title,
            category = it.type,
            mileage = it.mileage,
            cost = it.cost,
            detail = listOfNotNull(it.vendor.ifBlank { null }, it.notes.ifBlank { null }).joinToString("  ·  "),
            photoPath = it.photoPath,
        )
    }
    (services + builds).sortedBy { it.date }
}
