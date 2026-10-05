package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.legion.navigation.MapboxTokenCheck
import com.kevin.legion.navigation.MapboxTokenProvider
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The Mapbox token row on the Setup screen (mapbox-nav tickets 08 and 09), the Gemini key's shape:
 * a paste field, a save, a clear, one status line. Its own file because `KeyScreen.kt` is past the
 * 1000-line mark and this is where the new rows go.
 *
 * **No verify step.** Verifying would mean calling Mapbox over REST, which ToS 2.9.1 forbids outside
 * the mobile SDK, so a bad token is learnt when navigation first uses it and is then said in words
 * ("Mapbox refused the token") on every navigation surface. The row says so rather than implying a
 * check that did not happen.
 */
@Composable
fun MapboxTokenSection(provider: MapboxTokenProvider) {
    val sem = LocalLegionSemantics.current
    val tokenState by provider.state.collectAsState()
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    Text(
        "Mapbox token",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Text(
        MapboxTokenSectionCopy.explainer,
        style = LegionType.stamp,
        color = sem.faint,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        MapboxTokenSectionCopy.headline(tokenState.isSet, tokenState.rejected, provider.hasPasted()),
        style = MaterialTheme.typography.bodyMedium,
        // Fresh-install and refused are both advisories (act on this), never ALARM, as the Gemini row.
        color = if (tokenState.isSet && !tokenState.rejected) sem.faint else sem.estimated,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            label = { Text("Paste public token (pk.)", style = LegionType.stamp) },
        )
        TokenButtons(
            provider = provider,
            text = text,
            onTextChange = { text = it },
            onStatus = { msg, isError ->
                status = msg
                statusIsError = isError
            },
        )
    }
    status?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (statusIsError) sem.estimated else sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** CLEAR and SAVE, split from [MapboxTokenSection] to keep each Composable short. */
@Composable
private fun TokenButtons(
    provider: MapboxTokenProvider,
    text: String,
    onTextChange: (String) -> Unit,
    onStatus: (String?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = {
                provider.clear()
                onTextChange("")
                onStatus("Cleared.", false)
            },
            enabled = provider.hasPasted(),
        ) { Text("CLEAR", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary) }
        TextButton(
            onClick = {
                val verdict = provider.save(text)
                if (verdict == MapboxTokenCheck.OK) {
                    onTextChange("")
                    onStatus(MapboxTokenSectionCopy.SAVED, false)
                } else {
                    onStatus(verdict.message, true)
                }
            },
            enabled = text.isNotBlank(),
        ) { Text("SAVE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary) }
    }
}

/** The row's sentences, outside the Composable so a test can pin them (the KeyScreen convention). */
internal object MapboxTokenSectionCopy {
    const val explainer =
        "Maps and turn-by-turn directions. Get a public token, starts with pk., from your Mapbox " +
            "account."
    const val SAVED =
        "Saved. Not verified: if Mapbox refuses it, navigation will say so the first time it is used."

    /** [isSet] is the effective token (pasted or baked); [pasted] says which of the two it is. */
    fun headline(isSet: Boolean, rejected: Boolean, pasted: Boolean): String = when {
        !isSet -> "No token set. Navigation is off."
        rejected -> "Mapbox refused the token. Paste a new one."
        pasted -> "A token is set"
        else -> "Using this build's own token. Paste yours to replace it."
    }
}
