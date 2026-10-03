package com.kevin.legion.navigation

/**
 * The billing guard for [MapboxNavController], pure so a test can pin it (mapbox-nav ADR 0054,
 * tickets 07 and 10).
 *
 * Mapbox bills a trip from `startTripSession()`: with a route set it is an Active Guidance trip,
 * with none it is a **Free Drive** trip, billed the same way. LEGION never wants a Free Drive trip,
 * so the whole rule is one invariant: **a session runs if and only if the phase is [NavPhase.GUIDING]**.
 * The only way into GUIDING is [startRequested] from [NavPhase.PREVIEW], and the only way into
 * PREVIEW is [routesReady] with at least one route, in the REQUESTING phase. Every method that can
 * end a trip returns whether the CALLER must stop the session, so the controller cannot forget: it
 * does what the return value says and nothing else.
 *
 * **Ticket 07 / 10 change: leaving the screen no longer ends a trip.** The spike's `screenLeft()`
 * ended GUIDING; the controller is app-owned now and a trip ends only on arrival, End, or process
 * death. [screenLeft] therefore never returns true. What it still does is drop a PREVIEW or an
 * in-flight request (neither is a trip, so nothing is billed) and clear a finished trip's verdict.
 *
 * A late route response after a cancel lands in IDLE and is refused ([routesReady] returns false),
 * which is what stops "I pressed End while it was thinking" from starting a trip anyway.
 */
class NavTripGuard {
    var phase: NavPhase = NavPhase.IDLE
        private set

    /** True exactly while a Mapbox trip session should be running. */
    val sessionShouldRun: Boolean get() = phase == NavPhase.GUIDING

    /**
     * A route request is about to go out. False while one is already in flight or a trip runs.
     * A new request from PREVIEW (changed stop or avoid before Start) is allowed: it replaces the
     * preview, and no session exists to disturb.
     */
    fun requestStarted(): Boolean {
        if (phase == NavPhase.REQUESTING || phase == NavPhase.GUIDING) return false
        phase = NavPhase.REQUESTING
        return true
    }

    /** Routes came back for a preview request. True means: show the preview (no session yet). */
    fun routesReady(routeCount: Int): Boolean {
        val accepted = phase == NavPhase.REQUESTING && routeCount > 0
        if (phase == NavPhase.REQUESTING) phase = if (accepted) NavPhase.PREVIEW else NavPhase.FAILED
        return accepted
    }

    /** The request failed or was cancelled by the SDK. No session was ever started. */
    fun requestFailed() {
        if (phase == NavPhase.REQUESTING) phase = NavPhase.FAILED
    }

    /**
     * Start was pressed. True only from PREVIEW: set the routes AND start the trip session now.
     * From anywhere else (nothing previewed, already guiding) it is refused and nothing changes.
     */
    fun startRequested(): Boolean {
        if (phase != NavPhase.PREVIEW) return false
        phase = NavPhase.GUIDING
        return true
    }

    /**
     * A request made to CHANGE a preview (a stop, an avoid) failed or could not be applied: the old
     * preview still stands, so go back to it. A no-op in any other phase.
     */
    fun abandonRequestToPreview() {
        if (phase == NavPhase.REQUESTING) phase = NavPhase.PREVIEW
    }

    /** Setting the routes or starting the session did not work out; back to a preview with no session. */
    fun startFailed() {
        if (phase == NavPhase.GUIDING) phase = NavPhase.FAILED
    }

    /**
     * End was pressed. Returns true if a session is running and must be stopped. A running trip
     * ends in [NavPhase.ENDED]; a preview or request is just discarded to IDLE (it was never a trip).
     */
    fun endRequested(): Boolean {
        val wasRunning = sessionShouldRun
        phase = if (wasRunning) NavPhase.ENDED else NavPhase.IDLE
        return wasRunning
    }

    /** The final destination was reached. Returns true if a session must be stopped. */
    fun arrived(): Boolean {
        val wasRunning = sessionShouldRun
        // An arrival signal that lands when nothing is guiding changes nothing.
        if (wasRunning) phase = NavPhase.ARRIVED
        return wasRunning
    }

    /**
     * The nav screen left composition. **A GUIDING trip is untouched** (ticket 07); a preview or an
     * in-flight request is dropped and a finished trip's verdict cleared. Always false: it never
     * asks the caller to stop a session.
     */
    fun screenLeft(): Boolean {
        if (phase != NavPhase.GUIDING) phase = NavPhase.IDLE
        return false
    }

    /** The "Done" button on an arrived / ended / failed sheet. Never touches a running trip. */
    fun dismissed() {
        if (phase == NavPhase.ARRIVED || phase == NavPhase.ENDED || phase == NavPhase.FAILED) phase = NavPhase.IDLE
    }

    /** The token changed under a trip: it ends exactly as if End had been pressed. */
    fun tokenChanged(): Boolean = endRequested().also { phase = NavPhase.IDLE }
}
