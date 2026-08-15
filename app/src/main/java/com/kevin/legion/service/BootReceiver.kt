package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.notes.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms every scheduled reminder after a reboot
 * (`.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`) - `AlarmManager` does not
 * persist alarms across a power cycle, so without this every reminder set before a reboot would
 * silently never fire again.
 *
 * **Does NOT start `MainActivity` or `AriaForegroundService`.** A `BootReceiver` existed before
 * and was deleted in `legion-shape` ticket 07 because it used to `startActivity(MainActivity)` on
 * `ACTION_BOOT_COMPLETED` - car-launcher behaviour with no place in a phone app the driver opens
 * on their own. This one's only job is [AlarmScheduler.rescheduleAll]; nothing here touches the
 * assistant's on/off state ([com.kevin.legion.service.AssistantIgnition]) or brings any UI forward.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
