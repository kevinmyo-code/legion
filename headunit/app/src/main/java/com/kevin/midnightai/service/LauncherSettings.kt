package com.kevin.midnightai.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.kevin.midnightai.util.ExternalDoor
import com.kevin.midnightai.util.ExternalScreen

object LauncherSettings {
    /** True when this app is currently the system's default HOME. Ground truth — no pref needed. */
    fun isDefaultLauncher(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == context.packageName
    }

    /**
     * Opens the system Home-settings screen so the driver can select or deselect this app as the
     * default launcher. Android doesn't allow apps to set this programmatically; we can only
     * direct the user to the right system screen.
     *
     * Some cheap head-unit ROMs (AOSP 8 to 10) expose no Home-picker screen, so the top-level
     * Settings app is the next door. Returns null when one of the two opened, else the words for
     * what did not. Both go through [ExternalDoor], which also catches the `SecurityException` the
     * old hand-written catches here did not: a non-exported Settings activity throws that, not
     * `ActivityNotFoundException`, and it killed the launcher just the same.
     */
    fun openLauncherSettings(context: Context): String? {
        val homePicker = ExternalDoor.attempt(ExternalScreen.HOME_SETTINGS) {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } ?: return null
        val settingsApp = ExternalDoor.attempt(ExternalScreen.HOME_SETTINGS) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        // Neither door opened. The driver asked for the home-app setting, so that is what the
        // words name, not the settings app we tried on his behalf.
        return if (settingsApp == null) null else homePicker
    }

    /**
     * Tries to launch another installed home/launcher app directly, so the driver can escape to
     * the stock Android OS without going through settings. Falls back to the Home-settings screen
     * when no other launcher is found, or when the one found refuses to start: that explicit
     * component of another app is the case that throws `SecurityException`, which the old catch
     * here let through.
     *
     * Returns null when something opened, else the words for what did not.
     */
    fun escapeToOS(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val others = context.packageManager
            .queryIntentActivities(homeIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
        val target = others.firstOrNull() ?: return openLauncherSettings(context)
        val failed = ExternalDoor.attempt(ExternalScreen.OTHER_HOME_APP) {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(
                        ComponentName(
                            target.activityInfo.packageName,
                            target.activityInfo.name,
                        ),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } ?: return null
        // The other launcher would not start. Settings is the next door; if that opened, the
        // driver got somewhere and there is nothing to say.
        return if (openLauncherSettings(context) == null) null else failed
    }
}
