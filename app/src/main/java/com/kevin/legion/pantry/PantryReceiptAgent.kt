package com.kevin.legion.pantry

import android.util.Log
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.parsers.parseMoneyCents
import org.json.JSONObject

/**
 * Vision extraction for a photographed grocery receipt
 * (`.claude/plans/wiggly-beaming-quasar.md`, step 4). Unlike
 * [com.kevin.legion.ledger.LedgerStatementAgent] this is the PRIMARY path, not
 * a fallback - there is no deterministic layout for a photographed receipt the
 * way bank statements have one. Same reconciliation discipline though:
 * extracted line totals must reconcile against the receipt's own printed
 * figures before anything is accepted; a mismatch quarantines rather than
 * silently writing a wrong number. Per-item macro estimates are never
 * reconciled against anything - there's nothing on a receipt to check them
 * against - and must always be surfaced as estimates, never fact (CLAUDE.md §9.1).
 *
 * **The gate reconciles against SUBTOTAL, not the grand total (fixed
 * 2026-08-07).** It originally asked only for `total` and checked
 * `sum(items) == total`, which is unsatisfiable on any receipt that prints
 * sales tax as a separate line - i.e. every US receipt. Kevin's real Walmart
 * receipt quarantined at 12084 vs 12886 cents, the 802-cent gap being Texas
 * sales tax. The failure was invisible for four sessions because all eight
 * unit tests fed canned JSON whose items happened to sum to the total, so the
 * fixtures proved the parser matched its own spec rather than matching a
 * till (`memory/library/lessons.md` L14, same shape as the card parser
 * dropping four interest rows).
 *
 * The fix is a STRONGER gate, not a relaxed one: two anchors instead of one,
 * both read off the receipt's own printed figures.
 * 1. `sum(items) == subtotal`
 * 2. `subtotal + tax + otherCharges == total`
 *
 * Splitting them is what makes a quarantine message diagnostic - a dropped
 * item fails anchor 1 and a misread tax line fails anchor 2, and the driver
 * is told which. When the receipt prints no subtotal at all the two collapse
 * into the single `sum(items) + tax + otherCharges == total`, which is still
 * a real anchor. **A missing figure never loosens the check**: `tax` absent
 * means zero is added, so a receipt that really did charge tax fails loudly
 * against its own total rather than being waved through (CLAUDE.md §4 rule 6
 * - a check satisfiable by an omission is not a gate).
 */
object PantryReceiptAgent {
    private const val TAG = "PantryReceiptAgent"

    /**
     * Thrown when a money field is PRESENT on the extraction but unparseable.
     * Deliberately distinct from the field being absent: absent means the
     * receipt didn't print it and the gate adapts, whereas present-but-garbage
     * means the model saw something it couldn't read, and treating that as
     * absent would silently drop a figure the gate depends on.
     */
    private class UnreadableFigureException(val field: String, val token: String) : Exception()

    private val SYSTEM_INSTRUCTION = "You read grocery receipt photos and extract structured data. " +
        "Never invent, round, or estimate a PRICE or the store/date/total/subtotal/tax - if a value " +
        "isn't legible in the photo, leave it null. Report money figures ONLY as the receipt prints " +
        "them; never derive a subtotal by adding up the items yourself, and never derive tax by " +
        "applying a rate. You may ESTIMATE per-item nutrition (calories, protein, carbs, " +
        "fat) from your general knowledge of the named product, since a receipt never prints those - " +
        "but never estimate money figures. You are not being asked for advice, only structured extraction."

    private val PROMPT = "Extract this grocery receipt. Respond with ONLY a raw JSON object (no " +
        "markdown, no commentary, no code fences) with this exact shape:\n" +
        "{\"store\": string, \"purchaseDate\": string (YYYY-MM-DD, guess from context if not printed), " +
        "\"currency\": \"SGD\" or \"USD\" (guess from store/context if not stated), " +
        "\"total\": string (the receipt's own printed grand total, exact decimal like \"45.67\"), " +
        "\"subtotal\": string or null (the receipt's own printed subtotal BEFORE tax, exact " +
        "decimal - null ONLY if the receipt genuinely doesn't print one; never compute it yourself), " +
        "\"tax\": string or null (the SUM of every printed tax line, exact decimal - null ONLY if " +
        "the receipt prints no tax line at all; never estimate or derive a tax figure), " +
        "\"otherCharges\": string or null (the sum of any other printed charge that is neither a " +
        "line item nor tax, such as a bag fee, bottle deposit or delivery fee; negative for a " +
        "printed discount or coupon; null if there are none), " +
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

        // Absent is a legitimate state for all three (a receipt need not print
        // a subtotal, and a tax-free basket prints no tax line); present but
        // unreadable is not, and quarantines - see UnreadableFigureException.
        val subtotalCents: Long?
        val taxCents: Long?
        val otherChargesCents: Long?
        try {
            subtotalCents = optionalMoneyCents(root, "subtotal")
            taxCents = optionalMoneyCents(root, "tax")
            otherChargesCents = optionalMoneyCents(root, "otherCharges")
        } catch (e: UnreadableFigureException) {
            return PantryIngestResult.Quarantined(
                "Couldn't read this receipt's printed ${e.field} ('${e.token}') - couldn't verify " +
                    "its numbers, so nothing was saved."
            )
        }

        // A non-positive subtotal or a negative tax is not a figure any till
        // prints; it means the extraction misread something, and letting
        // either through would make the anchors below satisfiable by garbage.
        // otherCharges is deliberately allowed to be negative - that's how a
        // printed coupon or discount line arrives.
        if (subtotalCents != null && subtotalCents <= 0L) {
            return PantryIngestResult.Quarantined(
                "This receipt's subtotal came back as $subtotalCents cents, which no receipt prints - " +
                    "couldn't verify its numbers, so nothing was saved."
            )
        }
        if (taxCents != null && taxCents < 0L) {
            return PantryIngestResult.Quarantined(
                "This receipt's tax came back negative ($taxCents cents) - couldn't verify its " +
                    "numbers, so nothing was saved."
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

        // The reconciliation gate: money must sum exactly, against the
        // receipt's own printed figures. Macro estimates are never part of
        // this check - they're not verifiable against anything on the
        // receipt. See this object's doc comment for why it reconciles
        // against the SUBTOTAL rather than the grand total.
        val money = { cents: Long -> "${currency.name} ${formatCents(cents)}" }
        val itemsTotal = items.sumOf { it.totalPriceCents }
        val taxTotal = taxCents ?: 0L
        val otherTotal = otherChargesCents ?: 0L

        if (subtotalCents != null) {
            // Anchor 1: the items are all of, and only, what the subtotal covers.
            if (itemsTotal != subtotalCents) {
                val missing = subtotalCents - itemsTotal
                return PantryIngestResult.Quarantined(
                    "This receipt's ${items.size} extracted items come to ${money(itemsTotal)}, but it " +
                        "prints a subtotal of ${money(subtotalCents)} - ${money(kotlin.math.abs(missing))} " +
                        (if (missing > 0) "is missing, so an item was probably not read. " else "too much was read. ") +
                        "Couldn't verify the numbers, so nothing was saved. Try a clearer photo."
                )
            }
            // Anchor 2: subtotal, tax and any other printed charge account for
            // the grand total with nothing left over.
            val computed = subtotalCents + taxTotal + otherTotal
            if (computed != totalCents) {
                return PantryIngestResult.Quarantined(
                    "This receipt's own figures don't tie out: a subtotal of ${money(subtotalCents)} " +
                        "plus ${money(taxTotal)} tax" +
                        (if (otherTotal != 0L) " plus ${money(otherTotal)} in other charges" else "") +
                        " lands at ${money(computed)}, not the ${money(totalCents)} it prints as the " +
                        "total. Couldn't verify the numbers, so nothing was saved. Try a clearer photo."
                )
            }
        } else {
            // No printed subtotal to split the check on, so the two anchors
            // collapse into one. Still a real gate: the items plus every
            // printed non-item charge must account for the total exactly.
            val computed = itemsTotal + taxTotal + otherTotal
            if (computed != totalCents) {
                return PantryIngestResult.Quarantined(
                    "This receipt's ${items.size} extracted items come to ${money(itemsTotal)}" +
                        (if (taxTotal != 0L) " plus ${money(taxTotal)} tax" else "") +
                        (if (otherTotal != 0L) " plus ${money(otherTotal)} in other charges" else "") +
                        ", which lands at ${money(computed)}, not the ${money(totalCents)} it prints " +
                        "as the total. Couldn't verify the numbers, so nothing was saved. " +
                        "Try a clearer photo."
                )
            }
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

    /**
     * Reads an optional printed money field. Returns null when the key is
     * absent, JSON-null or blank - all of which mean "the receipt didn't
     * print this" - and throws [UnreadableFigureException] when the key IS
     * present but doesn't parse, so an unreadable figure can never be
     * mistaken for an absent one.
     */
    private fun optionalMoneyCents(root: JSONObject, field: String): Long? {
        if (!root.has(field) || root.isNull(field)) return null
        val token = root.optString(field).trim()
        if (token.isBlank()) return null
        return try {
            parseMoneyCents(token)
        } catch (e: Exception) {
            throw UnreadableFigureException(field, token)
        }
    }
}
