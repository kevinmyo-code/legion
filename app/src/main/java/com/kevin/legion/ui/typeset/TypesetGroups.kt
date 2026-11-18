
package com.kevin.legion.ui.typeset

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.home.slotStatusWord
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.home.categoryButtonSpec
import com.kevin.legion.ui.home.lookOf
import com.kevin.legion.ui.home.singleAppIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * One app group on the Typeset front page (the old home's category row: Bank, Music, Maps, Mail,
 * Chat) as a small icon with its short name under it (Kevin, 2026-11-17: "same for bank music maps
 * mail"). The icon is the picked app's own launcher icon when exactly one installed app is picked
 * (the old row's rule, Kevin 2026-10-09: "if a bucket has only one app, take the icon of that app"),
 * otherwise the group's glyph from the repo's set ([lookOf]) in ink. The taps are the old row's own
 * (`CategoryRow`): one app opens it, several ask which in a sheet, none opens the chooser, long
 * press edits the group. A group with nothing picked reads in italic meta with a meta glyph; one
 * that cannot open now (paused, not installed) is dimmed; its words say so to TalkBack.
 */
@Composable
fun RowScope.TypesetGroupCell(ui: CategoryUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    val spec = categoryButtonSpec(ui)
    TypesetIconCell(
        label = ui.category.short,
        onClick = onClick,
        onLongClick = onLongClick,
        description = spec.description,
        dimmed = spec.dimmed,
        italic = spec.unset,
    ) {
        val app = singleAppIcon(ui)
        if (app != null) AppGlyph(app, "") else Glyph(lookOf(ui.category).icon, quiet = spec.unset)
    }
}

/**
 * A group opened (several apps picked: "open which?"), in Typeset: the group's name as the head,
 * then one ruled row per app with a two-letter ink tile (no logos, ADR 0081), its name, and any
 * reason it cannot open in words; "Choose apps" edits the group. Maps lists LEGION Navigation first
 * (ADR 0054). The same taps as the old sheet; only the drawing differs.
 */
@Composable
fun TypesetGroupSheet(
    ui: CategoryUi,
    onLaunch: (DockSlotUi) -> Unit,
    onChoose: () -> Unit,
    onOpenNavigation: (() -> Unit)?,
) {
    CompositionLocalProvider(LocalContentColor provides SoftColors.text) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(ui.category.title, style = TypesetType.lead, color = SoftColors.text)
            Text("Open which?", style = TypesetType.smallItalic, color = SoftColors.text2)
            HorizontalDivider(Modifier.padding(top = 8.dp), thickness = 1.dp, color = SoftColors.text)
            onOpenNavigation?.let { GroupRow("Le", "LEGION Navigation", null, it) }
            ui.slots.forEach { slot ->
                val why = slotStatusWord(slot)
                GroupRow(slotTile(slot).monogram, slot.label, why) { onLaunch(slot) }
            }
            Box(Modifier.fillMaxWidth().tap("Choose apps", onChoose), contentAlignment = Alignment.CenterStart) {
                Text(
                    "Choose apps",
                    style = TypesetType.small.copy(textDecoration = TextDecoration.Underline),
                    color = SoftColors.text,
                )
            }
        }
    }
}

@Composable
private fun GroupRow(monogram: String, name: String, why: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(name, onClick)) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).border(1.dp, SoftColors.outline), contentAlignment = Alignment.Center) {
                Text(monogram, style = TypesetType.bodyStrong, color = SoftColors.text)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    name,
                    style = TypesetType.bodyStrong,
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                why?.let { Text(it, style = TypesetType.smallItalic, color = SoftColors.text2) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
}
