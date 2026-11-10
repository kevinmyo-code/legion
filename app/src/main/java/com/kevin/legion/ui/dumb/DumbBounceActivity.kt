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
 * The screen a bounced app leaves behind. Two modes, picked by [EXTRA_MATRIX]:
 *
 * - **Matrix mode on** (prototype `Bounce.dc.html`, ADR 0062): "Matrix mode is on". "Back to home"
 *   closes it (Home was already pressed); the small link starts the 60 second leave wait. No pass is
 *   offered: Matrix leave is the only way out of Matrix mode (ADR 0066).
 * - **Normal mode, "Block feed apps always"** (ADR 0066): "<App> is blocked", "Go back", and
 *   "Ask for a 15 min pass", which runs the wall-clock wait and the sentence here and then opens the app.
 */
class DumbBounceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { "That app" }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val graph = (application as MidnightApplication).dumb
        // Read the live state, not only the extra: the mode may have changed since the bounce.
        val matrix = intent.getBooleanExtra(EXTRA_MATRIX, true) || graph.controller.state.value.on
        setContent {
            if (matrix || packageName == null) {
                DumbBounceScreen(
                    appLabel = label,
                    onBack = { finish() },
                    onLeave = {
                        graph.controller.requestLeave()
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                )
            } else {
                FeedBlockedScreen(
                    appLabel = label,
                    pass = graph.feedPass,
                    packageName = packageName,
                    onBack = {
                        graph.feedPass.cancelRequest(packageName)
                        finish()
                    },
                    onBreathe = {
                        graph.feedPass.cancelRequest(packageName)
                        com.kevin.legion.ui.typeset.BreathActivity.start(this)
                        finish()
                    },
                    onOpen = {
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val opened = launch != null && runCatching { startActivity(launch) }.isSuccess
                        if (opened) finish()
                        opened
                    },
                )
            }
        }
    }

    /** singleTop: a second bounce while this screen shows is for a different app; rebuild for it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_PACKAGE = "package"

        /** True when Matrix mode was on at the bounce. Missing reads as true: the old, stricter screen. */
        const val EXTRA_MATRIX = "matrix"
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
