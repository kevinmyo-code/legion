@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.ui.home.GamesTone
import com.kevin.legion.ui.theme.soft.SoftColors

@Composable
internal fun ThemeChips(selected: TetrisTheme, onPick: (TetrisTheme) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TetrisTheme.entries.forEach { t ->
            val on = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) SoftColors.primaryContainer else SoftColors.cardHigh)
                    .clickable(onClickLabel = "Use the ${t.label} theme") { onPick(t) }
                    .semantics { contentDescription = "${t.label} theme" + if (on) ", selected" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = if (on) SoftColors.onPrimaryContainer else SoftColors.text2,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Said in words wherever a race hides the pause button (Kevin, 2026-10-19). */
const val RACE_NO_PAUSE = "Races can't pause, the clock is shared."

/** The big number or sentence over the well while the board is not live, or the pause menu. */
@Composable
internal fun Overlay(state: TetrisUiState, actions: TetrisActions) {
    if (state.paused) {
        Column(
            Modifier.fillMaxSize().background(SoftColors.ground.copy(alpha = 0.92f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Paused",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
            )
            Button(
                onClick = actions.onResume,
                colors = toneButton(),
                modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
            ) { Text("Resume", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth()) {
                Text("Quit", color = SoftColors.text)
            }
        }
        return
    }
    if (state.stage == TetrisStage.PLAYING) return
    Box(
        Modifier.fillMaxSize().background(SoftColors.ground.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            state.message,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = SoftColors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun toneButton() = ButtonDefaults.buttonColors(
    containerColor = GamesTone.onContainer,
    contentColor = GamesTone.container,
)

