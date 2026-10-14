package com.kevin.midnightai.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

object LauncherSettings {
    private const val TAG = "LauncherSettings"

    /** True when this app is currently the system's default HOME. Ground truth — no pref needed. */
    fun isDefaultLauncher(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == context.packageName
    }

    /**
     * Opens the system Home-settings screen so the driver can select or deselect
     * this app as the default launcher. Android doesn't allow apps to set this
     * programmatically; we can only direct the user to the right system screen.
     */
    fun openLauncherSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            // Some cheap head-unit ROMs (AOSP 8-10) don't expose a Home-picker
            // screen; fall back to the top-level Settings app rather than crash.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "No home-picker or settings activity available on this unit", e2)
            }
        }
    }

    /**
     * Tries to launch another installed home/launcher app directly, so the driver
     * can escape to the stock Android OS without going through settings. Falls back
     * to opening the system Home-settings screen if no other launcher is found.
     */
    fun escapeToOS(context: Context) {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val others = context.packageManager
            .queryIntentActivities(homeIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
        val target = others.firstOrNull()
        if (target != null) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setComponent(
                            ComponentName(
                                target.activityInfo.packageName,
                                target.activityInfo.name,
                            )
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Other launcher wouldn't start, falling back to settings", e)
                openLauncherSettings(context)
            }
        } else {
            openLauncherSettings(context)
        }
    }
}
