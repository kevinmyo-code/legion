package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.navigation.voice.NavScreenLauncher
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Brings the nav screen to the front for a voice-started trip or a route preview (mapbox-nav
 * ticket 07: "a trip started by voice while LEGION is in the background brings the nav screen to
 * the front"). Same shape as the other voice tools that land on a screen: [MainActivity] with
 * [MainActivity.EXTRA_ROUTE].
 *
 * **The launch is never trusted, only observed.** Android may refuse a background activity start
 * without throwing, so success is "the process is visible within [WAIT_MS]", read from
 * [ProcessLifecycleOwner]. That proves LEGION is on screen, not that it is on the nav screen (when
 * LEGION was already in front, the route extra is what navigates, and that part is not observable
 * from here). `false` makes the tool say in words that the map could not be shown; guidance is
 * unaffected either way.
 *
 * **Not yet run on a phone**: whether the overlay grant lets the start through from the background is
 * ticket 07's open question, owed on the A25.
 */
class NavScreenOpener(private val context: Context) : NavScreenLauncher {
    override suspend fun bringForward(): Boolean {
        if (!startActivity()) return false
        return withContext(Dispatchers.Main) {
            var waited = 0L
            while (!visible() && waited < WAIT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            visible()
        }
    }

    /** Whether the system accepted the start; says nothing about it having been allowed to show. */
    private fun startActivity(): Boolean {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.NAVIGATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "nav screen launch failed: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "nav screen launch refused: ${e.message}")
            false
        }
    }

    private fun visible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private companion object {
        const val TAG = "NavScreenOpener"
        const val WAIT_MS = 2_000L
        const val POLL_MS = 100L
    }
}
