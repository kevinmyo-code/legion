package com.kevin.legion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Shared list furniture for every aspect screen. Extracted from the
 * `proto/ledger-ui` and `proto/fleet-pantry-ui` prototypes (ticket 08 built
 * the shapes, ticket 09's resolution §3 validated them a second time against
 * fleet and pantry and named this the extraction point - "before the three
 * aspects diverge", and ledger is the first real aspect screen built against
 * the Instrument theme, so it happens now rather than being retrofitted once
 * ticket 09 lands).
 *
 * All four read [LocalLegionSemantics] directly rather than taking a
 * `LegionSemantics` parameter - unlike the throwaway prototypes, which
 * threaded `sem` through every function because the prototype host switched
 * themes per-variant. Production screens are always inside one [com.kevin.legion.ui.theme.LegionTheme],
 * so reading the composition local here is the simpler, equally-correct call.
 */

/**
 * Stamp-cased section label with an optional trailing value (a count, an
 * account name) and a solid [com.kevin.legion.ui.theme.LegionSemantics.rule]
 * hairline underneath. The heavier of the two hairline roles - see
 * [Hairline] for the row-separator weight.
 */
@Composable
fun SectionHeader(left: String, right: String? = null) {
    val sem = LocalLegionSemantics.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(left, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
            if (right != null) Text(right, style = LegionType.stamp, color = sem.faint)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(sem.rule))
    }
}

/**
 * Row-separator hairline, deliberately softer than [SectionHeader]'s rule so
 * a long list of rows doesn't stripe.
 */
@Composable
fun Hairline() {
    val sem = LocalLegionSemantics.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(sem.ruleFaint))
}

/**
 * The workhorse row: a title, an optional subtitle beneath it, and a
 * trailing mono reading. Every aspect that shows "a label plus a number"
 * uses this rather than hand-rolling its own `Row` - fleet's live PID
 * values, maintenance due dates, and (in shape) ledger's balance rows.
 *
 * [valueColor] defaults to plain ink, matching [com.kevin.legion.ui.theme.LegionSemantics.debit]'s
 * "most values are not a signal" posture from the theme doc - callers pass
 * [com.kevin.legion.ui.theme.LegionSemantics.credit]/`estimated`/`quarantined`
 * explicitly when the value IS one.
 */
@Composable
fun ReadingRow(label: String, value: String, sub: String? = null, valueColor: Color? = null) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = LegionType.stamp, color = sem.faint)
        }
        Text(
            value,
            style = LegionType.reading,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A feature that exists in the data layer but has no screen yet. Ghosted
 * (`semantics.ghost`) rather than hidden or faked - CLAUDE.md's "say plainly
 * what is not built" posture applied to UI, per ticket 09 resolution §4.
 * [why] should state what exists ("12 records in the database") so the row
 * reads as an honest status, not dead space.
 */
@Composable
fun NotBuiltRow(label: String, why: String) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = sem.ghost)
            Text(why, style = LegionType.stamp, color = sem.ghost)
        }
        Text("NOT BUILT", style = LegionType.stamp, color = sem.ghost)
    }
}
