package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.GeminiKeyValidator
import com.kevin.legion.ai.KeyCheck
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
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
 *
 * **Restyled 2026-08-12.** The screen logic is unchanged; it wore ticket-07-era
 * plain M3 (`Button("< Back")`, unstyled body text) which read as a different
 * app next to the restyled Settings it hangs off. Now [DeckScreenHeader] plus
 * the same panel/stamp vocabulary as its two siblings.
 */
@Composable
fun KeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    var keyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showSaveAnyway by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }

    fun verify() {
        showSaveAnyway = false
        checking = true
        scope.launch {
            when (GeminiKeyValidator.check(keyText)) {
                KeyCheck.VALID -> {
                    CompanionProfile.saveGeminiKey(context, keyText)
                    // Refresh the process-wide cache immediately (GeminiKeyProvider.init
                    // is otherwise only called once, at AriaForegroundService.onCreate) -
                    // without this, anything that reads GeminiKeyProvider.hasKey() before
                    // the next process restart (e.g. the ledger tab's spend gate, ticket
                    // 08 Part 6) would keep reading "no key" even though one was just saved.
                    GeminiKeyProvider.init(context)
                    hasKey = GeminiKeyProvider.hasKey()
                    keyText = ""
                    status = "Saved."
                    statusIsError = false
                    checking = false
                }
                KeyCheck.INVALID_KEY -> {
                    status = "That key was rejected."
                    statusIsError = true
                    checking = false
                }
                KeyCheck.NETWORK_ERROR -> {
                    status = "Couldn't reach Google to verify right now."
                    statusIsError = true
                    showSaveAnyway = true
                    checking = false
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Gemini key", onBack = onBack)

            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (hasKey) "A key is set" else "No key set",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (hasKey) {
                                "The assistant and every LLM fallback can run. Pasting a new key replaces it."
                            } else {
                                "The assistant can't run without one. Ledger, pantry and fleet are unaffected."
                            },
                            style = LegionType.stamp,
                            // ADVISORY (ticket 13 re-home, ticket 09 answer §3): "no key set" is the
                            // fresh-install state, not a failure - amber, never chrome.
                            color = if (hasKey) sem.faint else sem.estimated,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Your key", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "LEGION talks to Google directly with your key. Nothing goes through a " +
                            "server I run.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        // Mono and no autocorrect/autocapitalise, same as the Spotify client-ID
                        // field: an opaque token that a helpful keyboard silently corrupts.
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        label = { Text("Paste key", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::verify, enabled = !checking && keyText.isNotBlank()) {
                            Text(
                                if (checking) "VERIFYING" else "VERIFY & SAVE",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (showSaveAnyway) {
                    // NETWORK_ERROR does not block (resolution §2): the driver can
                    // save unverified and it will be exercised for real the next
                    // time something actually calls Gemini.
                    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Save it without verifying?",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            TextButton(onClick = {
                                CompanionProfile.saveGeminiKey(context, keyText)
                                GeminiKeyProvider.init(context) // see the VALID branch's comment above
                                hasKey = GeminiKeyProvider.hasKey()
                                keyText = ""
                                status = "Saved. Not verified yet - we'll find out the next time it's used."
                                statusIsError = false
                                showSaveAnyway = false
                            }) {
                                Text("SAVE ANYWAY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "Note: on Google's free tier, content you send may be used to improve their " +
                        "models. That includes statement and receipt text.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // ADVISORY (ticket 13 re-home, ticket 09 answer §3): INVALID_KEY and
                        // NETWORK_ERROR are both advisories - act on this - never ALARM.
                        color = if (statusIsError) sem.estimated else sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
