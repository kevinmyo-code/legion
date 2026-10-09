@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The bottom sheet behind the To-dos / Lists / Ideas buttons (one-home ticket 11). A sheet, not a
 * screen: it opens over HOME and Done returns to exactly where the user was. Split into this window
 * and [HomePanelSheetContent] so a screenshot can draw the content without a window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePanelSheet(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    val sheet = state.sheet ?: return
    ModalBottomSheet(
        onDismissRequest = callbacks.onCloseSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        HomePanelSheetContent(sheet, state, callbacks)
    }
}

@Composable
fun HomePanelSheetContent(sheet: PanelSheet, state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when (sheet) {
                    PanelSheet.TODOS -> "To-dos"
                    PanelSheet.LISTS -> "Lists"
                    PanelSheet.IDEAS -> "Ideas this weekend"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
            )
            TextButton(onClick = callbacks.onCloseSheet, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Done", color = SoftColors.text)
            }
        }
        // The sheet scrolls when a list is long; HOME itself never does.
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            when (sheet) {
                PanelSheet.TODOS -> TodoRows(state, callbacks)
                PanelSheet.LISTS -> ListRows(state, callbacks)
                PanelSheet.IDEAS -> IdeaRows(state, callbacks)
            }
        }
    }
}

@Composable
private fun rowTitle() = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun SheetNote(text: String, failed: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) SoftColors.caution else SoftColors.text2,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun TodoRows(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    state.todosNote?.let { SheetNote(it, failed = true) }
    if (state.todos.isEmpty() && state.todosNote == null) SheetNote("Nothing to do.", failed = false)
    state.todos.forEach { todo ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(value = todo.done, role = Role.Checkbox) { callbacks.onToggleTodo(todo, it) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val box = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(22.dp)
                    .let {
                        if (todo.done) {
                            it.background(SoftColors.outline, box)
                        } else {
                            it.border(2.dp, AreaAccent.LISTS.onContainer, box)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (todo.done) {
                    MsIcon(R.drawable.ms_check, contentDescription = null, tint = SoftColors.ground, size = 16.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (todo.done) SoftColors.text3 else SoftColors.text,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                )
                Text(todo.sub, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
        }
    }
}

@Composable
private fun ListRows(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    state.listsNote?.let { SheetNote(it, failed = true) }
    if (state.lists.isEmpty() && state.listsNote == null) SheetNote("No lists yet.", failed = false)
    state.lists.forEach { list ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button) { callbacks.onOpenList(list) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(10.dp).background(AreaAccent.LISTS.onContainer, RoundedCornerShape(5.dp)))
            Column(Modifier.weight(1f)) {
                Text(list.title, style = rowTitle(), color = SoftColors.text)
                Text(list.sub, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
        }
    }
}

@Composable
private fun IdeaRows(state: HomeCalendarUiState, callbacks: HomeCalendarCallbacks) {
    var open by remember { mutableStateOf<Long?>(null) }
    state.ideasNote?.let { SheetNote(it, failed = true) }
    if (state.ideas.isEmpty() && state.ideasNote == null) SheetNote("No ideas this weekend.", failed = false)
    state.ideas.forEach { idea ->
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { open = if (open == idea.id) null else idea.id },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).background(AreaAccent.NEWS.onContainer, RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f)) {
                    Text(idea.title, style = rowTitle(), color = SoftColors.text)
                    Text(idea.sub, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
                    Text(
                        "Suggestion \u00b7 not a plan",
                        style = MaterialTheme.typography.labelSmall,
                        color = AreaAccent.NEWS.onContainer,
                    )
                }
            }
            idea.outcome?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (open == idea.id && idea.outcome == null) {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { callbacks.onAddIdea(idea) }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("Add to my plans")
                    }
                    OutlinedButton(
                        onClick = { callbacks.onDropIdea(idea) },
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text("Not interested")
                    }
                }
            }
        }
    }
}
