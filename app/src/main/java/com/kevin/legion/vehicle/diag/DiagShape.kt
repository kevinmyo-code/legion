package com.kevin.legion.vehicle.diag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A scan as tool-result JSON, shaped for the cards that come after `feat/cards-viz` merges: a
 * module status list (one row per module: name, address, outcome words, codes) and a readings
 * list (label, value, unit, verified). Every code carries where its meaning came from.
 */
object DiagShape {

    fun allCodes(r: ScanReport): List<String> = buildList {
        r.engine?.let { e ->
            listOf(e.stored, e.pending, e.permanent).forEach { read -> read.dtcs.forEach { add(it.code) } }
        }
        r.modules.forEach { m -> m.codes.dtcs.filter { it.fault }.forEach { add(it.code) } }
    }.distinct()

    fun report(context: Context, r: ScanReport, diff: ScanDiff?): JSONObject = JSONObject()
        .put("bus", r.bus.words)
        .put("adapter", r.adapter.words)
        .put("make", r.make ?: JSONObject.NULL)
        .put("coverage", JSONObject()
            .put("mode", r.coverage.mode)
            .put("probed", r.coverage.probed)
            .put("planned", r.coverage.planned)
            .put("ranges", r.coverage.ranges)
            .put("complete", r.complete))
        .put("engine", r.engine?.let { engine(context, it) } ?: JSONObject.NULL)
        .put("modules", JSONArray(r.modules.map { module(context, it) }))
        .put("readings", JSONArray(r.readings.map(::reading)))
        .put("notes", JSONArray(r.notes))
        .put("compare", diff?.let {
            JSONObject().put("new", JSONArray(it.added)).put("gone", JSONArray(it.gone))
                .put("unchanged", JSONArray(it.unchanged)).put("not_rechecked", JSONArray(it.notRechecked))
        } ?: JSONObject.NULL)

    private fun engine(context: Context, e: EngineReport) = JSONObject()
        .put("stored", read(context, e.stored))
        .put("pending", read(context, e.pending))
        .put("permanent", read(context, e.permanent))
        .put("answered_by", JSONArray(e.responders))

    private fun module(context: Context, m: ModuleReport) = JSONObject()
        .put("address", m.address)
        .put("name", m.name ?: "unidentified module")
        .put("name_source", m.nameSource ?: "nobody: this address is in no table and the module did not name itself")
        .put("restraint", m.restraint)
        .put("codes", read(context, m.codes))
        .put("ids", JSONArray(m.ids.filter { it.outcome == Outcome.READ }.map { JSONObject().put(it.label, it.value) }))

    private fun read(context: Context, c: CodeRead): JSONObject = JSONObject()
        .put("outcome", c.outcome.words)
        .put("detail", c.detail ?: JSONObject.NULL)
        .put("list", JSONArray(c.dtcs.filter { it.fault || it.status == null }.map { d ->
            val meaning = DiagScanStore.describe(context, d.code)
            JSONObject().put("code", d.code).put("status", d.words.joinToString(", "))
                .put("meaning", meaning.words).put("meaning_source", meaning.source)
        }))

    fun clearResult(r: ClearResult): JSONObject = JSONObject()
        .put("module", r.target.label)
        .put("outcome", r.outcome.name)
        .put("words", r.outcome.words)
        .put("codes_before", JSONArray(r.before))
        .put("codes_after", r.after?.let { JSONArray(it) } ?: JSONObject.NULL)

    private fun reading(p: PackReadingResult) = JSONObject()
        .put("label", p.label)
        .put("value", p.value ?: p.outcome.words)
        .put("unit", p.unit)
        .put("verified", p.verified)
        .put("note", if (p.verified) "verified on this car" else UNVERIFIED)
        .put("source", p.source)

    private const val UNVERIFIED = "unverified: from a community list, not checked on this car"
}
