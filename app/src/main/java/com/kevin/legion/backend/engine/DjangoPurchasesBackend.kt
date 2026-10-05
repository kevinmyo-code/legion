package com.kevin.legion.backend.engine

import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseDraft
import com.kevin.legion.purchases.PurchaseListing
import com.kevin.legion.purchases.PurchaseMatch
import com.kevin.legion.purchases.PurchaseOutcome
import com.kevin.legion.purchases.PurchasesBackend
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val PURCHASES_PATH = "/api/purchases/"
private const val LAST_BOUGHT_PATH = "/api/purchases/last-bought"

private val purchasesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable
private data class PurchaseRow(
    val id: String,
    val item: String,
    @SerialName("bought_on") val boughtOn: Int,
    @SerialName("logged_by") val loggedBy: String? = null,
    @SerialName("logged_by_me") val loggedByMe: Boolean = false,
    val store: String? = null,
    @SerialName("price_cents") val priceCents: Long? = null,
    @SerialName("price_note") val priceNote: String? = null,
    @SerialName("quantity_note") val quantityNote: String? = null,
    val visibility: String = "shared",
    val source: String = "MANUAL",
) {
    fun toPurchase() = Purchase(
        id = id,
        item = item,
        boughtOn = boughtOn,
        loggedBy = loggedBy,
        loggedByMe = loggedByMe,
        store = store,
        priceCents = priceCents,
        priceNote = priceNote,
        quantityNote = quantityNote,
        isPrivate = visibility == "private",
        source = source,
    )
}

@Serializable
private data class MatchRow(
    val entry: PurchaseRow,
    val exact: Boolean = false,
    @SerialName("times_logged") val timesLogged: Int = 1,
)

@Serializable
private data class LastBoughtBody(val matches: List<MatchRow> = emptyList())

@Serializable
private data class ListBody(
    val results: List<PurchaseRow> = emptyList(),
    val truncated: Boolean = false,
    val message: String? = null,
)

/** `POST` body. `explicitNulls = false`, so an absent store/price/note is simply not sent: this is a
 * create, there is nothing to clear. */
@Serializable
private data class PurchaseWrite(
    val item: String,
    @SerialName("bought_on") val boughtOn: Int,
    val store: String? = null,
    @SerialName("price_cents") val priceCents: Long? = null,
    @SerialName("quantity_note") val quantityNote: String? = null,
    val visibility: String,
    @SerialName("sync_id") val syncId: String,
)

/**
 * [PurchasesBackend] over the household engine's `/api/purchases/` routes
 * (`server/purchases/`, purchase-log ticket 06), with the device token [EngineHttp] attaches - the
 * same path the checklists use.
 *
 * **This class is where an [EngineHttp] failure becomes one of [PurchaseOutcome]'s four honest
 * states**, and nothing above it sees a raw exception or a status code:
 * unreachable (nothing sent) stays unreachable, the engine's 4xx/5xx and a lost token are refusals
 * said in the engine's own words, and a 2xx whose body does not decode is unconfirmed - for a write
 * the entry may have landed, so nobody may call it saved or not saved.
 *
 * Paths: the collection root keeps its trailing slash and `last-bought` does not, matching
 * `server/openapi.yaml`; getting it wrong is a 404, not a redirect (same trap as checklists).
 */
class DjangoPurchasesBackend(private val http: EngineHttp) : PurchasesBackend {

    override suspend fun lastBought(query: String): PurchaseOutcome<List<PurchaseMatch>> =
        read(LAST_BOUGHT_PATH, mapOf("q" to query)) { body ->
            purchasesJson.decodeFromString(LastBoughtBody.serializer(), body).matches.map {
                PurchaseMatch(it.entry.toPurchase(), it.exact, it.timesLogged)
            }
        }

    override suspend fun list(query: String?, limit: Int): PurchaseOutcome<PurchaseListing> {
        val params = buildMap {
            put("limit", limit.toString())
            query?.let { put("q", it) }
        }
        return read(PURCHASES_PATH, params) { body ->
            val parsed = purchasesJson.decodeFromString(ListBody.serializer(), body)
            PurchaseListing(parsed.results.map { it.toPurchase() }, parsed.truncated, parsed.message)
        }
    }

    override suspend fun create(draft: PurchaseDraft): PurchaseOutcome<Purchase> {
        val body = purchasesJson.encodeToString(
            PurchaseWrite.serializer(),
            PurchaseWrite(
                item = draft.item,
                boughtOn = draft.boughtOn,
                store = draft.store,
                priceCents = draft.priceCents,
                quantityNote = draft.note,
                visibility = if (draft.isPrivate) "private" else "shared",
                syncId = draft.syncId,
            ),
        )
        // 201 is a new entry and 200 is "this sync_id already exists, here it is": both are the
        // entry being in the log, which is what a retried write needs to learn.
        return outcome(http.post(PURCHASES_PATH, body)) {
            purchasesJson.decodeFromString(PurchaseRow.serializer(), it).toPurchase()
        }
    }

    private suspend fun <T> read(
        path: String,
        query: Map<String, String>,
        decode: (String) -> T,
    ): PurchaseOutcome<T> = outcome(http.get(path, query), decode)

    private fun <T> outcome(result: Result<EngineOk>, decode: (String) -> T): PurchaseOutcome<T> {
        val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
        return when {
            result.isSuccess -> try {
                PurchaseOutcome.Ok(decode(result.getOrThrow().body))
            } catch (e: SerializationException) {
                PurchaseOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            } catch (e: DateTimeParseException) {
                PurchaseOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            } catch (e: IllegalArgumentException) {
                PurchaseOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
            }
            failure is EngineFailure.Unreachable -> PurchaseOutcome.Unreachable(failure.message)
            failure is EngineFailure.Unauthorized -> PurchaseOutcome.Refused(failure.sentence)
            failure is EngineFailure.Refused -> PurchaseOutcome.Refused(engineRefusalSentence(failure.body))
            failure is EngineFailure.Malformed -> PurchaseOutcome.Unconfirmed(failure.sentence)
            else -> PurchaseOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
        }
    }
}
