package com.kevin.legion.ui.common

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The header gear as a dropdown (home-launcher ticket 07). Kevin, 2026-10-02: the top-right gear
 * "opens the app's settings. i want it to open a modal or a dropdown where i can choose the phone's
 * settings or the app settings." Two items, each an icon tile, a title and a one-line subtitle.
 *
 * The open/closed state lives here, not in `MainActivity`, so [StatusLine] stays a plain function of
 * its callbacks and a test can tap the gear without a NavHost. It is `rememberSaveable` so a rotation
 * does not snap the menu shut under the user's thumb.
 */
@Composable
internal fun SettingsGearMenu(
    onOpenLegionSettings: () -> Unit,
    onOpenPhoneSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            MsIcon(res = R.drawable.ms_settings, contentDescription = "Settings", tint = SoftColors.text2)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SoftColors.cardHighest,
            shape = RoundedCornerShape(18.dp),
        ) {
            SettingsMenuItems(
                onOpenPhoneSettings = { open = false; onOpenPhoneSettings() },
                onOpenLegionSettings = { open = false; onOpenLegionSettings() },
            )
        }
    }
}

/** The two menu rows, split out so a screenshot can draw them without a popup window. */
@Composable
internal fun SettingsMenuItems(onOpenPhoneSettings: () -> Unit, onOpenLegionSettings: () -> Unit) {
    SettingsMenuRow(
        iconRes = R.drawable.ms_smartphone,
        tileColor = Color(0xFF1F3450),
        iconColor = Color(0xFFA8C8FF),
        title = "Phone settings",
        subtitle = "Wi-Fi, display, battery",
        onClick = onOpenPhoneSettings,
    )
    SettingsMenuRow(
        iconRes = R.drawable.ms_settings,
        tileColor = SoftColors.primaryContainer,
        iconColor = SoftColors.onPrimaryContainer,
        title = "LEGION settings",
        subtitle = "Assistant, connections, privacy",
        onClick = onOpenLegionSettings,
    )
}

@Composable
private fun SettingsMenuRow(
    @DrawableRes iconRes: Int,
    tileColor: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tileColor),
                    contentAlignment = Alignment.Center,
                ) {
                    MsIcon(res = iconRes, contentDescription = null, tint = iconColor, size = 20.dp)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
                }
            }
        },
        onClick = onClick,
    )
}

/**
 * Opens Android's own Settings (`ACTION_SETTINGS`) in a new task, or returns the sentence saying
 * why it could not. Null means the start was handed to the system; it says nothing about what the
 * user then does there. Never silent: a refused start reads as a broken gear.
 */
fun startPhoneSettings(context: Context): String? = runCatching {
    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
}.getOrElse { "Couldn't open the phone's settings: ${it.message ?: it.javaClass.simpleName}" }
