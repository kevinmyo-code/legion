package com.kevin.midnightai.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.ai.CURATED_VOICES
import com.kevin.midnightai.ai.DEFAULT_VOICE
import com.kevin.midnightai.ai.GeminiVoice
import com.kevin.midnightai.ui.theme.AriaColors

/**
 * Voice picker: lists the curated prebuilt Gemini voices with a tap-to-audition
 * play button and a tap-to-select row, returning the chosen voice name via
 * [onConfirm]. Samples play from bundled res/raw clips (voice_sample_<name>);
 * until those are generated (tools/generate_voice_samples.ps1) the play button is
 * a no-op, but selection still works. Reusable in onboarding and (later) settings.
 */
@Composable
fun VoicePicker(
    initialVoice: String,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initialVoice.ifBlank { DEFAULT_VOICE }) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    fun audition(voice: GeminiVoice) {
        val resId = context.resources.getIdentifier(voice.sampleRawName, "raw", context.packageName)
        if (resId == 0) return  // sample not bundled yet (run the gen script)
        player?.release()
        player = MediaPlayer.create(context, resId)?.also { it.start() }
    }

    fun stopPlayer() {
        player?.release()
        player = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { stopPlayer(); onBack() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Pick a voice", style = MaterialTheme.typography.headlineMedium, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap ▶ to hear each one, then choose your favorite.",
            style = MaterialTheme.typography.bodyMedium,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(16.dp))

        for (voice in CURATED_VOICES) {
            VoiceRow(
                voice = voice,
                selected = selected == voice.name,
                onPlay = { audition(voice) },
                onSelect = { selected = voice.name },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(10.dp))
        Button(onClick = { stopPlayer(); onConfirm(selected) }) { Text("NEXT") }
    }
}

@Composable
private fun VoiceRow(
    voice: GeminiVoice,
    selected: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
) {
    val accent = if (selected) AriaColors.Amber else AriaColors.PanelBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .background(AriaColors.Panel)
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                voice.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) AriaColors.Amber else AriaColors.TextPrimary,
            )
            Text(
                voice.descriptor.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AriaColors.TextDim,
            )
        }
        Text(
            text = "▶",
            style = MaterialTheme.typography.titleMedium,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onPlay() }
                .padding(10.dp),
        )
    }
}
