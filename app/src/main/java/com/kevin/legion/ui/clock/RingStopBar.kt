package com.kevin.legion.ui.clock

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.clock.RingControl
import com.kevin.legion.clock.RingRequest
import com.kevin.legion.clock.RingService
import com.kevin.legion.clock.RingStopReason
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The in-app Stop (phantom alarm fix, 2026-11-21): while anything rings, a small bar over Alfred's
 * home and the Clock screen says what rings, the emergency alert included (it is in no clock list),
 * and stops all of it ([RingControl.stopAll]). Tapping the words opens the ring screen. Draws nothing
 * when nothing rings, so no screen changes the rest of the time.
 */
@Composable
fun RingStopBar(modifier: Modifier = Modifier) {
    val ringing by RingService.ringing.collectAsStateWithLifecycle()
    val line = ringStopLine(ringing) ?: return
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().background(SoftColors.alertContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            line,
            color = SoftColors.onAlert,
            modifier = Modifier.weight(1f).clickable {
                context.startActivity(Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        Button(onClick = { RingControl.stopAll(context, RingStopReason.BUTTON) }, colors = primaryColors()) {
            Text("Stop alarm")
        }
    }
}

/** What the bar says for the newest ring, or null when nothing rings. */
internal fun ringStopLine(ringing: List<RingRequest>): String? =
    ringing.lastOrNull()?.let { "${it.kind.title} ringing" }
