package com.kevin.legion.ui.checklists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The Lists page - variant C, icon cards, ticket 01's own pick. Two 3-column grids ("Routines"
 * then "Lists"), a trailing dashed "New list" card, and (when [ListsPageState.showArchived]) a
 * third "Archived" section, its cards dimmed. Matches `research/prototype-canvas/ListsC.dc.html`
 * fitted to the A25's 384dp (ticket 01's own "prototype was drawn at 412dp" note).
 */
/** Ticket 01's own "3-column grid" - named so detekt's [MagicNumber] rule has something other
 * than a bare literal to point at everywhere this grid's column count is used. */
private const val GRID_COLUMNS = 3
private val FULL_ROW_SPAN: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(GRID_COLUMNS) }
private val SINGLE_SPAN: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(1) }

@Composable
fun ListsContent(state: ListsPageState, callbacks: ListsPageCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        ListsPageTopBar(showArchived = state.showArchived, onBack = callbacks.onBack, onToggleArchived = callbacks.onToggleArchived)

        if (state.loading) return@Column

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.routines.isNotEmpty()) {
                item(span = FULL_ROW_SPAN) {
                    SectionLabel("Routines")
                }
                items(state.routines, key = { "r${it.checklist.id}" }) { card ->
                    ListIconCard(card = card, onClick = { callbacks.onOpenList(card.checklist.id) })
                }
            }
            item(span = FULL_ROW_SPAN) {
                SectionLabel("Lists")
            }
            items(state.plainLists, key = { "p${it.checklist.id}" }) { card ->
                ListIconCard(card = card, onClick = { callbacks.onOpenList(card.checklist.id) })
            }
            item(span = SINGLE_SPAN) {
                NewListCard(onClick = { callbacks.onShowCreateDialog(true) })
            }
            if (state.showArchived && state.archivedLists.isNotEmpty()) {
                item(span = FULL_ROW_SPAN) {
                    SectionLabel("Archived")
                }
                items(state.archivedLists, key = { "a${it.checklist.id}" }) { card ->
                    ListIconCard(card = card, dimmed = true, onClick = { callbacks.onOpenList(card.checklist.id) })
                }
            }
        }
    }

    if (state.showCreateDialog) {
        CreateChecklistDialog(
            onDismiss = { callbacks.onShowCreateDialog(false) },
            onCreate = callbacks.onCreate,
        )
    }
}

@Composable
private fun ListsPageTopBar(showArchived: Boolean, onBack: () -> Unit, onToggleArchived: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        Text(
            "Lists",
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                MsIcon(res = R.drawable.ms_more_vert, contentDescription = "More options", tint = SoftColors.text2)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (showArchived) "Hide archived" else "Show archived") },
                    onClick = { menuOpen = false; onToggleArchived() },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = SoftColors.text2,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** An archived card's reduced alpha - "dimmed", per ticket 04's own wording. */
private const val ARCHIVED_ALPHA = 0.5f

/** One icon card - the 68dp ring, the 58dp coloured chip, the name, then the short label,
 * per ticket 04's own layout. [dimmed] renders an archived card at reduced alpha, still tappable. */
@Composable
private fun ListIconCard(card: ListCardUi, dimmed: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SoftColors.card)
            .clickable(onClick = onClick)
            .alpha(if (dimmed) ARCHIVED_ALPHA else 1f)
            .padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProgressRing(fraction = card.progress.fraction, ringColor = card.visual.accent.onContainer) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(card.visual.accent.container),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(res = card.visual.icon, contentDescription = null, tint = card.visual.accent.onContainer, size = 28.dp)
            }
        }
        Text(
            card.checklist.name,
            style = MaterialTheme.typography.titleSmall,
            color = SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(card.progress.label, style = MaterialTheme.typography.labelSmall, color = SoftColors.text2)
    }
}

/** The trailing dashed "New list" card - `research/prototype-canvas/ListsC.dc.html`'s own
 * `1.5dp dashed outline` styling; [androidx.compose.ui.graphics.drawscope.Stroke]'s `pathEffect`
 * draws the dash since Compose has no built-in dashed-border modifier. */
@Composable
private fun NewListCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .dashedBorder(SoftColors.outline, 20.dp)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(SoftColors.cardHigh),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(res = R.drawable.ms_add, contentDescription = null, tint = SoftColors.text2)
        }
        Text(
            "New list",
            style = MaterialTheme.typography.titleSmall,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
