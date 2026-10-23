package com.kevin.legion.dumb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.kevin.legion.MidnightApplication
import com.kevin.legion.ui.dumb.DumbBounceActivity

/**
 * Sends a hidden app back home (ADR 0062). Listens for window-state changes only
 * (`res/xml/dumb_bounce_service.xml`: no window content, no other event type) and, when dumb mode
 * is on and [DumbBounce] says the window belongs to a hidden app, presses Home and shows LEGION's
 * "Dumb mode is on" screen over the result.
 *
 * Android only runs this once the user turns it on in Accessibility settings, which for a sideloaded
 * build first needs "Allow restricted settings" under App info. It does nothing while dumb mode is
 * off, and nothing it does reads what is on the screen.
 */
class DumbBounceService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(event.packageName?.toString())
        }
    }

    private fun handleWindowChange(packageName: String?) {
        val live = (application as? MidnightApplication)?.dumb?.takeIf { it.controller.state.value.on }
        if (live != null && packageName != null && shouldBounce(live, packageName)) bounce(live, packageName)
    }

    private fun shouldBounce(graph: DumbModeGraph, packageName: String): Boolean =
        runCatching { graph.bounce.shouldBounce(packageName) }
            .onFailure { Log.w(TAG, "Bounce check failed; leaving the window alone", it) }
            .getOrDefault(false)

    override fun onInterrupt() = Unit

    private fun bounce(graph: DumbModeGraph, packageName: String) {
        // Home first, so Back from the bounce screen lands on LEGION's home and not on the hidden app.
        performGlobalAction(GLOBAL_ACTION_HOME)
        val label = graph.appLabel(packageName)
        main.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, DumbBounceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(DumbBounceActivity.EXTRA_APP_LABEL, label),
                )
            }.onFailure { Log.w(TAG, "Could not show the bounce screen", it) }
        }, SHOW_DELAY_MS)
    }

    private companion object {
        const val TAG = "DumbBounce"
        const val SHOW_DELAY_MS = 200L
    }
}
