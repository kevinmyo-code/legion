package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Kotlin half of the shared gate corpus. Ticket 03 ruling 2's deliverable.
 *
 * **Why this exists.** Ruling 2 accepted TWO implementations of the same reconciliation arithmetic
 * - a pre-check on the phone and the SQL inside `commit_statement`/`commit_receipt` - on one
 * condition: that something proves they agree. `app/src/test/resources/gate-corpus.json` is the
 * single source of truth for what the gate must do; this file checks it from the Kotlin side and
 * `tools/gate_corpus_sql.py` emits the same cases for the RPC side.
 *
 * **What this asserts, stated precisely so it is not mistaken for more.** The phone's own
 * pre-check does not exist yet: ticket 03 ruling 3 retires the statement parsers in favour of a CSV
 * the user's own LLM produces, and that import path is unbuilt. So there is no Kotlin gate function
 * to call today.
 *
 * What CAN be checked, and is, is that every case's stated expectation follows from its own
 * numbers under the rules in CLAUDE.md section 4 - rule 6 first, then the anchors. That matters for
 * two reasons. It makes the corpus self-verifying, so a case whose `expect` contradicts its own
 * arithmetic fails here rather than quietly redefining correct behaviour for both sides. And it is
 * the same arithmetic the SQL implements, so a divergence in either direction shows up as a
 * disagreement between this file and the SQL run.
 *
 * **AMENDED 2026-08-29, backend-erp ticket 25 ("statement ingestion leaves the phone").** The
 * ledger pre-check briefly landed (`LegionCsvStatementParser`) and `ledgerOutcome` called its
 * production arithmetic, `LedgerReconciliationCheck.check`, directly. Kevin then ruled that the
 * phone never ingests a statement at all - the web app does, against `public.commit_statement`'s
 * own SQL - so that Kotlin pre-check has no production caller left and was deleted with the rest
 * of the phone-side parsers (ticket 25). `ledgerOutcome` below reimplements the arithmetic inline
 * now, the same shape `pantryOutcome` already used (pantry's own pre-check never landed either).
 * This file's job is unchanged either way: prove the corpus is internally consistent against
 * CLAUDE.md section 4's rules, so a divergence between this and `tools/gate_corpus_sql.py`'s SQL
 * run would be caught by *some* copy of the arithmetic even with no Kotlin production path to test.
 */
@RunWith(RobolectricTestRunner::class)
class GateCorpusTest {

    private fun corpus(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("gate-corpus.json")
            ?: error("gate-corpus.json missing from test resources")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    /**
     * The ledger gate, reimplemented inline (see this class's own doc comment for why there is no
     * production Kotlin function to call any more) - the same rules `public.commit_statement`'s
     * SQL implements: rule 6 first (an empty extraction can never pass, whatever the stated
     * figures are), then the stated-total anchor (skippable only for a DETERMINISTIC extraction
     * with no printed total), then the balance-delta anchor.
     */
    private fun ledgerOutcome(case: JSONObject): String {
        val lines = case.getJSONArray("lines")
        val amounts = (0 until lines.length()).map { lines.getJSONObject(it).getLong("amount_cents") }
        if (amounts.isEmpty()) return "QUARANTINED"

        val sum = amounts.sum()
        // A case with no "provenance" key defaults to DETERMINISTIC - every pre-amendment case in
        // the corpus is a three-anchor DETERMINISTIC statement and was never made to say so
        // explicitly; the amendment's new cases DO say so, because their outcome depends on it.
        val provenance = IngestMethod.valueOf(case.optString("provenance", "DETERMINISTIC"))
        val statedTotal = if (case.isNull("stated_total_cents")) null else case.getLong("stated_total_cents")

        if (statedTotal == null) {
            if (provenance != IngestMethod.DETERMINISTIC) return "QUARANTINED"
            // else: no anchor 1 to check - fall through to the balance-delta check, still mandatory.
        } else if (sum != statedTotal) {
            return "QUARANTINED"
        }

        val opening = case.getLong("opening_balance_cents")
        val closing = case.getLong("closing_balance_cents")
        if (closing - opening != sum) return "QUARANTINED"

        return "COMMITTED"
    }

    /**
     * The pantry gate. Two anchors that collapse into one when no subtotal is printed. Mirrors
     * `PantryReceiptAgent.kt:236-278`.
     *
     * The macro estimates are not read at all here, and that is the point of section 4 rule 5
     * rather than an omission: a receipt never prints calories, so they can never be gated.
     */
    private fun pantryOutcome(case: JSONObject): String {
        val items = case.getJSONArray("items")
        if (items.length() == 0) return "QUARANTINED"

        var itemsTotal = 0L
        for (i in 0 until items.length()) itemsTotal += items.getJSONObject(i).getLong("total_price_cents")

        val total = case.getLong("total_cents")
        val tax = if (case.isNull("tax_cents")) 0L else case.getLong("tax_cents")
        val other = if (case.isNull("other_charges_cents")) 0L else case.getLong("other_charges_cents")
        val subtotal = if (case.isNull("subtotal_cents")) null else case.getLong("subtotal_cents")

        if (subtotal != null) {
            if (itemsTotal != subtotal) return "QUARANTINED"
            if (subtotal + tax + other != total) return "QUARANTINED"
        } else {
            if (itemsTotal + tax + other != total) return "QUARANTINED"
        }
        return "COMMITTED"
    }

    private fun eachCase(key: String, run: (JSONObject) -> String) {
        val cases: JSONArray = corpus().getJSONArray(key)
        assertTrue("corpus '$key' is empty, which would make this test vacuous", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(
                "corpus case '${case.getString("name")}' (${key}) disagrees with its own arithmetic. " +
                    "Reason given: ${case.getString("why")}",
                case.getString("expect"),
                run(case),
            )
        }
    }

    @Test
    fun `every ledger corpus case matches the gate arithmetic it claims`() = eachCase("ledger", ::ledgerOutcome)

    @Test
    fun `every pantry corpus case matches the gate arithmetic it claims`() = eachCase("pantry", ::pantryOutcome)

    @Test
    fun `the corpus covers both outcomes on both aspects`() {
        // A corpus of all-COMMITTED cases would pass against a gate that never refuses anything,
        // and a corpus of all-QUARANTINED cases would pass against one that never accepts. Neither
        // failure is hypothetical: they are the two ways a green suite can mean nothing.
        val c = corpus()
        for (key in listOf("ledger", "pantry")) {
            val outcomes = mutableSetOf<String>()
            val cases = c.getJSONArray(key)
            for (i in 0 until cases.length()) outcomes += cases.getJSONObject(i).getString("expect")
            assertEquals("corpus '$key' must exercise both outcomes", setOf("COMMITTED", "QUARANTINED"), outcomes)
        }
    }

    @Test
    fun `rule 6 is covered by a case whose anchors would otherwise pass on nothing`() {
        // The specific hole rule 6 exists for: an empty extraction whose stated figures are all
        // zero satisfies every anchor. If nobody keeps a case of this exact shape, a regression
        // that drops the non-empty check would go unnoticed by every other case here.
        val ledger = corpus().getJSONArray("ledger")
        var found = false
        for (i in 0 until ledger.length()) {
            val case = ledger.getJSONObject(i)
            val emptyLines = case.getJSONArray("lines").length() == 0
            // A null stated_total_cents (the 2026-08-27 no-printed-total shape) can never be the
            // "all figures are zero" case this test pins - a null anchor is either skipped or an
            // outright scope-guard refusal, never a zero it could coincidentally equal.
            val statedTotalIsZero = !case.isNull("stated_total_cents") && case.getLong("stated_total_cents") == 0L
            val anchorsWouldPass = statedTotalIsZero &&
                case.getLong("closing_balance_cents") == case.getLong("opening_balance_cents")
            if (emptyLines && anchorsWouldPass) {
                assertEquals("that case must quarantine", "QUARANTINED", case.getString("expect"))
                found = true
            }
        }
        assertTrue("no corpus case pins rule 6 against self-satisfying zero anchors", found)
    }
}
