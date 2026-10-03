package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.AreaChip

/**
 * The Money title row: area chip, "Money", and the two header actions. Pulled out of
 * [LedgerContent] (soft conversion, ADR 0051) so that function stays under detekt's LongMethod
 * limit after the chip was added, and LedgerScreen.kt under TooManyFunctions. Presentation only; the callbacks are [LedgerContent]'s own, and
 * the to-categorize count (computed there, see its call site) is said in words on the button.
 */
@Suppress("FunctionNaming") // @Composable PascalCase; detekt rule has no Composable exemption
@Composable
internal fun MoneyTitleRow(
    toCategorizeCount: Int,
    onOpenGroceries: () -> Unit,
    onOpenCategorize: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AreaChip(AreaAccent.MONEY, size = 32.dp, iconSize = 18.dp)
            Text(
                "Money",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A grocery receipt is a purchase (2026-08-07 brief) -
            // pantry's read screen lives under Money now, reached
            // from here rather than its own tab.
            TextButton(onClick = onOpenGroceries) {
                Text("Groceries", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            // Mission-control ticket 16: now opens the CATEGORIZE drilldown rather than
            // instant-firing the categorise action - that action lives INSIDE the drilldown
            // now (an explicit RUN CATEGORIZATION button), because this screen is where its
            // own results land. The count said in words, not a bare badge (CLAUDE.md §4) -
            // same convention SectionHeader's own right-hand count already used pre-ticket-16.
            TextButton(onClick = onOpenCategorize) {
                Text(
                    if (toCategorizeCount > 0) "Categorize ($toCategorizeCount)" else "Categorize",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
