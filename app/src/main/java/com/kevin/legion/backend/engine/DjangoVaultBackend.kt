package com.kevin.legion.backend.engine

import com.kevin.legion.vault.BiometricUnlock
import com.kevin.legion.vault.Revealed
import com.kevin.legion.vault.VaultBackend
import com.kevin.legion.vault.VaultItem
import com.kevin.legion.vault.VaultOutcome
import com.kevin.legion.vault.VaultSecret
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ITEMS_PATH = "/api/vault/items"
private const val UNLOCK_HEADER = "X-Legion-Unlocked"
private const val UNLOCK_VALUE = "biometric"

private val vaultJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class VaultItemRow(
    val id: String,
    val label: String,
    val kind: String = "other",
    @SerialName("last_revealed_at") val lastRevealedAt: String? = null,
) {
    fun toItem() = VaultItem(
        id = id,
        label = label,
        kind = kind,
        lastRevealedAtMs = lastRevealedAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
    )
}

@Serializable
private data class VaultList(val results: List<VaultItemRow> = emptyList())

@Serializable
private data class RevealRow(val id: String, val label: String, val kind: String = "other", val value: String)

/**
 * [VaultBackend] over the engine's `/api/vault/items` routes (`server/id_vault/views.py`, ADR 0076).
 *
 * **The value crosses this class twice and is never kept or printed here.** Out, inside a create or
 * update body; in, from the reveal's reply, straight into a [VaultSecret]. No exception message built
 * here quotes a body (a decode failure on the reveal says only that it did not decode), and
 * [EngineHttp] logs nothing. The reveal sends `X-Legion-Unlocked: biometric`, which needs a
 * [BiometricUnlock] to call at all.
 *
 * **429 is the engine's rate limit** (10 reveals an hour per member): it arrives as a 4xx refusal
 * with DRF's own sentence ("Request was throttled. Expected available in N seconds."), status kept,
 * so the screen can say it in words.
 */
class DjangoVaultBackend(private val http: EngineHttp) : VaultBackend {

    override suspend fun list(): VaultOutcome<List<VaultItem>> =
        outcome(http.get(ITEMS_PATH)) { body ->
            vaultJson.decodeFromString(VaultList.serializer(), body).results.map { it.toItem() }
        }

    override suspend fun create(label: String, kind: String, value: VaultSecret): VaultOutcome<VaultItem> {
        val body = JsonObject(
            mapOf(
                "label" to JsonPrimitive(label),
                "kind" to JsonPrimitive(kind),
                "value" to JsonPrimitive(value.reveal()),
            ),
        ).toString()
        return outcome(http.post(ITEMS_PATH, body)) {
            vaultJson.decodeFromString(VaultItemRow.serializer(), it).toItem()
        }
    }

    override suspend fun update(
        id: String,
        label: String?,
        kind: String?,
        value: VaultSecret?,
    ): VaultOutcome<VaultItem> {
        val fields = buildMap {
            label?.let { put("label", JsonPrimitive(it)) }
            kind?.let { put("kind", JsonPrimitive(it)) }
            value?.let { put("value", JsonPrimitive(it.reveal())) }
        }
        return outcome(http.patch("$ITEMS_PATH/$id", JsonObject(fields).toString())) {
            vaultJson.decodeFromString(VaultItemRow.serializer(), it).toItem()
        }
    }

    override suspend fun delete(id: String): VaultOutcome<Unit> = outcome(http.delete("$ITEMS_PATH/$id")) { }

    override suspend fun reveal(id: String, unlock: BiometricUnlock): VaultOutcome<Revealed> =
        outcome(
            http.post("$ITEMS_PATH/$id/reveal", "{}", extraHeaders = mapOf(UNLOCK_HEADER to UNLOCK_VALUE)),
        ) { body ->
            val row = vaultJson.decodeFromString(RevealRow.serializer(), body)
            Revealed(VaultItem(row.id, row.label, row.kind), VaultSecret(row.value))
        }

    private fun <T> outcome(result: Result<EngineOk>, decode: (String) -> T): VaultOutcome<T> {
        val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
        return when {
            result.isSuccess -> try {
                VaultOutcome.Ok(decode(result.getOrThrow().body))
            } catch (e: SerializationException) {
                // Never e.message: a decode error can quote the body, and a reveal's body holds the value.
                VaultOutcome.Unconfirmed("The engine's reply did not decode.")
            } catch (e: DateTimeParseException) {
                VaultOutcome.Unconfirmed("The engine's reply did not decode.")
            } catch (e: IllegalArgumentException) {
                VaultOutcome.Unconfirmed("The engine's reply did not decode.")
            }
            failure is EngineFailure.Unreachable -> VaultOutcome.Unreachable(failure.message)
            failure is EngineFailure.Unauthorized -> VaultOutcome.Refused(
                failure.body.takeIf { failure.status == HTTP_FORBIDDEN && it.isNotBlank() }
                    ?.let(::engineRefusalSentence) ?: failure.sentence,
                failure.status,
            )
            failure is EngineFailure.Refused ->
                VaultOutcome.Refused(engineRefusalSentence(failure.body), failure.status)
            failure is EngineFailure.Malformed -> VaultOutcome.Unconfirmed(failure.sentence)
            else -> VaultOutcome.Unconfirmed("The vault call did not finish.")
        }
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}
