package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The crisis path's driver-facing surface (CLAUDE.md sec 9.1).
 *
 * **This composable deliberately breaks the sec 12 design language, and that is
 * the feature.** No city-pop palette, no katakana, no cream lower-third, no
 * amber/magenta, no avatar, no Zero. Sec 9.1 says to stop performing the
 * character and surface real resources; rendering a crisis line in the app's
 * cassette-broadcast styling would be performing it harder, and dressing a
 * suicide hotline in a brand aesthetic is grotesque. Plain white on near-black,
 * high contrast, no ornament. If a future palette pass sweeps this file for
 * AriaColors tokens, that is a mistake - leave it alone.
 *
 * No animation, per sec 9's frame-clock rule, and because nothing here should
 * move.
 */
@Composable
fun CrisisCard(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opaque scrim, not a translucent one: this covers Cruise entirely so
            // the dashboard, the avatar and the now-playing deck are not sitting
            // behind the message looking cheerful.
            .background(Color(0xFF08080A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF141418))
                .border(1.dp, Color(0xFF3A3A42), RoundedCornerShape(4.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "I'm not equipped for this, and I'm not going to pretend otherwise.",
                color = Color(0xFFF2F2F4),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 28.sp,
            )
            Text(
                text = "If you're in crisis, please talk to someone who is.\n" +
                    "In the US, call or text 988 to reach the Suicide and Crisis Lifeline, any time.",
                color = Color(0xFFB8B8C0),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "DISMISS",
                color = Color(0xFF8A8A94),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(12.dp),
            )
        }
    }
}
