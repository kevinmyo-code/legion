package com.kevin.legion.carlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the car link's location sender alive with the screen off: a foreground service of type
 * `location` with an ongoing notification in plain words. It holds no logic of its own: the
 * [CarLinkController] starts it only while paired AND "Send location" is on AND the pairing network
 * is present, and stops it the moment any of those ends. Not sticky: if Android kills it, it does not
 * come back by itself with stale conditions.
 */
class CarLinkLocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sending location to the car")
            .setContentText("The car's head unit gets this phone's position. Turn it off in Settings, Car link.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "car_link"
        private const val NOTIFICATION_ID = 17311
        private const val TAG = "CarLinkService"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car link", NotificationManager.IMPORTANCE_LOW))
        }

        /** The Android side of [LocationServiceHost]. A refused start is false, never a crash. */
        fun host(context: Context): LocationServiceHost = object : LocationServiceHost {
            private val app = context.applicationContext

            @Suppress("TooGenericExceptionCaught") // Android 12+ refuses background starts with several types
            override fun start(): Boolean = try {
                ContextCompat.startForegroundService(app, Intent(app, CarLinkLocationService::class.java))
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "start refused: ${e.javaClass.simpleName}")
                false
            }

            override fun stop() {
                app.stopService(Intent(app, CarLinkLocationService::class.java))
            }
        }
    }
}
