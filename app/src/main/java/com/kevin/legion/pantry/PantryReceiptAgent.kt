package com.kevin.legion.pantry

import android.util.Log
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ledger.parsers.parseMoneyCents
import org.json.JSONObject

/**
 * Vision extraction for a photographed grocery receipt
 * (`.claude/plans/wiggly-beaming-quasar.md`, step 4). Unlike
 * [com.kevin.legion.ledger.LedgerStatementAgent] this is the PRIMARY path, not
 * a fallback - there is no deterministic layout for a photographed receipt the
 * way bank statements have one. Same reconciliation discipline though:
 * extracted line totals must sum to the receipt's own printed total before
 * anything is accepted; a mismatch quarantines rather than silently writing a
 * wrong number. Per-item macro estimates are never reconciled against
 * anything - there's nothing on a receipt to check them against - and must
 * always be surfaced as estimates, never fact (CLAUDE.md §9.1).
 */
object PantryReceiptAgent {
    private const val TAG = "PantryReceiptAgent"

    private val SYSTEM_INSTRUCTION = "You read grocery receipt photos and extract structured data. " +
        "Never invent, round, or estimate a PRICE or the store/date/total - if a value isn't legible " +
        "in the photo, leave it null. You may ESTIMATE per-item nutrition (calories, protein, carbs, " +
        "fat) from your general knowledge of the named product, since a receipt never prints those - " +
        "but never estimate money figures. You are not being asked for advice, only structured extraction."

    private val PROMPT = "Extract this grocery receipt. Respond with ONLY a raw JSON object (no " +
        "markdown, no commentary, no code fences) with this exact shape:\n" +
        "{\"store\": string, \"purchaseDate\": string (YYYY-MM-DD, guess from context if not printed), " +
        "\"currency\": \"SGD\" or \"USD\" (guess from store/context if not stated), " +
        "\"total\": string (the receipt's own printed grand total, exact decimal like \"45.67\"), " +
        "\"items\": [{\"name\": string, \"quantity\": number (default 1), " +
        "\"unitPrice\": string or null (exact decimal), \"totalPrice\": string (exact decimal, this " +
        "item's line total), \"caloriesKcal\": number or null (your best estimate for this item), " +
        "\"proteinG\": number or null (estimate), \"carbsG\": number or null (estimate), " +
        "\"fatG\": number or null (estimate)}]}"

    suspend fun extract(imageBytes: ByteArray, sourceImagePath: String): PantryIngestResult {
        val raw = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false)
                .ask(context = "", question = PROMPT, imageBytes = imageBytes)
        } catch (e: Exception) {
            Log.w(TAG, "extraction request failed: ${e.message}")
            null
        } ?: return PantryIngestResult.Quarantined(
            "I couldn't reach the extraction service to read this receipt - try again in a sec."
        )

        return parseAndReconcile(raw, sourceImagePath)
    }

    /** Network-free: the raw model text in, a typed result out. Unit-tested directly. */
    fun parseAndReconcile(raw: String, sourceImagePath: String): PantryIngestResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) {
            return PantryIngestResult.Quarantined(
                "The extraction came back in an unreadable shape - couldn't verify this receipt's numbers."
            )
        }

        val root = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            return PantryIngestResult.Quarantined(
                "The extraction came back malformed - couldn't verify this receipt's numbers."
            )
        }

        val store = root.optString("store").trim()
        if (store.isBlank()) {
            return PantryIngestResult.Quarantined(
                "Couldn't make out the store name on this receipt - couldn't verify its numbers."
            )
        }

        val currency = when (root.optString("currency").trim().uppercase()) {
            "SGD" -> LedgerCurrency.SGD
            "USD" -> LedgerCurrency.USD
            else -> return PantryIngestResult.Quarantined(
                "Couldn't tell which currency this receipt is in - couldn't verify its numbers."
            )
        }

        val purchaseDateStr = root.optString("purchaseDate").trim()
        val purchaseDate = try {
            java.time.LocalDate.parse(purchaseDateStr).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        } catch (e: Exception) {
            return PantryIngestResult.Quarantined("Couldn't parse this receipt's date: '$purchaseDateStr'.")
        }

        val totalCents = try {
            parseMoneyCents(root.optString("total").trim())
        } catch (e: Exception) {
            return PantryIngestResult.Quarantined(
                "This receipt doesn't print a clear total to verify against - refusing to guess."
            )
        }

        val itemsArray = root.optJSONArray("items")
            ?: return PantryIngestResult.Quarantined("No line items found on this receipt.")

        val items = mutableListOf<PantryLineItem>()
        for (i in 0 until itemsArray.length()) {
            val o = itemsArray.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val totalPriceStr = o.optString("totalPrice").trim()
            if (name.isBlank() || totalPriceStr.isBlank()) {
                return PantryIngestResult.Quarantined(
                    "One of the extracted items is missing a name or price - couldn't verify this " +
                        "receipt's numbers."
                )
            }
            val totalPriceCents = try {
                parseMoneyCents(totalPriceStr)
            } catch (e: Exception) {
                return PantryIngestResult.Quarantined("Couldn't parse an item price: '$totalPriceStr'.")
            }
            val unitPriceCents = o.optString("unitPrice").trim().takeIf { it.isNotBlank() }?.let {
                try {
                    parseMoneyCents(it)
                } catch (e: Exception) {
                    null
                }
            }
            items.add(
                PantryLineItem(
                    receiptId = 0,
                    name = name,
                    quantity = if (o.has("quantity")) o.optDouble("quantity", 1.0) else 1.0,
                    unitPriceCents = unitPriceCents,
                    totalPriceCents = totalPriceCents,
                    caloriesKcal = o.optInt("caloriesKcal", -1).takeIf { it >= 0 && o.has("caloriesKcal") },
                    proteinG = o.optDouble("proteinG").takeIf { !it.isNaN() },
                    carbsG = o.optDouble("carbsG").takeIf { !it.isNaN() },
                    fatG = o.optDouble("fatG").takeIf { !it.isNaN() },
                )
            )
        }

        if (items.isEmpty()) {
            return PantryIngestResult.Quarantined("No line items found on this receipt.")
        }

        // The reconciliation gate: money must sum exactly. Macro estimates are
        // never part of this check - they're not verifiable against anything
        // on the receipt.
        val actualTotal = items.sumOf { it.totalPriceCents }
        if (actualTotal != totalCents) {
            return PantryIngestResult.Quarantined(
                "This receipt's extracted items ($actualTotal cents) don't sum to its own printed " +
                    "total ($totalCents cents) - couldn't verify the numbers, so nothing was saved. " +
                    "Try a clearer photo."
            )
        }

        return PantryIngestResult.Success(
            receipt = PantryReceipt(
                store = store,
                purchaseDate = purchaseDate,
                currency = currency,
                totalCents = totalCents,
                sourceImagePath = sourceImagePath,
            ),
            items = items,
        )
    }
}
