package com.kevin.legion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.apps.CategoryPicks
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.filterDrawer
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/*
 * The two sheets behind HOME's category buttons (home-launcher ticket 07): "Open with" when several
 * apps are picked, and the chooser. Split from CategoryRow.kt so neither file is a grab-bag.
 */

/** A dimmed row: the same figure the category buttons and the dock use for "still here, not live". */
private const val CATEGORY_DIMMED_ALPHA = 0.5f

/** The status word a pick that cannot open carries, or null when it can. */
internal fun slotStatusWord(slot: DockSlotUi): String? = when {
    slot.loading -> "Loading"
    slot.app == null -> "Not installed"
    slot.paused -> "Paused"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenWithSheet(
    ui: CategoryUi,
    onDismiss: () -> Unit,
    onLaunch: (DockSlotUi) -> Unit,
    onChoose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        OpenWithSheetContent(ui = ui, onLaunch = onLaunch, onChoose = onChoose)
    }
}

/** The sheet body, split from its window so a screenshot can draw it directly. */
@Composable
internal fun OpenWithSheetContent(ui: CategoryUi, onLaunch: (DockSlotUi) -> Unit, onChoose: () -> Unit) {
    val look = lookOf(ui.category)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(look.tile), contentAlignment = Alignment.Center) {
                MsIcon(res = look.icon, contentDescription = null, tint = look.glyph, size = 20.dp)
            }
            Text(ui.category.title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
            Box(Modifier.weight(1f))
            Text("Open with", style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
        ui.slots.forEach { slot -> OpenWithRow(slot, onClick = { onLaunch(slot) }) }
        OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Choose apps")
        }
    }
}

@Composable
private fun OpenWithRow(slot: DockSlotUi, onClick: () -> Unit) {
    val status = slotStatusWord(slot)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .alpha(if (status != null) CATEGORY_DIMMED_ALPHA else 1f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppGlyph(slot.icon, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    slot.loading -> "Loading your apps"
                    slot.app == null -> "Not installed"
                    else -> slot.label
                },
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            workWords(slot.app, slot.paused)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftColors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** WORK / WORK - PAUSED under a label, in words, for a work-profile app; null for a personal one. */
private fun workWords(app: DrawerApp?, paused: Boolean): String? = when {
    app?.isWork != true -> null
    paused -> "WORK - PAUSED"
    else -> "WORK"
}

@Composable
private fun AppGlyph(icon: ImageBitmap?, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(SoftColors.cardHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size - GLYPH_INSET))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChooserSheet(
    ui: CategoryUi,
    rows: List<ChooserRow>,
    onDismiss: () -> Unit,
    onSave: (List<DockPin>) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        var working by remember { mutableStateOf(ui.slots.map { it.pin }) }
        ChooserSheetContent(
            category = ui.category,
            original = ui.slots,
            working = working,
            rows = rows,
            onToggle = { working = CategoryPicks.toggle(working, it) },
            onCancel = onDismiss,
            onSave = { onSave(working) },
            modifier = Modifier.fillMaxHeight(CHOOSER_HEIGHT_FRACTION),
        )
    }
}

private const val CHOOSER_HEIGHT_FRACTION = 0.85f

/** How far an app icon sits inside its rounded square. */
private val GLYPH_INSET = 4.dp

/** The search field and the checkbox list under it; owns the query so typing never recomposes the
 * title or the Save row. The list takes the sheet's remaining height. */
@Composable
private fun ColumnScope.ChooserSearchAndList(
    rows: List<ChooserRow>,
    original: List<DockSlotUi>,
    working: List<DockPin>,
    onToggle: (DockPin) -> Unit,
    initialQuery: String,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val byApp = remember(rows) { rows.associateBy { it.app } }
    val shown = remember(rows, query) { filterDrawer(rows.map { it.app }, query) }
    val gone = original.filter { it.app == null && !it.loading }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search apps") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (query.isBlank()) {
            items(gone, key = { "gone/${it.pin.packageName}/${it.pin.userSerial}" }) { slot ->
                ChooserRowItem(
                    label = slot.pin.packageName,
                    sub = "Not installed",
                    icon = null,
                    checked = slot.pin in working,
                    dimmed = true,
                    onToggle = { onToggle(slot.pin) },
                )
            }
        }
        items(shown, key = { "${it.profileKey}/${it.packageName}/${it.className}" }) { app ->
            val pin = DockPin(app.packageName, app.profileKey)
            ChooserRowItem(
                label = app.label,
                sub = workWords(app, paused = false),
                icon = byApp[app]?.icon,
                checked = pin in working,
                dimmed = false,
                onToggle = { onToggle(pin) },
            )
        }
    }
}

/**
 * The chooser body: title, the one-line explanation, a search field, every drawer app as a checkbox
 * row, Cancel and Save. A pick that is no longer installed has no drawer row, so it is listed first,
 * ticked and dimmed "Not installed" - unticking it is the only way it leaves, never a silent drop on
 * Save. [working] is the live tick set; [original] is what was saved when the sheet opened.
 */
@Composable
internal fun ChooserSheetContent(
    category: HomeCategory,
    original: List<DockSlotUi>,
    working: List<DockPin>,
    rows: List<ChooserRow>,
    onToggle: (DockPin) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            Text("Apps for ${category.title}", style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
            Text(
                "Pick one or more. One app opens straight away; more than one asks which.",
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ChooserSearchAndList(
            rows = rows,
            original = original,
            working = working,
            onToggle = onToggle,
            initialQuery = initialQuery,
        )
        if (working.isEmpty()) {
            Text(
                "Nothing ticked. Saving leaves this button as not set up.",
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            androidx.compose.material3.Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save") }
        }
    }
}

@Composable
private fun ChooserRowItem(
    label: String,
    sub: String?,
    icon: ImageBitmap?,
    checked: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) { role = Role.Checkbox }
            .alpha(if (dimmed) CATEGORY_DIMMED_ALPHA else 1f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppGlyph(icon, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftColors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
