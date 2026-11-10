package com.kevin.legion.ui.clock

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.clock.AlarmText
import com.kevin.legion.clock.AndroidBedtimeEffects
import com.kevin.legion.clock.ClockGraph
import com.kevin.legion.clock.RingKind
import com.kevin.legion.clock.RingRequest
import com.kevin.legion.clock.RingService
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.SafetyAlarms
import com.kevin.legion.spokenbrief.WakeBrief
import com.kevin.legion.ui.MainActivity
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The ring screen (tickets 02 and 07, ADR 0079), shown over the lock screen by the ring's
 * full-screen intent. Dismiss, Snooze and **Brief me** for an alarm; Stop and +1 min for a timer.
 * Closes itself when nothing is ringing. Direct-boot aware; before the first unlock Brief me is
 * hidden, because the assistant cannot start until then.
 *
 * Brief me is the tap: it dismisses the alarm, asks the assistant service for the sitrep, and opens
 * LEGION so the conversation is on screen. **A plain Dismiss of a morning alarm now also speaks the
 * brief, unprompted, once a day** (ADR 0082, [WakeBrief]); Brief me stays as the replay. An
 * emergency [RingKind.ALERT] shows its facts and one Dismiss, after which the line is said
 * ([SafetyAlarms]).
 */
class RingActivity : ComponentActivity() {
    private var problem by mutableStateOf<String?>(null)

    /** Set while Brief me hands over, so the ring emptying does not close the screen first. */
    @Volatile private var holdOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val unlocked = AndroidBedtimeEffects.isUnlocked(this)
        lifecycleScope.launch {
            RingService.ringing.first { it.isEmpty() && !holdOpen }
            finish()
        }
        setContent {
            SoftTheme {
                val ringing by RingService.ringing.collectAsStateWithLifecycle()
                val top = ringing.lastOrNull()
                RingScreen(
                    now = AlarmText.clockOf(System.currentTimeMillis(), ZoneId.systemDefault()),
                    ring = top,
                    canBrief = unlocked,
                    problem = problem,
                    actions = RingActions(
                        onDismiss = { top?.let { dismiss(it); WakeBrief.afterDismiss(this, it.kind, unlocked) } },
                        onSnooze = { top?.let { ClockGraph.controller(this).alarms.snooze(it.id) } },
                        onBrief = { top?.let(::brief) },
                        onAddMinute = { top?.let { ClockGraph.controller(this).timers.addMinute(it.id) } },
                        onClose = ::finish,
                    ),
                )
            }
        }
    }

    private fun dismiss(r: RingRequest) {
        val clock = ClockGraph.controller(this)
        when (r.kind) {
            RingKind.ALARM -> clock.alarms.dismiss(r.id)
            RingKind.TIMER -> clock.timers.remove(r.id)
            RingKind.ALERT -> SafetyAlarms.dismissed(this, r.id)
        }
    }

    private fun brief(r: RingRequest) {
        holdOpen = true
        WakeBrief.briefedByTap(this) // the tap is today's brief; no second, unprompted one
        val refusal = briefRefusal()
        dismiss(r)
        if (refusal != null) {
            problem = refusal
            lifecycleScope.launch {
                delay(PROBLEM_SHOW_MS)
                finish()
            }
            return
        }
        startService(Intent(this, AriaForegroundService::class.java).setAction(AriaForegroundService.ACTION_BRIEF))
        openLegion()
    }

    /** Said on this screen, because the strip's own refusal notice is behind the lock screen. */
    private fun briefRefusal(): String? = when {
        !GeminiKeyProvider.hasKey() -> "No brief: no Gemini key is saved. The alarm is dismissed."
        !micAllowed() -> "No brief: the microphone is not allowed for LEGION. The alarm is dismissed."
        !online() -> "No brief: the phone is offline. The alarm is dismissed."
        else -> null
    }

    private fun micAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return cm == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun openLegion() {
        val main = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    startActivity(main)
                    finish()
                }
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            startActivity(main)
            finish()
        }
    }

    @Suppress("DEPRECATION") // the window flags are the API 24-26 form of setShowWhenLocked/setTurnScreenOn
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            window.addFlags(flags)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val PROBLEM_SHOW_MS = 4_000L

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
