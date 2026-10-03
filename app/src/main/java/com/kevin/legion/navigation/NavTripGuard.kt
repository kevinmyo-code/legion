package com.kevin.legion.navigation

/**
 * Where a guided trip is. [NOT_SET_UP] (no token) and [TOKEN_REFUSED] (Mapbox said the token is
 * bad) are decided before a guard exists, from the token alone; neither ever has a trip.
 */
enum class NavPhase { NOT_SET_UP, TOKEN_REFUSED, IDLE, REQUESTING, GUIDING, ARRIVED, FAILED }

/**
 * The billing guard for [MapboxNavController], pure so a test can pin it (mapbox-nav spike,
 * ADR 0054, ticket 07's rule).
 *
 * Mapbox bills a trip from `startTripSession()`: with a route set it is an Active Guidance trip,
 * with none it is a **Free Drive** trip, billed the same way. LEGION never wants a Free Drive trip,
 * so the whole rule is one invariant: **a session runs if and only if the phase is [NavPhase.GUIDING]**,
 * and the only way into GUIDING is [routesReady] with at least one route, in the REQUESTING phase.
 * Every method that can end a trip returns whether the CALLER must stop the session, so the
 * controller cannot forget: it does what the return value says and nothing else.
 *
 * A late route response after a cancel lands in IDLE and is refused ([routesReady] returns false),
 * which is what stops "I pressed Stop while it was thinking" from starting a trip anyway.
 */
class NavTripGuard {
    var phase: NavPhase = NavPhase.IDLE
        private set

    /** True exactly while a Mapbox trip session should be running. */
    val sessionShouldRun: Boolean get() = phase == NavPhase.GUIDING

    /** A route request is about to go out. False when one is already in flight or a trip runs. */
    fun requestStarted(): Boolean {
        if (phase == NavPhase.REQUESTING || phase == NavPhase.GUIDING) return false
        phase = NavPhase.REQUESTING
        return true
    }

    /** Routes came back. True means: set them AND start the trip session now. */
    fun routesReady(routeCount: Int): Boolean {
        val accepted = phase == NavPhase.REQUESTING && routeCount > 0
        if (phase == NavPhase.REQUESTING) phase = if (accepted) NavPhase.GUIDING else NavPhase.FAILED
        return accepted
    }

    /** The request failed or was cancelled by the SDK. No session was ever started. */
    fun requestFailed() {
        if (phase == NavPhase.REQUESTING) phase = NavPhase.FAILED
    }

    /** User pressed Stop. Returns true if a session is running and must be stopped. */
    fun stopRequested(): Boolean = end(NavPhase.IDLE)

    /** The final destination was reached. Returns true if a session must be stopped. */
    fun arrived(): Boolean = end(NavPhase.ARRIVED)

    /** The screen left composition (or its ViewModel was cleared). Same effect as Stop. */
    fun screenLeft(): Boolean = end(NavPhase.IDLE)

    private fun end(next: NavPhase): Boolean {
        val wasRunning = sessionShouldRun
        val live = phase == NavPhase.REQUESTING || phase == NavPhase.GUIDING
        // A terminal verdict (ARRIVED, FAILED) is only cleared by an explicit return to IDLE; an
        // arrival signal that lands when nothing is live changes nothing.
        if (live || next == NavPhase.IDLE) phase = next
        return wasRunning
    }
}
