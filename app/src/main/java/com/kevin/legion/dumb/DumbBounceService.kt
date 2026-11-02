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
 * Sends a blocked app back home (ADR 0062, ADR 0066). Listens for window-state changes only
 * (`res/xml/dumb_bounce_service.xml`: no window content, no other event type) and, when Matrix mode
 * is on or "Block feed apps always" is, and [DumbBounce] says the window belongs to a blocked app,
 * presses Home and shows LEGION's bounce screen over the result.
 *
 * A 15 minute pass (ADR 0066) ends while the app may still be open, with no window change to notice
 * it. So the service remembers the last foreground app from the events it already gets
 * ([ForegroundTracker]) and posts a check for the instant each running pass ends; the check bounces
 * only if that app is still the last one seen. `Handler` delays run on uptime, so a phone in deep
 * sleep checks late; the next window change is the backstop, because every event re-asks the ladder.
 *
 * Android only runs this once the user turns it on in Accessibility settings, which for a sideloaded
 * build first needs "Allow restricted settings" under App info. It does nothing while both switches
 * are off, and nothing it does reads what is on the screen.
 */
class DumbBounceService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val foreground = ForegroundTracker()

    /** One pending expiry check per passed package, so a re-arm replaces rather than piles up. */
    private val expiryChecks = HashMap<String, Runnable>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // A restart drops posted callbacks; re-arm for any pass the prefs say is still running.
        graph()?.let { g -> runCatching { g.feedPass.running() }.getOrDefault(emptyMap()).forEach(::armExpiry) }
        // Back on: take down the "Feed blocker is off" notice (ADR 0066, 2026-11-02).
        graph()?.let { g -> runCatching { g.feedBlockWatch.sync(accessibilityOn = true) } }
    }

    /**
     * The system unbinds when the user turns the service off in Accessibility settings: the one
     * immediate signal, with no polling. Says so if the feed block is on (ADR 0066, 2026-11-02). Any
     * other unbind is cleared again by [onServiceConnected] or the next app-foreground check.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        graph()?.let { g -> runCatching { g.feedBlockWatch.sync(accessibilityOn = false) } }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(event.packageName?.toString())
        }
    }

    private fun graph(): DumbModeGraph? = (application as? MidnightApplication)?.dumb

    private fun handleWindowChange(packageName: String?) {
        val graph = graph() ?: return
        // Both switches off: no lookups at all. A pass only exists while the feed block is on.
        if (!graph.bounceActive() || packageName == null) return
        val countsAsApp = runCatching { graph.countsAsForegroundApp(packageName) }.getOrDefault(false)
        foreground.note(packageName, countsAsApp)
        if (shouldBounce(graph, packageName)) {
            bounce(graph, packageName)
        } else {
            runCatching { graph.feedPass.grantedUntilMs(packageName) }.getOrNull()?.let { armExpiry(packageName, it) }
        }
    }

    private fun armExpiry(packageName: String, grantedUntilMs: Long) {
        expiryChecks.remove(packageName)?.let(main::removeCallbacks)
        val check = Runnable {
            expiryChecks.remove(packageName)
            val graph = graph() ?: return@Runnable
            val due = FeedPassRule.bounceAtExpiry(
                foreground.last, packageName, grantedUntilMs, System.currentTimeMillis(),
            )
            if (due && graph.bounceActive() && shouldBounce(graph, packageName)) bounce(graph, packageName)
        }
        expiryChecks[packageName] = check
        val delay = (grantedUntilMs - System.currentTimeMillis()).coerceIn(0L, FeedPassRule.PASS_MS) + EXPIRY_SLACK_MS
        main.postDelayed(check, delay)
    }

    private fun shouldBounce(graph: DumbModeGraph, packageName: String): Boolean =
        runCatching { graph.bounce.shouldBounce(packageName) }
            .onFailure { Log.w(TAG, "Bounce check failed; leaving the window alone", it) }
            .getOrDefault(false)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        expiryChecks.clear()
        super.onDestroy()
    }

    private fun bounce(graph: DumbModeGraph, packageName: String) {
        // Home first, so Back from the bounce screen lands on the home screen and not on the blocked app.
        performGlobalAction(GLOBAL_ACTION_HOME)
        val label = graph.appLabel(packageName)
        val matrix = graph.controller.state.value.on
        main.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, DumbBounceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(DumbBounceActivity.EXTRA_APP_LABEL, label)
                        .putExtra(DumbBounceActivity.EXTRA_PACKAGE, packageName)
                        .putExtra(DumbBounceActivity.EXTRA_MATRIX, matrix),
                )
            }.onFailure { Log.w(TAG, "Could not show the bounce screen", it) }
        }, SHOW_DELAY_MS)
    }

    private companion object {
        const val TAG = "DumbBounce"
        const val SHOW_DELAY_MS = 200L

        /** Fire just after the pass ends, so the ladder already reads it as spent. */
        const val EXPIRY_SLACK_MS = 500L
    }
}
