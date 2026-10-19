@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The built-in Tetris tile (retro-games ticket 06), above the ROM shelves: Solo always, and one
 * "Play <member>" per household member the engine lists. A race that cannot start says why in
 * words on the tile and leaves Solo alone.
 */
@Composable
internal fun TetrisShelf(games: GamesHome) {
    val race = games.state.race
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SoftColors.cardHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "TETRIS",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = GamesTone.onContainer,
        )
        Text(
            "Built in. Solo, or a three minute score race against someone in your household.",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { games.callbacks.onPlayTetris(null) },
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Play Tetris solo" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GamesTone.onContainer,
                    contentColor = GamesTone.container,
                ),
            ) { Text("Solo", fontWeight = FontWeight.Bold) }
            race.opponents.forEach { who ->
                OutlinedButton(
                    onClick = { games.callbacks.onPlayTetris(who.userId) },
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .semantics { contentDescription = "Invite ${who.name} to race at Tetris" },
                ) { Text("Play ${who.name}", color = SoftColors.text) }
            }
        }
        val note = when {
            race.loading -> "Checking who you can race..."
            race.unavailable != null -> race.unavailable
            race.opponents.isEmpty() -> "Nobody else in your household to race yet."
            else -> null
        }
        note?.let {
            val tone = if (race.unavailable != null) SoftColors.caution else SoftColors.text3
            Text(it, style = MaterialTheme.typography.bodySmall, color = tone)
        }
    }
}
