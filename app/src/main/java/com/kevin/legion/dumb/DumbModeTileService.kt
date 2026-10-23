package com.kevin.legion.dumb

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kevin.legion.MidnightApplication
import com.kevin.legion.ui.MainActivity

/**
 * The Quick Settings tile for dumb phone mode (ADR 0062). Off: one tap turns it on. On: a tap only
 * OPENS LEGION, which is on its home list; leaving takes the 60 second wait and the typed sentence,
 * so a tile that switched it off in one tap would be the shortcut the mode exists to remove.
 */
class DumbModeTileService : TileService() {

    private val controller: DumbModeController
        get() = (application as MidnightApplication).dumbMode

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (controller.state.value.on) openLegion() else controller.turnOn()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = controller.state.value.on
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Matrix mode"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "On. Tap to open" else "Off"
        }
        tile.updateTile()
    }

    private fun openLegion() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") // the Intent overload is the only one before API 34
            startActivityAndCollapse(intent)
        }
    }
}
