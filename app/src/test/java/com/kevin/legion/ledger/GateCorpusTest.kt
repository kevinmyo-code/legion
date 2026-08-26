package com.kevin.legion.ledger

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
 * **When the pre-check lands**, it plugs in here: replace `ledgerOutcome`/`pantryOutcome` with calls
 * to it and the comparison becomes direct rather than arithmetic-level. The corpus does not change.
 */
@RunWith(RobolectricTestRunner::class)
class GateCorpusTest {

    private fun corpus(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("gate-corpus.json")
            ?: error("gate-corpus.json missing from test resources")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    /**
     * The ledger gate, expressed once. Rule 6 before the anchors, deliberately: with zero lines the
     * sum is 0 and closing-minus-opening can also be 0, so both anchors are satisfiable by nothing
     * at all.
     */
    private fun ledgerOutcome(case: JSONObject): String {
        val lines = case.getJSONArray("lines")
        if (lines.length() == 0) return "QUARANTINED"

        var sum = 0L
        for (i in 0 until lines.length()) sum += lines.getJSONObject(i).getLong("amount_cents")

        if (sum != case.getLong("stated_total_cents")) return "QUARANTINED"
        if (case.getLong("closing_balance_cents") - case.getLong("opening_balance_cents") != sum) {
            return "QUARANTINED"
        }
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
            val anchorsWouldPass = case.getLong("stated_total_cents") == 0L &&
                case.getLong("closing_balance_cents") == case.getLong("opening_balance_cents")
            if (emptyLines && anchorsWouldPass) {
                assertEquals("that case must quarantine", "QUARANTINED", case.getString("expect"))
                found = true
            }
        }
        assertTrue("no corpus case pins rule 6 against self-satisfying zero anchors", found)
    }
}
