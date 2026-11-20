// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink.protocol

/** Writes the phone's link keys as one unit: both slots or neither. False = nothing changed on disk. */
fun interface LinkKeyPersistence {
    fun write(current: ByteArray?, reserve: ByteArray?): Boolean
}

/** Which slot an authenticated MNAI2 HELLO opened under. */
enum class KeySlot { CURRENT, RESERVE }

/** What a HELLO did to the ring, for the screen's words. */
enum class RingEvent { NONE, CONFIRMED, REVERTED }

/**
 * The phone's link keys, section 3.7: ONE current key, and during a re-pair the old one IN RESERVE,
 * used for nothing but a revert, until the first authenticated HELLO under the new key.
 *
 * Also owns section 3.4's silence: from `handover` until `done` opens or the session fails,
 * [sendKey] is null, so no fix, zone or revoke goes out under the old key.
 *
 * Every change is persisted first and applied in memory only when [LinkKeyPersistence.write] says
 * it held, so memory and disk never disagree. Times are monotonic milliseconds from the caller.
 */
class LinkKeyRing(
    current: ByteArray?,
    reserve: ByteArray?,
    private val persistence: LinkKeyPersistence,
) {
    var current: ByteArray? = current
        private set
    var reserve: ByteArray? = reserve
        private set

    /** A pairing session is open (section 3.7: the 60 s count runs only with the session closed). */
    @Volatile var sessionOpen: Boolean = false

    @Volatile private var silenced = false
    private var oldOnlySince: Long? = null

    /** The key fixes, zones and revokes go out under; null while silenced or unpaired. */
    @Synchronized fun sendKey(): ByteArray? = if (silenced) null else current

    @Synchronized fun beginHandover() { silenced = true }

    /** The session failed before `done` opened: the old key speaks again. */
    @Synchronized fun handoverFailed() { silenced = false }

    /**
     * `done` opened: [newKey] becomes current and the old current goes to reserve, persisted together
     * BEFORE the phone sends `stored`. False = nothing changed, and the caller aborts.
     */
    @Synchronized fun commit(newKey: ByteArray): Boolean {
        val old = current
        if (!persistence.write(newKey, old)) return false
        reserve?.fill(0)
        current = newKey.copyOf()
        reserve = old
        silenced = false
        oldOnlySince = null
        return true
    }

    /**
     * An `abort` after `done` opened (3.7): back to the old key, the new one deleted. With no reserve
     * (a first pairing) the phone ends unpaired. False = the write failed and nothing changed.
     */
    @Synchronized fun revert(): Boolean {
        val back = reserve
        if (!persistence.write(back, null)) return false
        current?.fill(0)
        current = back
        reserve = null
        oldOnlySince = null
        return true
    }

    /** "Forget head unit": both slots gone. */
    @Synchronized fun forget(): Boolean {
        if (!persistence.write(null, null)) return false
        current?.fill(0)
        reserve?.fill(0)
        current = null
        reserve = null
        oldOnlySince = null
        return true
    }

    /**
     * An authenticated, non-replayed HELLO opened under [slot] at [nowMs].
     * - CURRENT: proof the unit holds the new key; the reserve is deleted.
     * - RESERVE: counted only with the session closed; 60 s of them with no CURRENT HELLO reverts.
     */
    @Synchronized fun onHello(slot: KeySlot, nowMs: Long): RingEvent {
        if (slot == KeySlot.CURRENT) {
            oldOnlySince = null
            val old = reserve ?: return RingEvent.NONE
            if (!persistence.write(current, null)) return RingEvent.NONE
            old.fill(0)
            reserve = null
            return RingEvent.CONFIRMED
        }
        if (reserve == null || sessionOpen) return RingEvent.NONE
        val since = oldOnlySince ?: nowMs.also { oldOnlySince = it }
        if (nowMs - since < QUIET_MS) return RingEvent.NONE
        return if (revert()) RingEvent.REVERTED else RingEvent.NONE
    }

    companion object {
        /** Section 3.7: 60 s of old-key-only HELLOs. */
        const val QUIET_MS = 60_000L
    }
}
