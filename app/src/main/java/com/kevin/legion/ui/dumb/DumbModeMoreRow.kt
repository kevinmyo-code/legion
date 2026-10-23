package com.kevin.legion.ui.dumb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.MidnightApplication
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The More sheet's way to turn dumb phone mode on (ADR 0062). Draws nothing when the context is not
 * the real application (screenshot tests), so the baselines are untouched.
 */
@Composable
fun DumbModeMoreRow() {
    val app = LocalContext.current.applicationContext as? MidnightApplication ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Dumb mode", color = SoftColors.text)
            Text("A short list instead of a phone.", color = SoftColors.text.copy(alpha = 0.7f))
        }
        OutlinedButton(onClick = { app.dumbMode.turnOn() }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("Turn on")
        }
    }
}
