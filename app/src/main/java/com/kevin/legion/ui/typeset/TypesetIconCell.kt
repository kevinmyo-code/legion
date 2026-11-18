@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.typeset

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * One entry on the Typeset front page drawn as a small icon with its words under it (Kevin,
 * 2026-11-17: "little icons with the description text below them"): a 24dp glyph,
 * the label in one line of small serif under it, ellipsised, the whole cell one tap target of at
 * least 48dp. At the default font a cell is exactly a 48dp row, the height the word line had, so the
 * front page's fit ladder gives up nothing for the icons; at font 1.3 a row grows by a few dp.
 * The label stays the visible text (the screen map and `tools/phone` tap by it).
 *
 * State is never colour alone: [dimmed] (a group or pin that cannot open now) halves the icon and
 * sets the label in meta, [italic] (a group with nothing picked) italicises it, and the cell's
 * [description] says the state in words to TalkBack.
 */
@Composable
@Suppress("LongParameterList") // one cell: its words, its taps, its state, its icon
internal fun RowScope.TypesetIconCell(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    description: String? = null,
    dimmed: Boolean = false,
    italic: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier.weight(weight)
            .heightIn(min = TapTarget)
            .combinedClickable(role = Role.Button, onClickLabel = label, onClick = onClick, onLongClick = onLongClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(GlyphBox).alpha(if (dimmed) DIMMED else 1f), contentAlignment = Alignment.Center) { icon() }
        Text(
            label,
            style = if (italic) IconLabel.copy(fontStyle = FontStyle.Italic) else IconLabel,
            color = if (dimmed || italic) SoftColors.text2 else SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        )
    }
}

/** A glyph from the repo's own set, in ink (meta when [quiet]). */
@Composable
internal fun Glyph(@DrawableRes res: Int, quiet: Boolean = false) =
    MsIcon(res, contentDescription = null, tint = if (quiet) SoftColors.text2 else SoftColors.text, size = GlyphSize)

/**
 * An app's own launcher icon (the drawer cache's bitmap, full colour, the same one All apps shows),
 * or, while it is loading or gone, its two-letter monogram in a ruled square.
 */
@Composable
internal fun AppGlyph(icon: ImageBitmap?, monogram: String) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(GlyphBox))
    } else {
        Box(Modifier.size(GlyphBox).border(1.dp, SoftColors.outline), contentAlignment = Alignment.Center) {
            Text(monogram, style = IconLabel, color = SoftColors.text, maxLines = 1)
        }
    }
}

/**
 * [items] in rows of [perRow], each cell the same width, a short last row padded with empty cells so
 * every column lines up (an even grid, not a stretched last row).
 */
@Composable
internal fun <T> IconRows(items: List<T>, perRow: Int, cell: @Composable RowScope.(T) -> Unit) {
    items.chunked(perRow).forEach { row ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            row.forEach { cell(it) }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The glyph for each contents entry: the repo's `ms_*` set, three drawn here (`ts_*`). */
@DrawableRes
internal fun contentsIcon(name: String): Int = when (name) {
    "Today" -> R.drawable.ms_calendar_month
    "Money" -> R.drawable.ms_account_balance_wallet
    "Car" -> R.drawable.ms_directions_car
    "Clock" -> R.drawable.ms_schedule
    "Field" -> R.drawable.ts_compass
    "Breathe" -> R.drawable.ms_air
    "Games" -> R.drawable.ts_gamepad
    "Books" -> R.drawable.ms_menu_book
    "Lists" -> R.drawable.ms_checklist
    "Ideas" -> R.drawable.ts_lightbulb
    "Brief" -> R.drawable.ms_wb_twilight
    "Media" -> R.drawable.ms_play_arrow
    else -> R.drawable.ms_more_vert
}

private val GlyphBox = 24.dp
private val GlyphSize = 24.dp
private const val DIMMED = 0.5f

/** Small serif for a label under an icon: one step under `small`, so seven fit a phone's width. */
internal val IconLabel = TypesetType.small.copy(fontSize = 12.sp, lineHeight = 14.sp)
