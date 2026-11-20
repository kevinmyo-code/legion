// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink.protocol

import java.net.InetAddress

/** A fix as the location source gave it, with its monotonic (elapsed-realtime) time. */
class FixSample(val elapsedMs: Long, val fix: PhoneFix) {
    override fun toString() = "FixSample(redacted)"
}

/**
 * One datagram to send, to the accepted HELLO's source address AND port (rev 5, section 4: the unit
 * listens on the socket its HELLOs come from, not on 17311).
 */
class Outgoing(val bytes: ByteArray, val to: InetAddress, val port: Int) {
    override fun toString() = "Outgoing(${bytes.size} bytes)"
}

/**
 * The phone's MNAI2 side, section 4, without a socket or a clock: [onDatagram] for what arrived,
 * [tick] for what is due, [revoke] for "Forget head unit". The Android sender owns the socket.
 *
 * - A HELLO is accepted only if it opens and its (session, counter) is new (4.3); only accepted
 *   HELLOs move the destination, and a new address needs two of them in a row (rev 5). A session
 *   counts as new only if this end has never accepted it,
 *   which is stricter than "a different session" and costs nothing (a unit draws a fresh random one).
 * - HELLOs under the reserve key (3.7) are authenticated and replay-checked the same way and feed
 *   [LinkKeyRing.onHello]; nothing is ever sent under the reserve.
 * - Fixes go out only while accepted HELLOs keep arriving (stops after 3 missed HELLO intervals), at
 *   the requested interval, each echoing the newest accepted challenge.
 * - A fresh random 16-byte session per key, counter from 1 (4.1): a new link key never continues an
 *   old session, and no challenge held from before a key change is echoed.
 */
class Mnai2Feed(
    private val ring: LinkKeyRing,
    private val newSession: () -> ByteArray,
    private val onLan: (InetAddress) -> Boolean,
    private val zoneId: () -> String?,
    private val onRingEvent: (RingEvent) -> Unit = {},
) {
    private class Replay {
        var session: ByteArray? = null
        var counter = 0L
        val seen = ArrayDeque<ByteArray>()

        fun accept(s: ByteArray, c: Long): Boolean {
            val last = session
            val ok = if (last != null && last.contentEquals(s)) c > counter else seen.none { it.contentEquals(s) }
            if (!ok) return false
            if (last == null || !last.contentEquals(s)) {
                seen.addLast(s)
                if (seen.size > SEEN_SESSIONS) seen.removeFirst()
            }
            session = s
            counter = c
            return true
        }
    }

    private var keyRef: ByteArray? = null
    private var reserveRef: ByteArray? = null
    private var keys: DirectionKeys? = null
    private var reserveKeys: DirectionKeys? = null
    private var currentReplay = Replay()
    private var reserveReplay = Replay()

    private var session: ByteArray = ByteArray(0)
    private var counter = 0L

    private var destination: InetAddress? = null
    private var destinationPort = 0
    // A new source seen once; it becomes the destination at its second accepted HELLO (rev 5).
    private var candidate: InetAddress? = null
    private var candidatePort = 0
    private var challenge: ByteArray? = null
    private var challengeAt = 0L
    private var intervalMs = 0L
    private var lastHelloAt = 0L
    private var lastFixAt: Long? = null
    private var lastFixElapsed = Long.MIN_VALUE
    // ZONEs still owed to this unit session: sent on its first 3 accepted HELLOs (R4, 4.3 rev 6).
    private var zonesLeft = 0
    private var accepted = 0L

    /** True while accepted HELLOs keep arriving under the current key. */
    @Synchronized fun inRange(nowMs: Long): Boolean =
        challenge != null && nowMs - lastHelloAt <= MISSED_INTERVALS * minOf(intervalMs, MAX_HELLO_EVERY_MS)

    /** HELLOs accepted (authenticated, new, from the destination) since this feed started. */
    @Synchronized fun acceptedHellos(): Long = accepted

    /** Requested fix interval of the newest accepted HELLO, 0 when none. */
    @Synchronized fun requestedIntervalMs(): Long = intervalMs

    @Synchronized fun onDatagram(
        bytes: ByteArray,
        length: Int,
        from: InetAddress,
        fromPort: Int,
        nowMs: Long,
    ): List<Outgoing> {
        if (!onLan(from)) return emptyList()
        syncKeys()
        val cur = keys
        cur?.let { k -> Mnai2.openFromUnit(k.u2p, bytes, length) }?.let { d ->
            return onHello(d, KeySlot.CURRENT, from, fromPort, nowMs)
        }
        reserveKeys?.let { k -> Mnai2.openFromUnit(k.u2p, bytes, length) }?.let { d ->
            onHello(d, KeySlot.RESERVE, from, fromPort, nowMs)
        }
        return emptyList()
    }

    private fun onHello(
        d: Mnai2Datagram,
        slot: KeySlot,
        from: InetAddress,
        fromPort: Int,
        nowMs: Long,
    ): List<Outgoing> {
        if (d.type != Mnai2.HELLO) return emptyList()
        val hello = Mnai2.readHello(d.body) ?: return emptyList()
        val replay = if (slot == KeySlot.CURRENT) currentReplay else reserveReplay
        val newUnitSession = replay.session?.contentEquals(d.session) != true
        if (!replay.accept(d.session, d.counter)) return emptyList()
        val event = ring.onHello(slot, nowMs)
        if (event != RingEvent.NONE) onRingEvent(event)
        if (slot == KeySlot.RESERVE) {
            syncKeys()
            return emptyList()
        }
        if (!adopt(from, fromPort)) return emptyList()
        accepted++
        challenge = hello.challenge
        challengeAt = nowMs
        intervalMs = hello.intervalMs
        lastHelloAt = nowMs
        if (newUnitSession) zonesLeft = ZONE_REPEATS
        return zone(nowMs)
    }

    /**
     * What is due at [nowMs]: a fix when [sendFixes] is on, the unit is in range, the interval has
     * passed and [latest] is newer than the last fix sent and under 10 s old (the unit refuses older).
     */
    @Synchronized fun tick(nowMs: Long, latest: FixSample?, sendFixes: Boolean): List<Outgoing> {
        syncKeys()
        if (!sendFixes || latest == null || !inRange(nowMs)) return emptyList()
        val last = lastFixAt
        if ((last != null && nowMs - last < intervalMs) || latest.elapsedMs <= lastFixElapsed) return emptyList()
        val age = nowMs - latest.elapsedMs
        if (age < 0 || age > MAX_AGE_MS) return emptyList()
        val out = send(Mnai2.FIX, Mnai2.fixBody(challenge!!, latest.fix.aged(age))) ?: return emptyList()
        lastFixAt = nowMs
        lastFixElapsed = latest.elapsedMs
        return listOf(out)
    }

    /** A REVOKE (4.3) when a challenge from the last 10 s is held; null = not in range. */
    @Synchronized fun revoke(nowMs: Long): Outgoing? {
        syncKeys()
        val c = challenge ?: return null
        if (nowMs - challengeAt >= CHALLENGE_WINDOW_MS) return null
        return send(Mnai2.REVOKE, Mnai2.revokeBody(c))
    }

    private fun zone(nowMs: Long): List<Outgoing> {
        if (zonesLeft <= 0 || !inRange(nowMs)) return emptyList()
        val id = zoneId()?.takeIf { ZONE_ID.matches(it) } ?: return emptyList()
        val out = send(Mnai2.ZONE, Mnai2.zoneBody(challenge!!, id)) ?: return emptyList()
        zonesLeft--
        return listOf(out)
    }

    private fun send(type: Char, body: ByteArray): Outgoing? {
        val to = destination ?: return null
        val k = keys ?: return null
        // 3.4: nothing under the old key between `handover` and `done`.
        if (ring.sendKey() !== keyRef) return null
        counter++
        return Outgoing(Mnai2.seal(k.p2u, type, session, counter, body), to, destinationPort)
    }

    /** Re-derives everything when the ring's keys changed (commit, revert, forget). */
    private fun syncKeys() {
        val cur = ring.current
        if (cur !== keyRef) {
            keys?.wipe()
            keyRef = cur
            keys = cur?.let(::DirectionKeys)
            currentReplay = Replay()
            session = newSession()
            counter = 0
            destination = null
            candidate = null
            challenge = null
            intervalMs = 0
            lastFixAt = null
            lastFixElapsed = Long.MIN_VALUE
        }
        val res = ring.reserve
        if (res !== reserveRef) {
            reserveKeys?.wipe()
            reserveRef = res
            reserveKeys = res?.let(::DirectionKeys)
            reserveReplay = Replay()
        }
    }

    /**
     * Whether a HELLO from [from]:[fromPort] may drive the feed. The first source is taken at once; a
     * different one only at its second accepted HELLO in a row, so one replayed HELLO cannot move
     * where fixes go (rev 5, 4.3).
     */
    private fun adopt(from: InetAddress, fromPort: Int): Boolean {
        val current = destination
        val same = current == null || (current == from && destinationPort == fromPort)
        if (!same && !(candidate == from && candidatePort == fromPort)) {
            candidate = from
            candidatePort = fromPort
            return false
        }
        candidate = null
        destination = from
        destinationPort = fromPort
        return true
    }


    companion object {
        const val MISSED_INTERVALS = 3
        const val ZONE_REPEATS = 3
        const val MAX_HELLO_EVERY_MS = 5_000L
        const val MAX_AGE_MS = 10_000L
        const val CHALLENGE_WINDOW_MS = 10_000L
        private const val SEEN_SESSIONS = 32
        private val ZONE_ID = Regex("""[A-Za-z0-9_+\-/]{1,64}""")
    }
}
