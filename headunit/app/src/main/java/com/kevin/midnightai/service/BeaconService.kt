package com.kevin.midnightai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kevin.midnightai.location.BeaconServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs the phone end of the GPS beacon link (see [DeviceRole]).
 *
 * Deliberately its own service rather than more loops inside
 * [AriaForegroundService]: in beacon role this device is a GPS puck, not a car. It
 * has no OBD dongle, no Gemini session to keep warm and no driving surface, and the
 * whole reason the role is viable on a slow spare handset is that it costs almost
 * nothing to run. Folding it into the main service would drag all of that along.
 *
 * A foreground service is required, not stylistic: Android stops delivering
 * location updates to a backgrounded app within minutes, which would strand the
 * head unit exactly when the driver stops interacting with the phone - i.e. always,
 * since the phone is in a pocket for the whole drive.
 */
class BeaconService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Beacon service created")

        // Refuse to start rather than crash. Android 14 enforces the location-type
        // permission off the MANIFEST-declared foregroundServiceType, and this
        // service declares "location" and nothing else - so unlike
        // AriaForegroundService (which declares four types, none of them location and
        // so can safely downgrade to an untyped startForeground), there is no
        // permission-free variant of this service to fall back to. Calling
        // startForeground() here without the grant is a SecurityException, and via
        // BootReceiver that is a crash on every single boot, undiagnosable in the
        // field because ADB logcat is blocked on the head unit (CLAUDE.md §14).
        // stopSelf() before any startForeground() call is safe: the 5-second
        // "must call startForeground" deadline only applies to a service that keeps
        // living. The Setup screen refuses to select this role without the grant, so
        // reaching this is a permission revoked after the fact.
        if (!com.kevin.midnightai.location.LocationController.hasPermission(this)) {
            Log.w(TAG, "Location permission missing - beacon role cannot run")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForegroundCompat()
        BeaconServer.start(this, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onStartCommand still runs after an onCreate that called stopSelf, so this
        // needs the same guard - otherwise the refusal above just moves the
        // SecurityException one callback later instead of preventing it.
        if (!com.kevin.midnightai.location.LocationController.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // Restart if the OS kills us: the head unit has no other way to get a fix,
        // and it cannot tell "phone died" from "no signal yet" to prompt about it.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BeaconServer.stop(this)
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS beacon")
            .setContentText("Sharing this phone's location with the head unit.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        // onCreate has already refused to run without the location grant, so by here
        // the type is always safe to declare.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS beacon", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "BeaconService"
        private const val CHANNEL_ID = "beacon_channel"

        // Distinct from AriaForegroundService's id (1) - both can run on the same
        // device while the phone app is open, and a collision would replace its
        // notification and detach one of the two services from the foreground.
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BeaconService::class.java))
            }.onFailure { Log.w(TAG, "Could not start beacon service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BeaconService::class.java)) }
        }
    }
}
