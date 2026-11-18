package com.kevin.legion.ui.typeset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.LetterFolder
import com.kevin.legion.ui.apps.appCountLabel
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The Typeset app tray (ADR 0081), in letter buckets again (Kevin, 2026-11-17: "i want them in the
 * alphabet buckets like how it used to be"): the folder grid `eb124d20` gave All apps on 2026-10-02
 * (one folder per letter, A to Z then #, accents folded to their base letter, each a 2 x 2 of its
 * first four apps' icons over the letter and the count), which `3f563dd4` replaced outside Matrix
 * with a text index. Drawn here in Typeset: square ruled tiles on paper, serif letters. A tap on a
 * folder opens it over the page ([openFolder], hoisted): the letter, "N apps", and its apps three to
 * a row, icon over name. Kept from the index: the "Find an app" line (typing shows every match as a
 * flat icon grid, no folders), the work-apps line, the message line, "work" and "blocked" in words
 * under an app (ADR 0066 block list; the tap still goes to the caller, whose block screen says why),
 * and long press to pin or unpin. Stateless: `AppsScreen` owns the query, the open folder, the apps,
 * their icons and the launch.
 *
 * With a work profile, a plain line under "Find an app" pauses or turns on the work apps
 * ([workPaused] null = no work profile, no line). Stage 6, 2026-11-16: only the Matrix grid had
 * this control, so a paused work app here pointed at a row that did not exist (ADR 0050, 0035).
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // one tray, its state and its taps; nothing invented here
fun TypesetAppsIndex(
    folders: List<LetterFolder>,
    query: String,
    onQueryChange: (String) -> Unit,
    blocked: (DrawerApp) -> Boolean,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    workPaused: Boolean? = null,
    onToggleWork: () -> Unit = {},
    /** The drawer cache's icons, by [iconKey]; an app with none shows its first letters. */
    icons: Map<String, ImageBitmap> = emptyMap(),
    /** The letter of the folder open over the page, or null. */
    openFolder: String? = null,
    onOpenFolder: (String?) -> Unit = {},
) {
    val paused = workPaused == true
    val cellFor: @Composable RowScope.(DrawerApp) -> Unit = { app ->
        val icon = icons[iconKey(app.profileKey, app.packageName, app.className)]
        AppCell(app, icon, blocked(app), paused, onOpen, onLongPress)
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                "ALL APPS",
                style = TypesetType.caps,
                color = SoftColors.text2,
                modifier = Modifier.padding(top = 12.dp),
            )
            FindLine(query, onQueryChange)
            workPaused?.let { WorkLine(it, onToggleWork) }
            message?.let { Text(it, style = TypesetType.small, color = SoftColors.onAlert) }
            if (folders.isEmpty()) {
                Text(
                    if (query.isBlank()) "No apps to list." else "No app matches \"${query.trim()}\".",
                    style = TypesetType.body,
                    color = SoftColors.text,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                if (query.isBlank()) {
                    IconRows(folders, FOLDER_COLUMNS) { folder ->
                        FolderTile(folder, icons) { onOpenFolder(folder.letter) }
                    }
                } else {
                    IconRows(folders.flatMap { it.apps }, SEARCH_COLUMNS, cellFor)
                }
            }
        }
        folders.firstOrNull { it.letter == openFolder }?.let { folder ->
            BackHandler { onOpenFolder(null) }
            FolderOverlay(folder, onClose = { onOpenFolder(null) }, cell = cellFor)
        }
    }
}

@Composable
private fun FindLine(query: String, onQueryChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TypesetType.body.copy(color = SoftColors.text),
            cursorBrush = SolidColor(SoftColors.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTarget)
                .semantics { contentDescription = "Find an app" },
            decorationBox = { field ->
                Column(verticalArrangement = Arrangement.Center) {
                    if (query.isEmpty()) Text("Find an app", style = TypesetType.body, color = SoftColors.text3)
                    field()
                }
            },
        )
        HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
    }
}

/** Pauses or turns on the work profile's apps; the words say which, never a switch alone. */
@Composable
private fun WorkLine(paused: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (paused) WORK_PAUSED_LINE else WORK_PAUSE_LINE,
            style = TypesetType.body,
            color = if (paused) SoftColors.onAlert else SoftColors.text,
        )
    }
}

/** The work line's words; [com.kevin.legion.ui.apps.launchDrawerApp]'s paused sentence points at the first. */
const val WORK_PAUSED_LINE = "Work apps are paused. Tap to turn them on."
const val WORK_PAUSE_LINE = "Pause work apps"

/** A letter bucket: a ruled square holding its first four apps' icons, the letter and the count under it. */
@Composable
private fun RowScope.FolderTile(folder: LetterFolder, icons: Map<String, ImageBitmap>, onClick: () -> Unit) {
    val description = "Folder ${folder.letter}, ${appCountLabel(folder.apps.size)}"
    Column(
        Modifier.weight(1f)
            .clickable(role = Role.Button, onClickLabel = "Open folder ${folder.letter}", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.size(FolderBox).border(1.dp, SoftColors.outline).padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val shown = folder.apps.take(MINI_ICONS)
            for (rowStart in 0 until MINI_ICONS step 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in rowStart until rowStart + 2) {
                        val app = shown.getOrNull(i)
                        MiniIcon(app?.let { icons[iconKey(it.profileKey, it.packageName, it.className)] }, app)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(folder.letter, style = TypesetType.bodyStrong, color = SoftColors.text)
            Text(
                folder.apps.size.toString(),
                style = TypesetType.small,
                color = SoftColors.text2,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

/** One of a folder tile's four: the app's icon, its first letter while the icon is missing, or empty. */
@Composable
private fun MiniIcon(icon: ImageBitmap?, app: DrawerApp?) {
    Box(Modifier.size(MiniIconSize), contentAlignment = Alignment.Center) {
        when {
            icon != null -> Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(MiniIconSize))
            app != null -> Box(
                Modifier.size(MiniIconSize).background(SoftColors.card),
                contentAlignment = Alignment.Center,
            ) { Text(app.label.take(1), style = IconLabel, color = SoftColors.text2) }
        }
    }
}

/** An open folder over the page: a scrim (tap, Close or Back closes it), the letter, the count, three to a row. */
@Composable
private fun FolderOverlay(folder: LetterFolder, onClose: () -> Unit, cell: @Composable RowScope.(DrawerApp) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM))
            .clickable(role = Role.Button, onClickLabel = "Close folder ${folder.letter}", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = FolderMaxHeight)
                .background(SoftColors.ground).border(1.dp, SoftColors.text)
                .clickable(enabled = false) {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folder.letter, style = TypesetType.fig, color = SoftColors.text)
                Text(
                    appCountLabel(folder.apps.size),
                    style = TypesetType.smallItalic,
                    color = SoftColors.text2,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                Box(Modifier.tap("Close", onClose), contentAlignment = Alignment.Center) {
                    Text(
                        "Close",
                        style = TypesetType.small.copy(textDecoration = TextDecoration.Underline),
                        color = SoftColors.text,
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
            Column(Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                IconRows(folder.apps, DIALOG_COLUMNS, cell)
            }
        }
    }
}

/** An app: its icon over its name, "work", "paused" and "blocked" in words under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
private fun RowScope.AppCell(
    app: DrawerApp,
    icon: ImageBitmap?,
    isBlocked: Boolean,
    workPaused: Boolean,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
) {
    val words = listOfNotNull(
        "work".takeIf { app.isWork },
        "paused".takeIf { app.isWork && workPaused },
        "blocked".takeIf { isBlocked },
    )
    Column(
        Modifier.weight(1f)
            .heightIn(min = MinTarget)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = app.label,
                onLongClickLabel = "Pin or unpin",
                onLongClick = { onLongPress(app) },
                onClick = { onOpen(app) },
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(AppIconSize).alpha(if (isBlocked) DIMMED else 1f), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(AppIconSize))
            } else {
                Box(Modifier.size(AppIconSize).border(1.dp, SoftColors.outline), contentAlignment = Alignment.Center) {
                    Text(app.label.take(2), style = TypesetType.bodyStrong, color = SoftColors.text)
                }
            }
        }
        Text(
            app.label,
            style = TypesetType.small,
            color = if (isBlocked) SoftColors.text2 else SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        )
        if (words.isNotEmpty()) {
            Text(
                words.joinToString(", "),
                style = IconLabel.copy(fontStyle = TypesetType.smallItalic.fontStyle),
                color = if (isBlocked) SoftColors.onAlert else SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val FOLDER_COLUMNS = 4
private const val SEARCH_COLUMNS = 4
private const val DIALOG_COLUMNS = 3
private const val MINI_ICONS = 4
private const val SCRIM = 0.4f
private const val DIMMED = 0.5f
private val MinTarget = 44.dp
private val FolderBox = 64.dp
private val MiniIconSize = 22.dp
private val AppIconSize = 44.dp
private val FolderMaxHeight = 560.dp
