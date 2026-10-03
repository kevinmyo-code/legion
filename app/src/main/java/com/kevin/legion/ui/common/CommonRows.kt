package com.kevin.legion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.AreaChip
import com.kevin.legion.ui.theme.soft.LocalSoftActive
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

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
    if (LocalSoftActive.current) {
        // Lists' SectionLabel shape with an optional trailing value; no hairline underneath.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(left, style = MaterialTheme.typography.titleSmall, color = SoftColors.text2)
            if (right != null) Text(right, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
        }
        return
    }
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
 * The sub-screen title bar: a `< BACK` stamp on the leading edge, a centred
 * title, and a [Hairline] underneath.
 *
 * Extracted 2026-08-12 from the three deck-era screens that had each
 * hand-rolled the identical Row ([com.kevin.legion.ui.TelemetryScreen],
 * [com.kevin.legion.ui.CarsScreen], [com.kevin.legion.ui.CompanionsScreen]),
 * at the point a fourth, fifth and sixth caller appeared - the three
 * `settings/` screens, which until then still wore the plain M3 `Button("<
 * Back")` from the ticket-07 era and read as a different app once Settings
 * itself was restyled.
 *
 * The trailing [Text] is an intentional empty spacer that balances the back
 * button's width so the title stays optically centred, carried over verbatim
 * from the screens this was extracted from - not a slot for a third control.
 *
 * Under [com.kevin.legion.ui.theme.soft.SoftTheme] it renders [SoftScreenTopBar] instead; [accent] and
 * [iconRes] are read only there (an optional tonal area chip) and ignored under LegionTheme.
 *
 * **Those first three screens are NOT yet converted to call this.** They are
 * verified, working, and identical in output; changing them is a mechanical
 * follow-up, not part of the restyle that motivated the extraction.
 */
@Composable
fun DeckScreenHeader(
    title: String,
    onBack: () -> Unit,
    accent: AreaAccent? = null,
    @DrawableRes iconRes: Int? = null,
) {
    if (LocalSoftActive.current) {
        SoftScreenTopBar(title = title, onBack = onBack, accent = accent, iconRes = iconRes)
        return
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("", style = LegionType.stamp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Hairline()
    }
}

/**
 * The soft drill-down top bar: `ListsPageTopBar`'s exact shape (56dp row, 48dp icon button with the
 * Material Symbols Rounded back arrow, Figtree `titleLarge` title, sentence case as the caller wrote
 * it) plus an optional [AreaChip] between the arrow and the title when [accent] is given. The chip
 * is the same tonal circle HOME's tile for that area draws; [iconRes] overrides its glyph, otherwise
 * the area's own `AreaAccent.icon`. Shared by [DeckScreenHeader] and [DrilldownHeader] so the two
 * entry points cannot drift apart under SoftTheme.
 */
@Composable
internal fun SoftScreenTopBar(
    title: String,
    onBack: () -> Unit,
    accent: AreaAccent? = null,
    @DrawableRes iconRes: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        if (accent != null) {
            AreaChip(accent, iconRes = iconRes ?: accent.icon, size = 32.dp, iconSize = 18.dp)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            modifier = Modifier.weight(1f).padding(start = if (accent != null) 10.dp else 4.dp),
        )
    }
}

/**
 * The month pager's arrow: the same glyph the Calendar header uses, turned half a revolution for
 * "next" (no forward arrow is vendored). Replaces the bare "<" / ">" text buttons on the soft
 * money pages. [enabled] false greys it; the label is spoken ("Previous month" / "Next month").
 */
@Composable
internal fun MonthStepButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        MsIcon(
            res = R.drawable.ms_arrow_back,
            contentDescription = if (forward) "Next month" else "Previous month",
            tint = if (enabled) SoftColors.text else SoftColors.text2.copy(alpha = 0.4f),
            modifier = if (forward) Modifier.rotate(180f) else Modifier,
        )
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
 *
 * [modifier] is applied to the row itself and exists so a caller can make one
 * navigable (fleet's "Switch car" row) without a second near-identical row
 * type. It is the first optional parameter per the repo's vendored
 * `compose-modifier-and-layout-style` skill; the padding and width below are
 * applied AFTER it so a caller's `clickable` covers the whole padded row
 * rather than an unpadded rectangle inside it.
 */
@Composable
fun ReadingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color? = null,
) {
    val sem = LocalLegionSemantics.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
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
        Text("Not built".deckCase(), style = LegionType.stamp, color = sem.ghost)
    }
}
