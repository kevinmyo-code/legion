package com.kevin.legion.ui.common

import androidx.compose.foundation.layout.Column
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

/**
 * Shared "< BACK" + title header every in-screen drilldown opens with - lifted out of
 * `ui/BodyScreen.kt` (goal-plans ticket 08) so `ui/goals/GoalChecklistPanel.kt`'s relocated
 * TRAINING drilldown (see that file's own doc comment on why it now hosts one) can open with the
 * exact same header [com.kevin.legion.ui.BodyScreen]'s MASS/INTAKE/SLEEP drilldowns already use,
 * rather than a second hand-rolled copy drifting from this one. Same shape
 * [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]'s own header row uses.
 */
@Composable
fun DrilldownHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
