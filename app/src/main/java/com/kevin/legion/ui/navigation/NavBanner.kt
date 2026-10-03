package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavState
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

// The top of the guiding screen: the turn banner, the "Then:" strip and the muted tag.

/** The teal turn banner: arrow, distance to the turn, the street. While rerouting it says so instead. */
@Composable
internal fun TurnBanner(nav: NavState) {
    val accent = AreaAccent.FLEET
    val turn = nav.guidance?.turn
    val arrowRotation = if (nav.rerouting || turn == null) 0f else NavFormat.turnRotation(turn.modifier)
    Row(
        Modifier.fillMaxWidth().background(accent.container, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(
            R.drawable.ms_navigation,
            contentDescription = null,
            tint = accent.onContainer,
            size = 44.dp,
            modifier = Modifier.rotate(arrowRotation),
        )
        Column {
            val big = when {
                nav.rerouting -> "Rerouting"
                turn?.distanceM != null -> NavFormat.distance(turn.distanceM)
                else -> "Guiding"
            }
            val small = when {
                nav.rerouting -> "Finding a new route to ${nav.destination?.name}"
                turn != null -> turn.text
                else -> "Waiting for the first instruction"
            }
            Text(big, style = MaterialTheme.typography.headlineLarge, color = SoftColors.text)
            Text(
                small,
                style = MaterialTheme.typography.titleMedium,
                color = accent.onContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Then: ..." under the banner. Absent when the SDK has no next-next maneuver: never an empty strip. */
@Composable
internal fun ThenStrip(then: String?) {
    if (then.isNullOrBlank()) return
    Text(
        "Then: $then",
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(SoftColors.card, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun MutedTag() {
    Text(
        "Turn cues muted",
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.onAlert,
        modifier = Modifier
            .background(SoftColors.alertContainer, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
