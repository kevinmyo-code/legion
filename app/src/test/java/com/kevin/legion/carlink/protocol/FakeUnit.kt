package com.kevin.legion.carlink.protocol

import java.nio.ByteBuffer

/**
 * Test stand-in for the unit's side of MNAI2: seals HELLOs under `U2P ` straight from section 4.2's
 * table (not through the phone's own [Mnai2.seal], which only speaks `P2U `).
 */
class FakeUnit(linkKey: ByteArray, val session: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }) {
    val keys = DirectionKeys(linkKey)
    var counter = 0L

    fun hello(
        intervalMs: Int = 1000,
        challenge: ByteArray = nextChallenge(),
        counterOverride: Long? = null,
    ): ByteArray {
        val c = counterOverride ?: ++counter
        val header = ByteBuffer.allocate(31).put("MNAI2".toByteArray()).put(1).put('H'.code.toByte())
            .put(session).putLong(c).array()
        val body = ByteBuffer.allocate(12).putInt(intervalMs).put(challenge).array()
        val key = Mnai2.sessionKey(keys.u2p, session)
        return header + LinkCrypto.seal(key, LinkCrypto.nonce("U2P ", c), header, body)
    }

    private var challengeSeed = 0
    fun nextChallenge(): ByteArray = ByteArray(8) { (challengeSeed * 8 + it).toByte() }.also { challengeSeed++ }
}

/** An in-memory [LinkKeyPersistence] that can be told to fail. */
class MemoryKeys(var current: ByteArray? = null, var reserve: ByteArray? = null) : LinkKeyPersistence {
    var fail = false
    var writes = 0

    override fun write(current: ByteArray?, reserve: ByteArray?): Boolean {
        if (fail) return false
        writes++
        this.current = current?.copyOf()
        this.reserve = reserve?.copyOf()
        return true
    }

    fun ring() = LinkKeyRing(current?.copyOf(), reserve?.copyOf(), this)
}
