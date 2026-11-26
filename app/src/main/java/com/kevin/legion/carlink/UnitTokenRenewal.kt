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
 * **A renewal belongs to the pairing that minted it, and dies with it.** The store drops the
 * pending record with the link key it was written under, and nothing is handed over unless the
 * record is still the store's, still names the car's own credential, and was lately listed by the
 * engine. Without all three, a pairing's leftover token reached the NEXT unit, bound the car back
 * to the pairing before it and revoked the credential the new pairing had just minted.
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
    @Volatile private var nextAskAt: Long? = null
    /** When the engine last listed [pending] as live. Null = not since this object was made. */
    @Volatile private var listedAt: Long? = null
    @Volatile private var sends = 0
    // Null, not a sentinel: `now - Long.MIN_VALUE` overflows negative and would never send.
    @Volatile private var sentAt: Long? = null
    @Volatile private var job: Job? = null

    /**
     * A new socket's life begins: this session may hand the pending token over again.
     *
     * The record is re-read here so memory agrees with the store at every socket's start. The
     * store, not this object, is the authority on which renewal is live ([mine] re-checks it at
     * every use), because Forget and a new pairing both end a renewal without going through here.
     * Re-read rather than cleared: [ask] and [onTokenStored] read [pending] directly, and a null
     * they did not expect would mint over a token that may already be on the unit.
     */
    override fun session() {
        sends = 0
        sentAt = null
        val fresh = store.pendingUnitToken()
        val held = pending
        // The record this object held is not the store's any more and is not the car's credential
        // either: the pairing it belonged to ended it while this object was not looking.
        if (held != null && held.id != fresh?.id && held.id != store.unitDeviceId()) end(held)
        // A different record is a different credential: what the engine said about the last one
        // says nothing about this one.
        if (fresh?.id != pending?.id) listedAt = null
        pending = fresh
        read = true
    }

    /**
     * A pending renewal whose pairing is over: the record goes and the credential with it. No
     * device can be given that token now, so revoking it beats leaving it live on the engine for
     * its 30 days. The store's own record is cleared only when it is still this one.
     */
    private fun end(p: PendingUnitToken) {
        pending = null
        listedAt = null
        if (store.pendingUnitId() == p.id) store.setPendingUnitToken(null)
        scope.launch(io) { tokens.revoke(p.id) }
    }

    /**
     * One step of the sender's loop, with the unit in range. Asks the engine (at most once every
     * [ASK_EVERY_MS]) whether a pending credential is still live, or whether the car's sign-in is
     * near its end, and hands a pending token over at most [MAX_SENDS] times per socket,
     * [SEND_EVERY_MS] apart.
     *
     * The ask is scheduled before the hand-over, because a token the engine no longer lists must
     * never reach the unit: the unit would store a dead credential, its next upload would 401, and
     * the screen would ask for the re-pair this exists to remove.
     */
    override fun step(sender: TokenCarrier) {
        maybeAsk()
        val p = deliverable() ?: return
        if (!dueToSend(sender.nowMs())) return
        sentAt = sender.nowMs()
        if (sender.handToken(p.token, p.vehicleId)) sends++
    }

    private fun maybeAsk() {
        val next = nextAskAt
        if (next != null && wallClock() < next) return
        if (job?.isActive == true) return
        nextAskAt = wallClock() + ASK_EVERY_MS
        job = scope.launch(io) {
            if (withTimeoutOrNull(ENGINE_TIMEOUT_MS) { ask() } == null) askAgainSoon()
        }
    }

    /** The engine said nothing (unreachable, or too slow): ask again in minutes, not in hours. */
    private fun askAgainSoon() {
        nextAskAt = wallClock() + ASK_RETRY_MS
    }

    /**
     * The pending token this step may hand over: this pairing's own record, and one the engine
     * listed as live within [TRUSTED_FOR_MS]. A record whose liveness is older than that waits for
     * the next answer from the engine rather than going over the wire on trust.
     */
    private fun deliverable(): PendingUnitToken? {
        val p = mine() ?: return null
        val seen = listedAt ?: return null
        return if (wallClock() - seen < TRUSTED_FOR_MS) p else null
    }

    /**
     * The record in memory, confirmed against the store's plain fields (no token is opened). The
     * store drops a pending renewal with the link key it was minted under, so this is what keeps a
     * record from outliving its pairing inside a socket that survived one: "Pair again" left the
     * old pairing's token armed and the next pass handed it to the NEW unit, which rebound the car
     * and revoked the credential the new pairing had just minted (RED review round 3).
     */
    private fun mine(): PendingUnitToken? {
        val p = loaded() ?: return null
        val car = store.unitDeviceId()
        // Already the car's: the unit answered and only the record's own clearing is still owed.
        if (car == p.id) return null
        if (store.pendingUnitId() == p.id && p.replaces == car) return p
        end(p)
        return null
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
        if (!fingerprint.contentEquals(Mnai2.fingerprint(p.token))) return
        val old = store.unitDeviceId()
        if (old == p.id) return clearPending()
        // The store is the authority on which renewal is live: an answer about a record that a
        // pairing took with it must never rebind the car or revoke what that pairing minted.
        if (store.pendingUnitId() != p.id || p.replaces != old) return
        if (!store.setUnitDeviceId(p.id)) return
        clearPending()
        if (old != null) scope.launch(io) { tokens.revoke(old) }
    }

    /** A record the store will not clear is kept in memory, so the next step tries the write again. */
    private fun clearPending() {
        if (store.setPendingUnitToken(null)) {
            pending = null
            listedAt = null
        }
    }

    /**
     * The pending record. Read from the store at most once before the first [session] (its token is
     * encrypted, so it is not re-read per step); from then on [session] keeps it in agreement.
     */
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
        val live = directory.list() ?: return askAgainSoon()
        val p = mine()
        if (p != null) {
            // The engine no longer has it, so no device can be using it: there is nothing to lose
            // in dropping it, and keeping it would block every later renewal.
            if (live.any { it.id == p.id }) listedAt = wallClock() else clearPending()
            return
        }
        val row = live.firstOrNull { it.id == id } ?: return
        val expires = row.expiresAtMs ?: return
        if (expires - wallClock() > RENEW_BEFORE_MS) return
        val minted = tokens.mint(HandoverPlan.UNIT_NAME, row.vehicleId) ?: return
        // Forget clears the unit's id, and this job may have been in flight across it: a pending
        // record written now would belong to a pairing that no longer exists, and the next pairing
        // would hand it over. Nothing has been sent, so the credential goes instead.
        if (store.unitDeviceId() != id) {
            tokens.revoke(minted.id)
            return
        }
        val next = PendingUnitToken(minted.id, minted.token, row.vehicleId, replaces = id)
        if (store.setPendingUnitToken(next)) {
            pending = next
            // Minted this moment, so the engine has just listed it by issuing it.
            listedAt = wallClock()
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

        /** How often the engine is asked. */
        const val ASK_EVERY_MS = 6L * 60 * 60 * 1000

        /**
         * After an engine that said nothing. Minutes, because a pending token is handed over only
         * while the engine has lately listed it, and a car is often in range of the phone long
         * before it is in range of a working network.
         */
        const val ASK_RETRY_MS = 10L * 60 * 1000

        /**
         * How long the engine's word that a pending credential is live stands. One ask apart, so a
         * record is re-confirmed on the same cadence it is asked about; past it the hand-over waits
         * for the engine rather than risking a revoked or expired token on the unit.
         */
        const val TRUSTED_FOR_MS = ASK_EVERY_MS

        /** Hand-overs per socket, and their spacing: a few tries on a lossy hotspot, then quiet. */
        const val MAX_SENDS = 10
        const val SEND_EVERY_MS = 3_000L

        /** The mint and the list together, bounded like the pairing's own mint. */
        const val ENGINE_TIMEOUT_MS = 20_000L
    }
}
