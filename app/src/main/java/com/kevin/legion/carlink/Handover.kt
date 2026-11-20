package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.HandoverBody

/** Where the transitional Gemini key comes from (3.5, 5.3). Null or blank = none. */
fun interface GeminiKeySource {
    fun key(): String?
}

/** A device token the engine minted FOR the head unit (5.1). Never the phone's own token. */
class UnitToken(val id: String, val token: String, val userId: String?) {
    override fun toString() = "UnitToken(redacted)"
}

/**
 * The engine end of 5.1 and 5.4: `POST /api/auth/devices` (kind `head_unit`) and
 * `DELETE /api/auth/devices/<id>`. Not built yet: production uses [UnitTokenNotYetAvailable].
 */
interface UnitTokenSource {
    suspend fun mint(unitName: String): UnitToken?
    suspend fun revoke(id: String): Boolean
}

/** Until the engine endpoint ships: no token is minted, so none is handed over, and nothing to revoke. */
class UnitTokenNotYetAvailable : UnitTokenSource {
    override suspend fun mint(unitName: String): UnitToken? = null
    override suspend fun revoke(id: String): Boolean = false
}

/**
 * What the phone hands over (3.5) for one pairing. The unit token is minted only once the unit has
 * proven itself (after a verified `ack`), and revoked again if the pairing then fails. The Gemini key
 * goes only when the person turned that on, because 5.3 wants a separate restricted key for the unit,
 * never the phone's own. Every value is registered with the log redactor before it is used.
 */
class HandoverPlan(
    private val gemini: GeminiKeySource,
    private val tokens: UnitTokenSource,
    private val includeGemini: () -> Boolean,
    private val phoneName: () -> String?,
    private val redact: (String) -> Unit,
) {
    @Volatile private var minted: UnitToken? = null

    suspend fun prepare() {
        if (minted != null) return
        minted = tokens.mint(UNIT_NAME)?.also { redact(it.token) }
    }

    fun body(): HandoverBody {
        val token = minted
        val key = if (includeGemini()) gemini.key()?.takeIf { it.isNotBlank() }?.also(redact) else null
        return HandoverBody(
            engineToken = token?.token,
            engineUserId = token?.userId,
            geminiKey = key,
            phoneName = phoneName(),
        )
    }

    /** The minted token's engine id, kept so "Forget head unit" can revoke it (5.4). */
    fun mintedId(): String? = minted?.id

    /** The pairing failed: revoke a token minted for it, so none is left live on the engine. */
    suspend fun discard() {
        minted?.let { tokens.revoke(it.id) }
        minted = null
    }

    /** The pairing held: the token now belongs to the unit. */
    fun keep() {
        minted = null
    }

    suspend fun revoke(id: String): Boolean = tokens.revoke(id)

    companion object {
        /** STUB (red step). */
        fun forCar(store: CarLinkStore, tokens: UnitTokenSource, phoneName: () -> String?, redact: (String) -> Unit) =
            HandoverPlan({ null }, tokens, { false }, phoneName, redact)

        /** 5.1's device name, listed as "Head unit" in the phone's device list. */
        const val UNIT_NAME = "Head unit"
    }
}
