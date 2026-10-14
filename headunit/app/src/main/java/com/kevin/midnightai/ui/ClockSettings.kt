package com.kevin.midnightai.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.kevin.midnightai.ai.AriaBrain
import com.kevin.midnightai.time.TrustedClock
import com.kevin.midnightai.time.ZoneSource
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens

/** The SYSTEM row's subtitle: the live "Clock: synced (device off by X)" line plus the zone. */
@Composable
internal fun clockRowSubtitle(): String {
    val s by TrustedClock.status.collectAsState()
    return "${s.line}. Zone ${s.zoneId}"
}

/** US zones as one-tap picks; anything else goes in the field as an IANA id. */
private val QUICK_ZONES = listOf(
    "CENTRAL" to "America/Chicago",
    "EASTERN" to "America/New_York",
    "MOUNTAIN" to "America/Denver",
    "PACIFIC" to "America/Los_Angeles",
)

private fun zoneSourceLabel(source: ZoneSource): String = when (source) {
    ZoneSource.PHONE -> "from the phone beacon"
    ZoneSource.MANUAL -> "set here"
    ZoneSource.ENGINE -> "from your LEGION household"
    ZoneSource.DEFAULT -> "default (Houston)"
}

/**
 * Clock & time zone (trusted clock, 2026-10-13). The head unit's own clock and zone are never
 * used for "now": this shows what the app uses instead and lets Kevin pin the zone.
 */
@Composable
internal fun ClockSection(context: Context, onBack: () -> Unit) {
    val status by TrustedClock.status.collectAsState()
    var manual by remember { mutableStateOf(TrustedClock.manualZoneId(context).orEmpty()) }
    var followPhone by remember { mutableStateOf(TrustedClock.followPhone(context)) }

    fun saveZone(id: String) {
        if (TrustedClock.setManualZone(context, id)) {
            manual = id.trim()
            AriaBrain.get(context).invalidateBase()
            Toast.makeText(context, if (id.isBlank()) "Zone cleared." else "Zone saved.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "\"$id\" is not a time zone id (try America/Chicago).", Toast.LENGTH_LONG).show()
        }
    }

    SectionScaffold("Clock & time zone", onBack) {
        Text(status.line, style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "The head unit's own clock and zone reset themselves, so the app keeps its own: network " +
                "time from time.google.com, held against a clock the head unit cannot change." +
                if (status.lastSyncFailed && !status.trusted) " The last sync failed (no data signal?); it retries on its own." else "",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s2))
        Button(
            onClick = {
                TrustedClock.requestSync()
                Toast.makeText(context, "Syncing...", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) { Text("SYNC NOW") }

        Spacer(Modifier.height(AriaDimens.s4))
        Text("Zone in use: ${status.zoneId}", style = RowTitle, color = AriaColors.TextPrimary)
        Text(
            "${zoneSourceLabel(status.zoneSource)}. The head unit says ${status.deviceZoneId}, which is ignored.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )

        Spacer(Modifier.height(AriaDimens.s3))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) {
            Switch(
                checked = followPhone,
                onCheckedChange = {
                    followPhone = it
                    TrustedClock.setFollowPhone(context, it)
                    AriaBrain.get(context).invalidateBase()
                },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Column(Modifier.weight(1f)) {
                Text("Follow the phone's time zone", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    "When the GPS beacon phone is linked it sends its own zone, which wins over the one " +
                        "below." + (TrustedClock.phoneZoneId(context)?.let { " Last heard: $it." } ?: " Not heard yet."),
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s3))
        Text("Your time zone", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            QUICK_ZONES.forEach { (label, id) ->
                val selected = manual == id
                val shape = RoundedCornerShape(AriaDimens.pageCorner)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = AriaDimens.driveTouchMin)
                        .clip(shape)
                        .border(AriaDimens.hairline, if (selected) AriaColors.Amber else AriaColors.TextDim, shape)
                        .clickable { saveZone(id) }
                        .padding(horizontal = AriaDimens.s3),
                ) {
                    Text(label, style = Label, color = if (selected) AriaColors.Amber else AriaColors.TextDim)
                }
            }
        }
        Spacer(Modifier.height(AriaDimens.s2))
        var field by remember { mutableStateOf(manual) }
        OutlinedTextField(
            value = field,
            onValueChange = { field = it },
            label = { Text("IANA zone id, e.g. America/Chicago (blank = LEGION household or default)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AriaDimens.s2))
        Button(
            onClick = { saveZone(field) },
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) { Text("SAVE ZONE") }
    }
}
