package com.kevin.legion.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.common.DeckRange
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * Soft-theme building blocks private to the BODY screen (soft-body conversion, ADR 0051).
 *
 * `ui/common`'s [com.kevin.legion.ui.common.DeckRangeSelector] and `GapEmptyRow`'s action are still
 * square/stamp-shaped and this conversion may not edit `ui/common`, so BODY draws the three controls
 * it needs here: a rounded range chip row, a tonal action button, and a worded delete action. All of
 * them put their meaning in WORDS (selected state is a fill AND a distinct text colour; delete says
 * "Delete"), never colour alone - CLAUDE.md section 4 posture.
 */

/** Sentence-case a primitive-owned stamp ("NOT LOGGED" -> "Not logged"); a figure passes through. */
internal fun softSentence(text: String): String =
    text.lowercase().replaceFirstChar { it.uppercase() }

/** Rounded range chips replacing the square [com.kevin.legion.ui.common.DeckRangeSelector]. */
@Composable
internal fun BodyRangeChips(selected: DeckRange, onSelect: (DeckRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (range in DeckRange.entries) {
            val isSelected = range == selected
            Text(
                range.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) SoftColors.onPrimaryContainer else SoftColors.text2,
                modifier = Modifier
                    .background(
                        if (isSelected) SoftColors.primaryContainer else SoftColors.card,
                        RoundedCornerShape(50),
                    )
                    .clickable(enabled = !isSelected) { onSelect(range) }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A tonal pill action ("Log weight") with a leading plus - the soft form of the old `+ LOG ...` stamp. */
@Composable
internal fun BodyAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false) {
    Row(
        modifier
            .background(if (quiet) SoftColors.cardHigh else SoftColors.primaryContainer, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!quiet) MsIcon(R.drawable.ms_add, contentDescription = null, tint = SoftColors.onPrimaryContainer, size = 16.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (quiet) SoftColors.text2 else SoftColors.onPrimaryContainer,
        )
    }
}

/** The history-row delete: a trash glyph beside the word, alert-coloured text on the row's own card. */
@Composable
internal fun BodyDeleteAction(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = SoftColors.onAlert) {
    Row(
        modifier
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MsIcon(R.drawable.ms_delete, contentDescription = null, tint = tint, size = 18.dp)
        Text("Delete", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
