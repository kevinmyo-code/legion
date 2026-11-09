package com.kevin.legion.clock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.kevin.legion.MidnightApplication
import com.kevin.legion.quiet.QuietMode

/**
 * Bedtime's view of Quiet mode and the feed block (ADR 0079), through their existing entry points:
 * [QuietMode.enable]/[QuietMode.disable] and `DumbModeGraph.feedBlock` with its watch. Both keep
 * state in credential-encrypted storage, so every call checks [unlocked] first.
 */
class AndroidBedtimeEffects(private val context: Context) : BedtimeEffects {

    override fun unlocked(): Boolean = isUnlocked(context)

    override fun quietOn(): BedtimeEffects.On {
        if (!unlocked()) return BedtimeEffects.On.Refused(LOCKED)
        if (QuietMode.isOn(context)) return BedtimeEffects.On.AlreadyOn
        return when (val r = QuietMode.enable(context)) {
            QuietMode.Result.On -> BedtimeEffects.On.TurnedOn
            QuietMode.Result.Off -> BedtimeEffects.On.Refused("Quiet did not turn on.")
            is QuietMode.Result.Refused -> BedtimeEffects.On.Refused(
                if (r.message == QuietMode.NEEDS_ACCESS) NEEDS_DND else r.message,
            )
        }
    }

    override fun quietOff(): String? = when (val r = QuietMode.disable(context)) {
        is QuietMode.Result.Refused -> if (r.message == QuietMode.NEEDS_ACCESS) NEEDS_DND_OFF else r.message
        else -> null
    }

    override fun feedOn(): BedtimeEffects.On {
        if (!unlocked()) return BedtimeEffects.On.Refused(LOCKED)
        val graph = (context.applicationContext as? MidnightApplication)?.dumb
            ?: return BedtimeEffects.On.Refused("The feed block could not be reached.")
        if (graph.feedBlock.get()) return BedtimeEffects.On.AlreadyOn
        graph.feedBlock.set(true)
        runCatching { graph.feedBlockWatch.check() }
        return BedtimeEffects.On.TurnedOn
    }

    override fun feedOff() {
        val graph = (context.applicationContext as? MidnightApplication)?.dumb ?: return
        graph.feedBlock.set(false)
        runCatching { graph.feedBlockWatch.check() }
    }

    companion object {
        const val NEEDS_DND = "Quiet needs Do Not Disturb access for LEGION, so it stayed off. Grant it on the Clock's Bedtime tab."
        const val NEEDS_DND_OFF = "Quiet could not be turned off: Do Not Disturb access for LEGION was removed."
        const val LOCKED = "The phone has not been unlocked since it restarted."

        fun isUnlocked(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
    }
}

/**
 * What can stop the clock ringing, each as a sentence and the system page that fixes it (ADR 0079).
 * The Clock screen shows every one that applies, in words, never as a silent colour.
 */
object ClockHealth {

    data class Problem(val sentence: String, val button: String, val intent: Intent)

    fun problems(context: Context): List<Problem> = listOfNotNull(
        notifications(context),
        fullScreen(context),
        exact(context),
        battery(context),
        restricted(context),
    )

    /** Bedtime's Quiet needs DND access; checked on the Bedtime tab before saving, never at 10 pm. */
    fun dndProblem(context: Context): Problem? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || nm.isNotificationPolicyAccessGranted) return null
        return Problem(
            "Bedtime turns Quiet on, which needs Do Not Disturb access for LEGION. Without it only the feed block comes on.",
            "Grant access",
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )
    }

    private fun notifications(context: Context): Problem? {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return null
        return Problem(
            "Notifications are off for LEGION, so an alarm can sound but cannot show its screen or buttons.",
            "Turn on",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    private fun fullScreen(context: Context): Problem? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) return null
        return Problem(
            "Full screen notifications are off for LEGION, so an alarm shows as a banner, not over the lock screen.",
            "Allow",
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
        )
    }

    private fun exact(context: Context): Problem? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return null
        return Problem(
            "Exact alarms are not allowed for LEGION, so alarms may ring several minutes late.",
            "Allow",
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
        )
    }

    @SuppressLint("BatteryLife") // a clock is the case this request exists for; Kevin taps it himself
    private fun battery(context: Context): Problem? {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return null
        return Problem(
            "Battery optimisation is on for LEGION. Samsung may put it to sleep and an alarm may not ring.",
            "Exempt LEGION",
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }

    private fun restricted(context: Context): Problem? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (!context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) return null
        return Problem(
            "LEGION's battery use is Restricted, so alarms do not ring at all. Set it to Unrestricted.",
            "Open settings",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }
}
