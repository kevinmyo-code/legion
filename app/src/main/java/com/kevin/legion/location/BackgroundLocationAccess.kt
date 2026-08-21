package com.kevin.legion.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Resolves what LEGION's location access actually buys the driver right now
 * (`.scratch/location-intelligence/issues/01-background-location.md`, settled decision 11).
 *
 * ### Why three states, not two
 *
 * Android's own permission model already forks foreground and background location into two
 * separate grants - `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` first, `ACCESS_BACKGROUND_LOCATION`
 * only afterward, and from API 30 the system will not even offer the second one in a normal
 * runtime dialog (it routes the driver to Settings' "Allow all the time" picker instead). Collapsing
 * that into a boolean would force a lie in one direction or the other: call foreground-only "on" and
 * geofences quietly never fire; call it "off" and a driver who deliberately declined "all the time"
 * (a real, reasonable choice - see the ticket) reads as having refused location outright, when place
 * reminders and hazard checks both still work while LEGION is open. **A refusal degrades in words,
 * never silently** (the ticket's own rule) - the three states below are what makes that possible,
 * because each one is a different sentence, not a different shade of the same one.
 *
 * | State | Foreground | Background | What actually works |
 * |---|---|---|---|
 * | [Granted] | yes | yes | Place reminders and hazard alerts work with the app closed. |
 * | [ForegroundOnly] | yes | no / not asked | Both only work while LEGION is open. |
 * | [None] | no | no | Neither works. |
 *
 * Kept as a plain sealed class rather than a boolean pair so a caller cannot forget the middle
 * state exists - `when` over this is exhaustive at compile time.
 */
sealed class LocationAccessState {
    /** Background location granted (implies foreground is too - Android will not grant the one
     * without the other). Geofences fire and hazard checks run with the app closed. */
    object Granted : LocationAccessState()

    /** Fine or coarse location granted, but background was either refused or never asked yet.
     * This is a legitimate, common resting state - most people do not want to hand out "all the
     * time" location the first time they're asked - and it is NOT an error state. */
    object ForegroundOnly : LocationAccessState()

    /** No location permission at all. Place reminders, hazard checks, and "where am I" all have
     * nothing to work from. */
    object None : LocationAccessState()
}

/**
 * Reads real permission state and resolves it to a [LocationAccessState]. Pure resolution logic
 * lives in [resolveLocationAccess] below so it can be unit tested without an Android permission
 * check in the loop - this function is the one Android-touching wrapper around it.
 */
object BackgroundLocationAccess {
    fun current(context: Context): LocationAccessState {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // Below API 29 there is no separate background grant to check - foreground access IS
        // background access, because the permission itself didn't exist yet. Treating it as
        // granted on those OS versions is not a shortcut, it's the honest answer: nothing was
        // ever asked because nothing needed asking.
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            fine || coarse
        }
        return resolveLocationAccess(foregroundGranted = fine || coarse, backgroundGranted = background)
    }
}

/**
 * The pure fork, isolated from [BackgroundLocationAccess.current] so it's testable without
 * Robolectric or a `Context` at all. Background can never be true without foreground also being
 * true in real Android behaviour (the OS enforces this at grant time), but this function doesn't
 * assume that invariant holds - it answers honestly off whatever it's given, so a caller that
 * somehow observes background=true/foreground=false (a stale read mid-transition, say) still gets
 * [LocationAccessState.Granted] rather than a state that contradicts what background access implies.
 */
fun resolveLocationAccess(foregroundGranted: Boolean, backgroundGranted: Boolean): LocationAccessState =
    when {
        backgroundGranted -> LocationAccessState.Granted
        foregroundGranted -> LocationAccessState.ForegroundOnly
        else -> LocationAccessState.None
    }
