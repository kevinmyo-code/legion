package com.kevin.legion.ui.apps

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.ProvideTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The default launcher's pin confirmation (`LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT`, 2026-11-18).
 * Declaring it is what makes `ShortcutManager.isRequestPinShortcutSupported()` true while LEGION is
 * the home app, so Chrome offers "Add to Home screen" / "Install app" again. Like a stock launcher
 * it asks first ([PinConfirm]): the name the app gave, the app asking, Add or Cancel, in Typeset.
 * Add calls `PinItemRequest.accept()`; Cancel does nothing, which the platform reads as a refusal.
 * Anything but a valid shortcut request (a widget, a stale one) closes at once.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PinnedShortcutsAndroid.request(this, intent)
        if (request == null || !PinConfirm.offer(request)) {
            finish()
            return
        }
        val from = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(request.fromPackage, 0)).toString()
        }.getOrDefault(request.fromPackage)
        setContent {
            ProvideTypesetEdition(systemBars = false) {
                SoftTheme {
                    PinShortcutSheet(
                        label = request.label,
                        from = from,
                        onAdd = {
                            val words = when (PinConfirm.add(request)) {
                                PinConfirm.Outcome.ADDED -> "${request.label} is on your home screen."
                                PinConfirm.Outcome.REFUSED -> "Couldn't add ${request.label}. Nothing was added."
                                PinConfirm.Outcome.GONE -> "That request expired. Nothing was added."
                            }
                            Toast.makeText(this, words, Toast.LENGTH_SHORT).show()
                            AppDrawerCache.warm(this)
                            finish()
                        },
                        onCancel = ::finish,
                    )
                }
            }
        }
    }
}

/** The sheet: a scrim (a tap on it cancels) and a ruled panel at the foot. Stateless. */
@Composable
internal fun PinShortcutSheet(label: String, from: String, onAdd: () -> Unit, onCancel: () -> Unit) {
    // The scrim is a sibling under the panel, not its parent: a clickable would merge the panel's
    // pane title, which cannot merge.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM))
                .clickable(role = Role.Button, onClickLabel = "Cancel", onClick = onCancel),
        )
        Column(
            Modifier.fillMaxWidth().background(SoftColors.ground).border(1.dp, SoftColors.text)
                .pointerInput(Unit) { detectTapGestures { } } // a tap on the panel is not a cancel
                .semantics { paneTitle = "Add to home screen" }
                .padding(16.dp),
        ) {
            Text("ADD TO HOME SCREEN", style = TypesetType.caps, color = SoftColors.text2)
            Text(label, style = TypesetType.lead, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp))
            Text("From $from", style = TypesetType.smallItalic, color = SoftColors.text2)
            HorizontalDivider(Modifier.padding(top = 12.dp), thickness = 1.dp, color = SoftColors.text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton("Cancel", onCancel)
                SheetButton("Add", onAdd)
            }
        }
    }
}

@Composable
private fun SheetButton(words: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(words, style = TypesetType.body.copy(textDecoration = TextDecoration.Underline), color = SoftColors.text)
    }
}

private const val SCRIM = 0.4f
