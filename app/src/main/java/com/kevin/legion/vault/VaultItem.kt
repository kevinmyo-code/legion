package com.kevin.legion.vault

/**
 * One ID vault item as the engine lists it (`/api/vault/items`, ADR 0076): a label and a kind,
 * **never a value**. The engine's list, create and update never answer with one; only the reveal
 * does, and that answer becomes a [VaultSecret], never one of these.
 */
data class VaultItem(
    val id: String,
    val label: String,
    /** `passport`, `nric`, `ssn`, `a_number`, `bank_account` or `other`. */
    val kind: String,
    val lastRevealedAtMs: Long? = null,
)

/**
 * A revealed value, held in memory only. **Its [toString] never prints the value**, so a log line,
 * an exception message, a string template or a crash report that touches one by accident prints
 * `[vault value]` instead (ruling 5: never logged). The value is read once, by [reveal], for the
 * screen to draw and the copy button to copy. [wipe] overwrites the characters when the screen lets
 * go of it.
 */
class VaultSecret(value: String) {
    private val chars: CharArray = value.toCharArray()
    @Volatile private var wiped = false

    /** The value, for the screen and the clipboard only. Empty after [wipe]. */
    fun reveal(): String = if (wiped) "" else String(chars)

    fun wipe() {
        chars.fill(' ')
        wiped = true
    }

    override fun toString(): String = "[vault value]"
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A reveal's answer: the item it opened and the value, wrapped. */
class Revealed(val item: VaultItem, val secret: VaultSecret) {
    override fun toString(): String = "Revealed(${item.label}, [vault value])"
}

/**
 * Proof that the fingerprint prompt succeeded on this phone just now. Constructed only by
 * [com.kevin.legion.ui.vault.VaultBiometric]'s success callback (and by tests), so a reveal cannot be
 * written that skips the prompt without saying so in code. The engine cannot check it (ADR 0076: the
 * header is the phone's word); this is the phone keeping that word.
 */
class BiometricUnlock internal constructor()

/** What one vault call did; the same four states as [com.kevin.legion.memory.MemoryOutcome]. */
sealed interface VaultOutcome<out T> {
    data class Ok<T>(val value: T) : VaultOutcome<T>
    data class Unreachable(val sentence: String) : VaultOutcome<Nothing>
    data class Refused(val sentence: String, val status: Int = 0) : VaultOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : VaultOutcome<Nothing>
}

/** The seam [VaultController] talks through; the one implementation is the engine's. */
interface VaultBackend {
    suspend fun list(): VaultOutcome<List<VaultItem>>
    suspend fun create(label: String, kind: String, value: VaultSecret): VaultOutcome<VaultItem>
    /** Null fields are left as the engine holds them. */
    suspend fun update(id: String, label: String?, kind: String?, value: VaultSecret?): VaultOutcome<VaultItem>
    suspend fun delete(id: String): VaultOutcome<Unit>
    /** `POST /api/vault/items/<id>/reveal` with `X-Legion-Unlocked: biometric`. */
    suspend fun reveal(id: String, unlock: BiometricUnlock): VaultOutcome<Revealed>
}
