package com.kevin.legion.ui.dumb

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.MidnightApplication
import com.kevin.legion.ui.MainActivity

/**
 * "Matrix mode is on" (prototype `Bounce.dc.html`, ADR 0062): shown over LEGION's home after a blocked
 * app was sent away. The blocked app's label is the only thing it says about that app. "Back to
 * home" closes it (Home was already pressed, so LEGION's home is underneath); the small link starts
 * the 60 second leave wait and opens the Leave flow.
 */
class DumbBounceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { "That app" }
        setContent {
            DumbBounceScreen(
                appLabel = label,
                onBack = { finish() },
                onLeave = {
                    (application as MidnightApplication).dumbMode.requestLeave()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_APP_LABEL = "app_label"
    }
}

@Composable
fun DumbBounceScreen(appLabel: String, onBack: () -> Unit, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DumbColors.ground).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Matrix mode is on", color = DumbColors.text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(
                "$appLabel is blocked in Matrix mode. To unblock it, open Matrix mode settings (tap the " +
                    "line under the date on Home) and tap $appLabel under Blocked apps. Its notifications " +
                    "are being held for later.",
                color = DumbColors.body,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Back to home", onBack, primary = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Leave matrix mode (60s wait)",
                color = DumbColors.dim,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onLeave)
                    .padding(12.dp),
            )
        }
    }
}
