package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The Money tile's body under its total: the muted "Current period" line, up to three category bars
 * and "+N more". Each bar is the row's own background, filled to its share of the largest category, so
 * the label and the full "USD 1,800.00" amount keep the whole row width (a separate bar between them
 * was a few dp wide at tile width). Everything is words as well as shape.
 *
 * **How many bars show is decided by the height this tile actually has**, after reserving the trust
 * disclosure below it: the disclosure is never shortened or clipped (CLAUDE.md section 4 rules 5 and
 * 7), so when height is short it is bars that go, down to none, with "+N more" counting what is hidden.
 */
@Composable
internal fun MoneyBars(model: MoneyTileModel, disclosureLines: Int) {
    val tight = MaterialTheme.typography.labelSmall.copy(lineHeight = TILE_LINE_HEIGHT)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxHeight - DISCLOSURE_LINE_HEIGHT * disclosureLines
        val shown = barsThatFit(model, available)
        val hidden = model.categoryCount - shown
        Column(Modifier.fillMaxWidth()) {
            if (model.currentPeriodLine != null || hidden > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        model.currentPeriodLine.orEmpty(),
                        style = tight,
                        color = SoftColors.text3,
                        modifier = Modifier.weight(1f),
                    )
                    if (hidden > 0) Text("+$hidden more", style = tight, color = SoftColors.text3)
                }
            }
            model.bars.take(shown).forEach { bar -> MoneyBarRow(bar, tight) }
        }
    }
}

private val TILE_LINE_HEIGHT = 14.sp
private val BAR_ROW_HEIGHT = 14.dp
/** labelSmall's own 16sp line, which is what a wrapped disclosure line costs. */
private val DISCLOSURE_LINE_HEIGHT = 16.dp

/** Most bars (0..3) that fit [available] beside the header line, which shows when anything needs it. */
private fun barsThatFit(model: MoneyTileModel, available: Dp): Int {
    var shown = model.bars.size
    while (shown > 0) {
        val header = if (model.currentPeriodLine != null || model.categoryCount > shown) BAR_ROW_HEIGHT else 0.dp
        if (header + BAR_ROW_HEIGHT * shown <= available) break
        shown--
    }
    return shown
}

@Composable
private fun MoneyBarRow(bar: TileBar, style: androidx.compose.ui.text.TextStyle) {
    Box(Modifier.fillMaxWidth().height(BAR_ROW_HEIGHT)) {
        Box(
            Modifier
                .fillMaxWidth(bar.fraction)
                .height(BAR_ROW_HEIGHT)
                .background(AreaAccent.MONEY.onContainer.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                bar.label,
                style = style,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(bar.amountText, style = style, color = SoftColors.text, maxLines = 1, softWrap = false)
        }
    }
}

/** Lines a disclosure takes at tile width (about 26 characters of labelSmall per line); reserved
 * under the bars so the disclosure is never the thing that gets cut. */
internal fun disclosureLineCount(disclosure: String?): Int =
    if (disclosure == null) 0 else (disclosure.length + DISCLOSURE_CHARS_PER_LINE - 1) / DISCLOSURE_CHARS_PER_LINE

private const val DISCLOSURE_CHARS_PER_LINE = 26
