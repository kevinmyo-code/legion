@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The Apps screen's letter-folder grid and the folder dialog (home-launcher ticket 07, Kevin picked
 * prototype A: one folder per letter). Stateless: [AppsScreen] owns the launch call, the pin menu
 * and the "which folder is open" state, so a tap here is exactly the same `launchDrawerApp` call
 * and the same failure sentence the old list rows used.
 *
 * Icons come from the [AppDrawerCache] snapshot's own `icons` map, keyed by [iconKey] - no second
 * `LauncherApps` query.
 */
private const val FOLDER_COLUMNS = 4
private const val DIALOG_COLUMNS = 3
private const val MINI_ICONS = 4

/** A work app carries WORK in words under its label (never the system badge alone - two "Outlook"
 * rows that differ only by a small briefcase are easy to tap wrong); a paused one says so. */
internal fun workLabel(app: DrawerApp, workPaused: Boolean): String? = when {
    !app.isWork -> null
    workPaused -> "WORK - PAUSED"
    else -> "WORK"
}

/** "1 app" / "N apps". */
internal fun appCountLabel(count: Int): String = if (count == 1) "1 app" else "$count apps"

@Composable
internal fun FolderGrid(
    folders: List<LetterFolder>,
    icons: Map<String, ImageBitmap>,
    onOpenFolder: (LetterFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(FOLDER_COLUMNS),
        modifier = modifier.fillMaxSize().testTag("app-folder-grid"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(folders, key = { it.letter }) { folder ->
            FolderTile(folder, icons) { onOpenFolder(folder) }
        }
    }
}

@Composable
private fun FolderTile(folder: LetterFolder, icons: Map<String, ImageBitmap>, onClick: () -> Unit) {
    val description = "Folder ${folder.letter}, ${appCountLabel(folder.apps.size)}"
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A 2 x 2 of the folder's first four apps; a folder with fewer leaves the rest empty
        // rather than repeating an icon.
        Column(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SoftColors.cardHigh)
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val shown = folder.apps.take(MINI_ICONS)
            for (rowStart in 0 until MINI_ICONS step 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in rowStart until rowStart + 2) {
                        val app = shown.getOrNull(i)
                        val icon = app?.let { icons[iconKey(it.profileKey, it.packageName, it.className)] }
                        if (icon != null) {
                            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(25.dp))
                        } else {
                            Spacer(Modifier.size(25.dp))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                folder.letter,
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                fontWeight = FontWeight.Bold,
            )
            Text(folder.apps.size.toString(), style = MaterialTheme.typography.labelMedium, color = SoftColors.text2)
        }
    }
}

/** The non-empty-search view: a flat 4-column icon grid, every match, no folders. */
@Composable
internal fun AppIconGrid(
    apps: List<DrawerApp>,
    icons: Map<String, ImageBitmap>,
    workPaused: Boolean,
    columns: Int,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(apps, key = { "${it.profileKey}/${it.packageName}/${it.className}" }) { app ->
            AppIconCell(
                app = app,
                icon = icons[iconKey(app.profileKey, app.packageName, app.className)],
                workPaused = workPaused,
                onOpen = { onOpen(app) },
                onLongPress = { onLongPress(app) },
            )
        }
    }
}

@Composable
internal fun AppIconCell(
    app: DrawerApp,
    icon: ImageBitmap?,
    workPaused: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val work = workLabel(app, workPaused)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(44.dp))
        }
        Text(
            app.label,
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (work != null) {
            Text(
                work,
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * An open folder: a dialog over a scrim (tapping the scrim or back dismisses it), the letter large,
 * "N apps", and the apps in a 3-column icon grid. [onOpen] closes the dialog first, so a failed
 * launch's sentence is visible on the screen behind it instead of hidden under the scrim.
 */
@Composable
internal fun FolderDialog(
    folder: LetterFolder,
    icons: Map<String, ImageBitmap>,
    workPaused: Boolean,
    onDismiss: () -> Unit,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(SoftColors.card)
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    folder.letter,
                    style = MaterialTheme.typography.displaySmall,
                    color = SoftColors.text,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    appCountLabel(folder.apps.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Rows of three in a plain scrolling Column, not a lazy grid: a lazy grid fills the
            // height it is offered, so a two-app folder would open as a tall empty card.
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("app-folder-dialog-grid"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                folder.apps.chunked(DIALOG_COLUMNS).forEach { rowApps ->
                    Row(Modifier.fillMaxWidth()) {
                        rowApps.forEach { app ->
                            Box(Modifier.weight(1f)) {
                                AppIconCell(
                                    app = app,
                                    icon = icons[iconKey(app.profileKey, app.packageName, app.className)],
                                    workPaused = workPaused,
                                    onOpen = { onOpen(app) },
                                    onLongPress = { onLongPress(app) },
                                )
                            }
                        }
                        repeat(DIALOG_COLUMNS - rowApps.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
