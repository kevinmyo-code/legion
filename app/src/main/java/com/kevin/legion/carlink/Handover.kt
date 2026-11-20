package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.HandoverBody

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
 * is only ever the car's OWN key field (5.3, rev 5: never the phone's own key); empty sends none.
 * Every value is registered with the log redactor before it is used.
 */
class HandoverPlan(
    private val carKey: () -> String?,
    private val tokens: UnitTokenSource,
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
        val key = carKey()?.takeIf { it.isNotBlank() }?.also(redact)
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
        /** The production wiring: the only Gemini key that can ever go is the car's own key field. */
        fun forCar(store: CarLinkStore, tokens: UnitTokenSource, phoneName: () -> String?, redact: (String) -> Unit) =
            HandoverPlan(store::carGeminiKey, tokens, phoneName, redact)

        /** 5.1's device name, listed as "Head unit" in the phone's device list. */
        const val UNIT_NAME = "Head unit"
    }
}
