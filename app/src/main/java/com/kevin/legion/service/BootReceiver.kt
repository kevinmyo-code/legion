package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.ui.MainActivity

/**
 * Automatically launches ARIA when the Android head unit finishes booting.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // A device in GPS-beacon role is a phone in someone's pocket, not a head
        // unit: throwing the full launcher UI in their face on every reboot would be
        // hostile, and the beacon needs no UI to work. Start just the service.
        // BOOT_COMPLETED is one of the few contexts still allowed to start a
        // foreground service from the background, which is why this is the trigger.
        if (DeviceRole.current(context) == DeviceRole.Role.GPS_BEACON) {
            BeaconService.start(context)
            return
        }

        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
