package com.kevin.legion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyValidator
import com.kevin.legion.ai.KeyCheck
import kotlinx.coroutines.launch

/**
 * `settings/key` - the BYO Gemini key screen (ticket 07 resolution §2/§4).
 * Wording is the resolution's, substance verbatim: LEGION talks to Google
 * directly on the user's own key, no server in between, and the free-tier
 * training disclosure carries over from Midnight AI reworded to drop any
 * paid-tier implication (there is no commercial tier here - CLAUDE.md §2).
 *
 * Validation is a suspend call ([GeminiKeyValidator.check]) launched from a
 * click, so it uses `rememberCoroutineScope` rather than a `LaunchedEffect`
 * keyed on some paste-counter - the click already is the event
 * (`.claude/skills/compose-side-effects`).
 *
 * ```
 * paste -> GeminiKeyValidator.check
 *   VALID          -> CompanionProfile.saveGeminiKey (encrypted), proceed
 *   INVALID_KEY    -> "that key was rejected", stay on screen
 *   NETWORK_ERROR  -> offer save-and-verify-later, do not block
 * ```
 */
@Composable
fun KeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showSaveAnyway by remember { mutableStateOf(false) }

    fun verify() {
        showSaveAnyway = false
        checking = true
        scope.launch {
            when (GeminiKeyValidator.check(keyText)) {
                KeyCheck.VALID -> {
                    CompanionProfile.saveGeminiKey(context, keyText)
                    status = "Saved."
                    checking = false
                }
                KeyCheck.INVALID_KEY -> {
                    status = "That key was rejected."
                    checking = false
                }
                KeyCheck.NETWORK_ERROR -> {
                    status = "Couldn't reach Google to verify right now."
                    showSaveAnyway = true
                    checking = false
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }

            Text("Add your Gemini key")
            Text("LEGION talks to Google directly with your key. Nothing goes through a server I run.")
            Text(
                "Note: on Google's free tier, content you send may be used to improve their " +
                    "models. That includes statement and receipt text.",
            )

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("Paste key") },
            )

            Button(onClick = ::verify, enabled = !checking && keyText.isNotBlank()) {
                Text(if (checking) "Verifying..." else "Verify & save")
            }

            if (showSaveAnyway) {
                // NETWORK_ERROR does not block (resolution §2): the driver can
                // save unverified and it will be exercised for real the next
                // time something actually calls Gemini.
                Button(onClick = {
                    CompanionProfile.saveGeminiKey(context, keyText)
                    status = "Saved. Not verified yet - we'll find out the next time it's used."
                    showSaveAnyway = false
                }) {
                    Text("Save anyway, verify later")
                }
            }

            status?.let { Text(it) }
        }
    }
}
