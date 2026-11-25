// Guard clauses: each step refuses for one reason, and an early return reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.Mnai2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the car link's owner calls on every socket: [session] when a new one opens, [step] on each
 * pass with the unit in range. Its own interface so [CarLinkController] can be driven without an
 * engine ([UnitTokenRenewal] is the one implementation).
 */
interface LinkRenewal {
    fun session()
    fun step(sender: TokenCarrier)
}

/**
 * Keeps the car's own engine sign-in alive without a re-pair (5.6).
 *
 * The engine gives a unit's credential 30 days and caps rotation at 90 days from the pairing, and
 * nothing renewed it: the car needed a re-pair about monthly. This renews it from the phone, which
 * is the only device the engine lets do so, while the two are linked in the car.
 *
 * **Deliver, then retire, and never the other way round.** The engine's `rotate` route kills the
 * old token the moment it answers, so a lost hand-over would leave the car with a dead credential
 * and no way back but a re-pair, which is the very thing this removes. So a renewal MINTS a second
 * credential for the same car, hands it over the live link, and revokes the old one only once the
 * unit says it stored the new one. Between the two the car keeps working on the old one, and a
 * failed delivery costs nothing: the same pending token is handed over again at the next contact.
 * A fresh credential also starts its own 90 days, so the pairing's cap never comes due.
 *
 * **Once a token has been on the wire it is never abandoned.** The unit may hold it with its reply
 * lost, so it is handed over again, and it is dropped only when the engine no longer lists it (it
 * expired, or every unit was revoked), where there is nothing left to lose.
 *
 * Nothing here blocks the sender's loop: [step] does the local work (one datagram) and the engine
 * calls run in one background job at a time.
 */
class UnitTokenRenewal(
    private val store: CarLinkStore,
    private val tokens: UnitTokenSource,
    private val directory: UnitTokenDirectory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Wall clock: the renewal window is days long and must count across reboots. */
    private val wallClock: () -> Long,
) : LinkRenewal {
    @Volatile private var pending: PendingUnitToken? = null
    @Volatile private var read = false
    @Volatile private var askedAt: Long? = null
    @Volatile private var sends = 0
    // Null, not a sentinel: `now - Long.MIN_VALUE` overflows negative and would never send.
    @Volatile private var sentAt: Long? = null
    @Volatile private var job: Job? = null

    /** A new socket's life begins: this session may hand the pending token over again. */
    override fun session() {
        sends = 0
        sentAt = null
    }

    /**
     * One step of the sender's loop, with the unit in range. Hands over a pending token (at most
     * [MAX_SENDS] times per socket, [SEND_EVERY_MS] apart), else asks the engine whether the car's
     * sign-in is near its end, in a background job.
     */
    override fun step(sender: TokenCarrier) {
        val p = loaded()
        if (p != null && dueToSend(sender.nowMs())) {
            sentAt = sender.nowMs()
            if (sender.handToken(p.token, p.vehicleId)) sends++
        }
        val asked = askedAt
        if (asked != null && wallClock() - asked < ASK_EVERY_MS) return
        if (job?.isActive == true) return
        askedAt = wallClock()
        job = scope.launch(io) { withTimeoutOrNull(ENGINE_TIMEOUT_MS) { ask() } }
    }

    /** A few tries per socket, spaced, then quiet until the next one. */
    private fun dueToSend(nowMs: Long): Boolean {
        if (sends >= MAX_SENDS) return false
        val last = sentAt ?: return true
        return nowMs - last >= SEND_EVERY_MS
    }

    /**
     * The unit stored the token whose [fingerprint] this is: that credential is now the car's, so
     * the one it replaces can go. The new id is written FIRST, because an id the phone forgets is a
     * credential nobody can ever revoke; a revoke that then fails leaves the old one to run out its
     * own 30 days, which is the same ending "Forget" already has when the phone is offline.
     */
    fun onTokenStored(fingerprint: ByteArray) {
        val p = pending ?: return
        val old = store.unitDeviceId()
        if (old == p.id) return clearPending()
        if (!store.setUnitDeviceId(p.id)) return
        clearPending()
        if (old != null) scope.launch(io) { tokens.revoke(old) }
    }

    /** A record the store will not clear is kept in memory, so the next step tries the write again. */
    private fun clearPending() {
        if (store.setPendingUnitToken(null)) pending = null
    }

    /** The pending record, read from the store once per process (its token is encrypted). */
    private fun loaded(): PendingUnitToken? {
        if (!read) {
            read = true
            // Reading it registers it with the log redactor; nothing here logs one anyway.
            pending = store.pendingUnitToken()
        }
        return pending
    }

    /** What the engine says: whether a pending credential is still worth waiting for, else whether
     * the car's own is near its end. An engine that cannot be reached says nothing at all. */
    private suspend fun ask() {
        val id = store.unitDeviceId() ?: return
        val live = directory.list() ?: return
        val p = pending
        if (p != null) {
            // The engine no longer has it, so no device can be using it: there is nothing to lose
            // in dropping it, and keeping it would block every later renewal.
            if (live.none { it.id == p.id }) clearPending()
            return
        }
        val row = live.firstOrNull { it.id == id } ?: return
        val expires = row.expiresAtMs ?: return
        if (expires - wallClock() > RENEW_BEFORE_MS) return
        val minted = tokens.mint(HandoverPlan.UNIT_NAME, row.vehicleId) ?: return
        val next = PendingUnitToken(minted.id, minted.token, row.vehicleId)
        if (true) {
            pending = next
        } else {
            // Nothing was written and nothing has been sent, so this credential is reachable by
            // nobody: it goes now rather than sitting live on the engine for 30 days.
            tokens.revoke(minted.id)
        }
    }

    companion object {
        /**
         * How long before expiry a renewal starts: 10 days of the engine's 30, so the car has about
         * ten days of drives to be in range of the phone before anything stops working.
         */
        const val RENEW_BEFORE_MS = 10L * 24 * 60 * 60 * 1000

        /** How often the engine is asked when nothing is pending. */
        const val ASK_EVERY_MS = 6L * 60 * 60 * 1000

        /** Hand-overs per socket, and their spacing: a few tries on a lossy hotspot, then quiet. */
        const val MAX_SENDS = 10
        const val SEND_EVERY_MS = 3_000L

        /** The mint and the list together, bounded like the pairing's own mint. */
        const val ENGINE_TIMEOUT_MS = 20_000L
    }
}
