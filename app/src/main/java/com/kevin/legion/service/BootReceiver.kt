package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.ui.MainActivity

/**
 * Automatically launches the app when the phone finishes booting.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
