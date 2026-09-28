package com.kevin.legion.quiet

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** The Quick Settings tile for [QuietMode]: one swipe down, one tap, from anywhere. */
class QuietTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        when (val result = QuietMode.toggle(this)) {
            is QuietMode.Result.Refused -> {
                // Said in words, and never a silent dead tap. If access is missing, take him
                // straight to where it's granted.
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                if (result.message == QuietMode.NEEDS_ACCESS) openAccessSettings()
            }
            else -> Unit
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = QuietMode.isOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Quiet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "Vibrate, calls only" else "Off"
        }
        tile.updateTile()
    }

    private fun openAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
