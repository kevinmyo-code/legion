package com.kevin.midnightai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.billing.EntitlementManager
import com.kevin.midnightai.ui.theme.AriaColors

private const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"

/**
 * Reusable Gemini API-key field: lets the driver paste their own key (the BYO-key
 * model - see the pricing/distribution plan), saves it to [CompanionProfile],
 * refreshes the process-wide [GeminiKeyProvider] cache, and flips
 * [com.kevin.midnightai.billing.EntitlementManager] to BYO_KEY so it takes effect
 * on the next session/sub-agent/image call without a restart. Rendered inside the
 * "AI & Plan" settings section, gated to drivers entitled to bring their own key
 * (already on one, or bought the one-time unlock).
 *
 * Shows which key is in use (the driver's, the app's built-in dev key, or none),
 * and a tappable link to where a free key is minted. [onSaved] lets the host
 * re-check key state after a save.
 */
@Composable
fun ApiKeyEntry(modifier: Modifier = Modifier, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(CompanionProfile.geminiKey(context)) }
    var reveal by remember { mutableStateOf(false) }
    // Bump after a save so the status line re-reads the live key state.
    var statusVersion by remember { mutableIntStateOf(0) }

    val status = remember(statusVersion) {
        when {
            CompanionProfile.hasGeminiKey(context) -> "Using your key."
            GeminiKeyProvider.hasKey() -> "Using the app's built-in key."
            else -> "Paste your key to switch to your own — free forever after."
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                CompanionProfile.saveGeminiKey(context, key)
                GeminiKeyProvider.init(context)
                // Saving a key flips the driver to BYO_KEY immediately - refresh so
                // the Plan screen and any AI-mode gate reflect it without a restart.
                EntitlementManager.refresh()
                statusVersion++
                onSaved()
            },
            enabled = key.isNotBlank(),
        ) { Text("SAVE KEY") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { reveal = !reveal }) {
            Text(if (reveal) "Hide" else "Show", color = AriaColors.TextDim)
        }
        Text(
            text = "Get a free key at aistudio.google.com/apikey",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.Amber,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "A free key from Google AI Studio works and needs no card. On the free tier " +
                "Google may use your conversations to improve their products, and their reviewers " +
                "may read them. Adding billing to your Google Cloud project turns that off. " +
                "Either way, your car's data never touches my servers.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
    }
}
