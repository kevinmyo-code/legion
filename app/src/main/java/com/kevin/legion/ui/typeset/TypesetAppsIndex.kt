package com.kevin.legion.ui.typeset

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.LetterFolder
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The Typeset app tray (ADR 0081, `proto-typeset.html` "All apps"): every app as an index by first
 * letter, names in full, no logos. A "Find an app" line at the top narrows it as you type. A feed
 * app the blocker covers reads "blocked" beside its name, from the same block list the drawer
 * uses (ADR 0066); the tap still goes to the caller, whose block screen says why. A work-profile
 * app says "work" in words. Stateless: `AppsScreen` owns the query, the loaded apps and the launch.
 */
@Composable
@Suppress("LongParameterList") // one tray, its state and its taps; nothing invented here
fun TypesetAppsIndex(
    folders: List<LetterFolder>,
    query: String,
    onQueryChange: (String) -> Unit,
    blocked: (DrawerApp) -> Boolean,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "ALL APPS",
            style = TypesetType.caps,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 12.dp),
        )
        FindLine(query, onQueryChange)
        message?.let { Text(it, style = TypesetType.small, color = SoftColors.onAlert) }
        if (folders.isEmpty()) {
            Text(
                if (query.isBlank()) "No apps to list." else "No app matches \"${query.trim()}\".",
                style = TypesetType.body,
                color = SoftColors.text,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(folders, key = { it.letter }) { folder ->
                IndexRow(folder, blocked, onOpen, onLongPress)
            }
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

/** One letter, its apps down the right column, a rule under the row. */
@Composable
private fun IndexRow(
    folder: LetterFolder,
    blocked: (DrawerApp) -> Boolean,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(
            folder.letter,
            style = TypesetType.fig,
            color = SoftColors.text2,
            modifier = Modifier.width(LetterColumn).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            folder.apps.forEach { app ->
                AppLine(app, blocked(app), onOpen, onLongPress)
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppLine(
    app: DrawerApp,
    isBlocked: Boolean,
    onOpen: (DrawerApp) -> Unit,
    onLongPress: (DrawerApp) -> Unit,
) {
    val words = listOfNotNull("work".takeIf { app.isWork }, "blocked".takeIf { isBlocked })
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = app.label,
                onLongClickLabel = "Pin or unpin",
                onLongClick = { onLongPress(app) },
                onClick = { onOpen(app) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(app.label, style = TypesetType.body, color = if (isBlocked) SoftColors.text2 else SoftColors.text)
        if (words.isNotEmpty()) {
            Text(
                words.joinToString(", "),
                style = TypesetType.smallItalic,
                color = if (isBlocked) SoftColors.onAlert else SoftColors.text2,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private val MinTarget = 44.dp
private val LetterColumn = 56.dp
