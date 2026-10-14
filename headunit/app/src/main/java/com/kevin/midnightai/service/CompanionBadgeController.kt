package com.kevin.midnightai.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kevin.midnightai.ui.CompanionBadge

/**
 * The floating **companion badge** — a small, non-focusable
 * TYPE_APPLICATION_OVERLAY window docked to the right edge, shown while a
 * music app the driver opened has the foreground. Just the avatar (tap to
 * talk) and minimal music transport (prev/play-pause/next) below it - touches
 * inside reach Compose, touches outside pass through to the app underneath.
 *
 * Replaces the earlier Split-Screen Companion Mode (native multi-window +
 * a full-height floating panel), removed 2026-07-08: FLAG_ACTIVITY_LAUNCH_ADJACENT
 * proved unreliable from Midnight AI's fullscreen home-screen starting state
 * (a documented Android limitation, not a bug), and even the panel fallback
 * only ever approximated a real split - the other app has no idea we exist
 * and doesn't reflow around us. This badge doesn't try to be a split at all:
 * it's just a way to stay reachable (tap-to-talk, transport) while another
 * app owns the screen. The driver returns to Midnight AI via Home, same as
 * any other app switch - no in-badge "return" affordance needed.
 *
 * Compose can't run in a raw window without ViewTree owners, so [OverlayOwners]
 * supplies the lifecycle / saved-state / view-model owners.
 *
 * All methods must run on the main thread. Requires the SYSTEM_ALERT_WINDOW
 * grant; [show] no-ops (logs) if it's missing so a denied grant is never a crash.
 */
class CompanionBadgeController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owners = OverlayOwners()

    private var root: ComposeView? = null

    /** Builds and adds the badge. No-op if already shown. Safe if the grant is missing. */
    fun show() {
        if (root != null) return
        owners.start()
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setContent {
                CompanionBadge(appContext)
            }
        }
        try {
            windowManager.addView(view, layoutParams())
            root = view
        } catch (e: Exception) {
            Log.w(TAG, "Could not add companion badge (overlay grant missing?): ${e.message}")
            owners.destroy()
        }
    }

    /** Removes the badge and tears down the Compose owners. No-op if not shown. */
    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        owners.destroy()
    }

    val isShowing: Boolean get() = root != null

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Don't steal focus/keys from the app underneath; touches inside
            // the badge still reach Compose, touches outside pass through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "CompanionBadge"
    }
}
