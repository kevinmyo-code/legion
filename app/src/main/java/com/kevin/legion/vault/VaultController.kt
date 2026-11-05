package com.kevin.legion.vault

import android.content.Context
import com.kevin.legion.backend.engine.DjangoVaultBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp

/**
 * The one controller the vault screen and the `show_vault_item` voice tool call (ADR 0035, ADR 0076).
 * The tool only ever calls [list] and [match]: labels, never a value. [reveal] needs a
 * [BiometricUnlock], which only the fingerprint prompt's success hands out.
 *
 * **Nothing here logs, and no sentence here carries a value.** Every sentence names the label at
 * most. Values arrive as [VaultSecret] and leave only inside a write to the engine.
 */
class VaultController(private val backend: VaultBackend) {

    suspend fun list(): VaultOutcome<List<VaultItem>> = backend.list()

    /** Adds an item. Label, kind and value are checked here only far enough to say what is missing. */
    suspend fun add(label: String, kind: String, value: VaultSecret): VaultOutcome<VaultItem> {
        val cleanLabel = label.trim()
        refusal(cleanLabel, kind, value.reveal().trim(), valueRequired = true)?.let { return VaultOutcome.Refused(it) }
        return backend.create(cleanLabel, kind, value)
    }

    /** Edits an item; a null [value] leaves the stored value as it is. */
    suspend fun edit(item: VaultItem, label: String, kind: String, value: VaultSecret?): VaultOutcome<VaultItem> {
        val cleanLabel = label.trim()
        refusal(cleanLabel, kind, value?.reveal()?.trim(), valueRequired = false)
            ?.let { return VaultOutcome.Refused(it) }
        return backend.update(item.id, cleanLabel.takeIf { it != item.label }, kind.takeIf { it != item.kind }, value)
    }

    suspend fun delete(item: VaultItem): VaultOutcome<Unit> = backend.delete(item.id)

    suspend fun reveal(item: VaultItem, unlock: BiometricUnlock): VaultOutcome<Revealed> =
        backend.reveal(item.id, unlock)

    private fun refusal(label: String, kind: String, value: String?, valueRequired: Boolean): String? = when {
        label.isEmpty() -> "Give it a label, like Passport. Nothing was saved."
        label.length > LABEL_MAX -> "A label is at most $LABEL_MAX characters. Nothing was saved."
        kind !in KINDS -> "Pick what kind of number it is. Nothing was saved."
        valueRequired && value.isNullOrEmpty() -> "Type the number itself. Nothing was saved."
        value != null && value.length > VALUE_MAX -> "A value is at most $VALUE_MAX characters. Nothing was saved."
        else -> null
    }

    companion object {
        /** The engine's `id_vault.models.KINDS`, in the order the add form offers them. */
        val KINDS = listOf("passport", "nric", "ssn", "a_number", "bank_account", "other")
        const val LABEL_MAX = 60
        const val VALUE_MAX = 128

        fun kindWord(kind: String): String = when (kind) {
            "passport" -> "Passport"
            "nric" -> "NRIC"
            "ssn" -> "SSN"
            "a_number" -> "A-number"
            "bank_account" -> "Bank account"
            else -> "Other"
        }

        /**
         * The item [label] names: an exact match (case and spacing aside), else the one item whose
         * label contains it, else null (none, or more than one).
         */
        fun match(items: List<VaultItem>, label: String): VaultItem? {
            val wanted = label.trim().lowercase().replace(Regex("\\s+"), " ")
            if (wanted.isEmpty()) return null
            items.firstOrNull { it.label.lowercase() == wanted }?.let { return it }
            val loose = items.filter {
                it.label.lowercase().contains(wanted) || kindWord(it.kind).lowercase() == wanted
            }
            return loose.singleOrNull()
        }

        /**
         * Why a vault call did not happen, in words. [verb] is the past participle ("revealed",
         * "saved", "deleted"). A 429 is the engine's reveal limit, said as such with its own sentence.
         */
        fun failed(outcome: VaultOutcome<*>, verb: String): String = when (outcome) {
            is VaultOutcome.Unreachable -> "I can't reach the engine, so nothing was $verb. ${outcome.sentence}"
            is VaultOutcome.Refused -> when (outcome.status) {
                HTTP_TOO_MANY -> "Nothing was $verb. The engine allows $REVEALS_PER_HOUR reveals an hour and " +
                    "that is used up. ${outcome.sentence}"
                else -> outcome.sentence.ifBlank { "The engine refused, so nothing was $verb." }
            }
            is VaultOutcome.Unconfirmed -> "The engine's reply could not be read, so I can't say whether it was $verb."
            is VaultOutcome.Ok<*> -> ""
        }

        private const val HTTP_TOO_MANY = 429
        private const val REVEALS_PER_HOUR = 10

        fun forContext(context: Context): VaultController =
            VaultController(DjangoVaultBackend(EngineHttp(EngineConfig(context.applicationContext))))
    }
}
