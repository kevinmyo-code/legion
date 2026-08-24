package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.StructuredOutputRequest
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The nine aspect-engine meta-tools (`.scratch/aspect-engine/issues/17-build-voice-surface.md`,
 * informed by ticket 06's answer and ticket 07's clerk prototype): `list_aspects`,
 * `describe_aspect`, `query_records`, `create_record`, `update_record`, `delete_record`,
 * `aspect_clerk`, `create_aspect`, `update_aspect`. [com.kevin.legion.service.LiveToolbox]
 * delegates to this module's [declarations]/[dispatch] rather than growing its own ~7,100-line
 * file further - a brand-new schema layer earns a new file the same way ledger/pantry each got
 * their own controller instead of living inside [LiveToolbox] directly.
 *
 * **[com.kevin.legion.engine.RecordStore] is the only write door for [EngineRecord] rows** - every
 * mutating tool below (`create_record`/`update_record`/`delete_record`, and `aspect_clerk`'s own
 * loop, which only ever calls back into these same three) goes through it, never
 * `EngineRecordDao` directly. `create_aspect`/`update_aspect` write schema rows
 * ([Aspect]/[RecordType]/[FieldDef]), which are outside [RecordStore]'s remit by design (see
 * `AspectDao`'s own doc comment: "an aspect's own lifecycle... has no reference-policy or
 * computed-field concerns, so it is not forced through that door too") - those go through their
 * own thin DAOs, and only after a confirmed second call (this file's own [pendingDrafts] handshake).
 *
 * **Provenance.** Every record this file writes is voice- or hand-entered, never document-derived,
 * so [create_record]/[update_record] always tag [RecordProvenance.USER] - there is no reconciliation
 * gate at this layer to reconcile against (CLAUDE.md §4 governs INGESTION paths; a user stating
 * "log my run, 5k, 28 minutes" is not an ingested document, it is a fact the user asserted about
 * themselves, the same category [EngineRecord]'s own doc comment carves out [RecordProvenance.USER]
 * for). What IS carried over from §4's posture is the outcome rule below.
 *
 * **The outcome rule** (CLAUDE.md §7, "the assistant never asserts an outcome it did not observe"):
 * every mutating tool's JSON result carries `"success"` and, on failure, a `"message"` stating IN
 * WORDS what did not happen and why - [RecordStore.WriteResult.Failure]/[RecordStore.DeleteResult]'s
 * own worded variants are threaded straight through, never swallowed into a bare boolean.
 *
 * **The estimate rule.** Nothing in this file guesses a field value the user did not give it -
 * every tool description below says so, and [aspectClerk]'s own system instruction repeats it as a
 * standing rule for the whole bounded loop, matching CLAUDE.md §4 rule 5's "anything not stated
 * must be labelled an estimate" applied one layer down: here, the rule is stronger - **never
 * fabricate a value at all**, ask instead.
 */
object EngineToolbox {

    // ---- schema helpers (mirrors LiveToolbox's own private fn/obj/schema, kept local so this
    // file stays self-contained rather than reaching into LiveToolbox's private members) ---------

    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun obj(vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { for ((k, v) in props) put(k, v) }

    private fun schema(type: String, description: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).put("description", description)
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    private fun result(success: Boolean, message: String?): JSONObject =
        JSONObject().put("success", success).apply { if (message != null) put("message", message) }

    // ---- the nine meta-tool names, exported so LiveToolbox can recognise them ------------------

    val TOOL_NAMES: Set<String> = setOf(
        "list_aspects", "describe_aspect", "query_records", "create_record", "update_record",
        "delete_record", "aspect_clerk", "create_aspect", "update_aspect",
    )

    /** The three that actually write an [EngineRecord] - what [aspectClerk] hands
     * [SubAgent.investigate] as `mutatingToolNames`, and the set [LiveToolbox]'s own
     * `MUTATING_TOOLS`/episodic-exclusion machinery should union in if it ever needs to know which
     * of these tools write. `create_aspect`/`update_aspect` are schema writes, not record writes,
     * and deliberately excluded - they are never offered to [aspectClerk] in the first place
     * (ticket 06's answer: schema edits route through the separate generator, not the clerk loop). */
    val MUTATING_RECORD_TOOLS: Set<String> = setOf("create_record", "update_record", "delete_record")

    private const val ROUTING_NOTE =
        " Routing: if you already know the exact record type and field names (for example you " +
            "just called describe_aspect yourself) and this is a single record to write, call " +
            "create_record/update_record/delete_record directly - it is faster. Reach for " +
            "aspect_clerk instead when the request needs you to find a record before changing it, " +
            "spans several records from one sentence, or names an aspect/record type you have not " +
            "already described."

    fun declarations(): JSONArray {
        val fns = JSONArray()

        fns.put(fn(
            name = "list_aspects",
            description = "Lists every aspect (a page of the user's life the app tracks - fleet, " +
                "ledger, pantry, and anything the user has defined themselves) and the record " +
                "types each one holds. Read-only; call this first when you do not already know " +
                "which aspects exist.",
            params = obj(),
            required = emptyList(),
        ))

        fns.put(fn(
            name = "describe_aspect",
            description = "Describes one aspect's record types and every field on each - name, " +
                "type, whether it is required, and the allowed options for a choice field. " +
                "Read-only. Call this before your first create_record/update_record/delete_record " +
                "against a record type you have not already described in this conversation, so " +
                "you use the real field names rather than guessing one.",
            params = obj("aspectName" to schema("string", "The aspect's name, e.g. \"fleet\".")),
            required = listOf("aspectName"),
        ))

        fns.put(fn(
            name = "query_records",
            description = "Finds records of one record type inside one aspect. Read-only. " +
                "\"filters\" is an object of field name to the exact value that field must equal " +
                "(a choice field's value must be one of its own options); leave it empty to list " +
                "everything. \"searchText\" does a loose substring match over the record's text " +
                "fields. \"limit\" caps how many rows come back (default 20).$ROUTING_NOTE",
            params = obj(
                "aspectName" to schema("string", "The aspect's name."),
                "recordTypeName" to schema("string", "The record type's name within that aspect."),
                "filters" to schema("object", "Field name to required exact value, or omit for no filter."),
                "searchText" to schema("string", "Optional loose text search over the record's text fields."),
                "limit" to schema("number", "Max rows to return, default 20."),
            ),
            required = listOf("aspectName", "recordTypeName"),
        ))

        fns.put(fn(
            name = "create_record",
            description = "Creates one new record of one record type inside one aspect. " +
                "\"fields\" is an object of field name to value, using the exact field names " +
                "describe_aspect reported - a date/datetime field takes an ISO string " +
                "(\"2026-08-23\" or a full ISO datetime), a money field takes whole CENTS as a " +
                "number (never dollars), a multi-select field takes a list of strings. Only write " +
                "a value the user actually told you - never guess or estimate one they did not " +
                "give you; leave a field out rather than invent it. Returns success only if the " +
                "record was actually written; on failure the message states in words what did not " +
                "happen (a bad reference, an unknown field, a missing record type) - never say " +
                "something was logged, saved, or recorded unless this tool reports " +
                "success.$ROUTING_NOTE",
            params = obj(
                "aspectName" to schema("string", "The aspect's name."),
                "recordTypeName" to schema("string", "The record type's name within that aspect."),
                "fields" to schema("object", "Field name to value, using describe_aspect's exact field names."),
            ),
            required = listOf("aspectName", "recordTypeName", "fields"),
        ))

        fns.put(fn(
            name = "update_record",
            description = "Updates fields on one existing record by its id. \"fields\" is a " +
                "PARTIAL object - only the keys you include change, everything else on the " +
                "record stays as it was. Same value rules as create_record: exact field names, " +
                "cents for money, ISO dates, never a guessed value. Returns success only if the " +
                "write actually happened; on failure the message states in words what did not " +
                "happen - never say something was updated unless this tool reports " +
                "success.$ROUTING_NOTE",
            params = obj(
                "recordId" to schema("number", "The record's id, from query_records or a prior create_record."),
                "fields" to schema("object", "Field name to new value - only the fields that changed."),
            ),
            required = listOf("recordId", "fields"),
        ))

        fns.put(fn(
            name = "delete_record",
            description = "Deletes (trashes, 30-day recoverable) one record by id, or a whole " +
                "matching set of records by aspect/recordType/filters. A SINGLE delete by " +
                "\"recordId\" runs immediately with no confirmation needed and returns a spoken " +
                "receipt of what was trashed - say it back plainly (\"deleted the oil change from " +
                "March\"). A BULK delete (recordId omitted, aspectName/recordTypeName/filters " +
                "given instead) NEVER deletes on the first call: it returns how many records " +
                "match and does nothing else. Call it again with the exact same arguments plus " +
                "\"confirm\": true only after telling the user the count and having them agree - " +
                "that second call is the one that actually deletes. Never say records were " +
                "deleted from the first, unconfirmed call - nothing was written yet. If deleting " +
                "is blocked (something else still references the record), the message says so in " +
                "words and nothing is deleted.$ROUTING_NOTE",
            params = obj(
                "recordId" to schema("number", "A single record's id for an immediate, unconfirmed delete."),
                "aspectName" to schema("string", "For a bulk delete: the aspect's name."),
                "recordTypeName" to schema("string", "For a bulk delete: the record type's name."),
                "filters" to schema("object", "For a bulk delete: field name to required exact value."),
                "confirm" to schema("boolean", "For a bulk delete only: true to actually delete the matched set."),
            ),
            required = emptyList(),
        ))

        fns.put(fn(
            name = "aspect_clerk",
            description = "Hands a natural-language instruction to a focused worker that reads " +
                "and writes the record store on its own (it calls list_aspects/describe_aspect/ " +
                "query_records/create_record/update_record/delete_record itself, in a bounded " +
                "loop, and reports back what it actually did). Use it for multi-step requests " +
                "(find a record, then change or delete it), for one sentence that produces " +
                "several rows (\"log three sets, 185 for 5 each\"), or when you do not already " +
                "know the record type's exact fields. It typically answers in one to a few " +
                "seconds and stays SILENT while working - if a call runs past about 4 seconds say " +
                "a short filler (\"working on it\") rather than leaving dead air, but do not " +
                "narrate every call. Its answer always states rows written and rows failed in " +
                "words; speak that outcome back plainly and never claim more was written than it " +
                "reports.$ROUTING_NOTE",
            params = obj("instruction" to schema("string", "The user's request, in their own words.")),
            required = listOf("instruction"),
        ))

        fns.put(fn(
            name = "create_aspect",
            description = "Authors a brand-new aspect (record types and their fields) from a " +
                "spoken description. NEVER writes anything on the first call - it drafts the " +
                "shape and returns it for you to read back to the user in plain language " +
                "(\"a Workouts aspect with an Exercise Log record type - exercise, weight, reps, " +
                "date\"). Only call this a SECOND time, with the exact same \"description\" and " +
                "the \"draftToken\" the first call returned plus \"confirm\": true, after the " +
                "user has heard the draft and agreed - that second call is the one that actually " +
                "creates it. Never tell the user an aspect was created before a confirmed call " +
                "reports success.",
            params = obj(
                "description" to schema("string", "What the user wants tracked, in their own words."),
                "confirm" to schema("boolean", "True only on the confirmed second call."),
                "draftToken" to schema("string", "The token the first call returned; required to confirm."),
            ),
            required = listOf("description"),
        ))

        fns.put(fn(
            name = "update_aspect",
            description = "Proposes ONE additive change to an existing aspect - a new record " +
                "type, a new field on an existing record type, or renaming the aspect - from a " +
                "spoken description. Same confirm handshake as create_aspect: the first call only " +
                "drafts the change and returns it to read back; call again with the same " +
                "\"aspectName\"/\"description\", the returned \"draftToken\", and \"confirm\": " +
                "true to actually apply it. Never say a change was made before a confirmed call " +
                "reports success.",
            params = obj(
                "aspectName" to schema("string", "The existing aspect's name."),
                "description" to schema("string", "The single change wanted, in the user's own words."),
                "confirm" to schema("boolean", "True only on the confirmed second call."),
                "draftToken" to schema("string", "The token the first call returned; required to confirm."),
            ),
            required = listOf("aspectName", "description"),
        ))

        return fns
    }

    /**
     * Runs one of the nine meta-tools, or returns null if [name] is not one of them so
     * [LiveToolbox.dispatch] can fall through to its own `when`.
     */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name !in TOOL_NAMES) return null
        val db = CarDatabase.getDatabase(context)
        return when (name) {
            "list_aspects" -> listAspects(db)
            "describe_aspect" -> describeAspect(db, args)
            "query_records" -> queryRecordsTool(db, args)
            "create_record" -> createRecord(db, args)
            "update_record" -> updateRecord(db, args)
            "delete_record" -> deleteRecord(db, args)
            "aspect_clerk" -> aspectClerk(context, args)
            "create_aspect" -> createAspect(db, args)
            "update_aspect" -> updateAspect(db, args)
            else -> null
        }
    }

    // ---- list_aspects / describe_aspect ----------------------------------------------------------

    private suspend fun listAspects(db: CarDatabase): JSONObject {
        val aspects = db.aspectDao().listActive()
        val out = JSONArray()
        for (a in aspects) {
            val recordTypes = db.recordTypeDao().listByAspect(a.id)
            out.put(
                JSONObject()
                    .put("name", a.name)
                    .put("recordTypes", JSONArray(recordTypes.map { it.name })),
            )
        }
        return JSONObject().put("aspects", out)
    }

    private suspend fun describeAspect(db: CarDatabase, args: JSONObject): JSONObject {
        val aspectName = args.optString("aspectName")
        val aspect = resolveAspect(db, aspectName)
            ?: return result(false, "There's no aspect called \"$aspectName\" - call list_aspects to see what exists.")

        val recordTypes = db.recordTypeDao().listByAspect(aspect.id)
        val recordTypesJson = JSONArray()
        for (rt in recordTypes) {
            val fieldDefs = db.fieldDefDao().forRecordType(rt.id)
            val fieldsJson = JSONArray()
            for (fd in fieldDefs) {
                val fieldJson = JSONObject()
                    .put("name", fd.name)
                    .put("type", fd.type.name)
                    .put("required", fd.required)
                if (fd.type == FieldType.CHOICE || fd.type == FieldType.MULTI_SELECT_CHOICE) {
                    fieldJson.put("options", JSONArray(FieldConfig.choiceOptions(fd.config)))
                }
                fieldsJson.put(fieldJson)
            }
            recordTypesJson.put(JSONObject().put("name", rt.name).put("fields", fieldsJson))
        }
        return JSONObject().put("aspect", aspect.name).put("recordTypes", recordTypesJson)
    }

    // ---- query_records ------------------------------------------------------------------------

    /** What a resolved query needs downstream - matched rows plus the field defs to serialize
     * them against. [error], when non-null, means the aspect/record type could not be resolved
     * and nothing else in this class is populated. */
    private class QueryOutcome(
        val recordType: RecordType?,
        val fieldDefs: List<FieldDef>,
        val matches: List<EngineRecord>,
        val error: String?,
    )

    private suspend fun runQuery(db: CarDatabase, args: JSONObject): QueryOutcome {
        val aspectName = args.optString("aspectName")
        val recordTypeName = args.optString("recordTypeName")
        val aspect = resolveAspect(db, aspectName)
            ?: return QueryOutcome(null, emptyList(), emptyList(),
                "There's no aspect called \"$aspectName\" - call list_aspects to see what exists.")
        val recordType = resolveRecordType(db, aspect.id, recordTypeName)
            ?: return QueryOutcome(null, emptyList(), emptyList(),
                "\"$aspectName\" has no record type called \"$recordTypeName\" - call describe_aspect to see what exists.")

        val fieldDefs = db.fieldDefDao().forRecordType(recordType.id)
        val all = db.engineRecordDao().activeByRecordType(recordType.id)
        val filters = args.optJSONObject("filters") ?: JSONObject()
        val searchText = args.optString("searchText").trim()
        val limit = args.optInt("limit", 20).coerceIn(1, 200)

        val matched = all
            .filter { matchesFilters(fieldDefs, it, filters) }
            .filter { searchText.isBlank() || it.searchText.contains(searchText, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(limit)

        return QueryOutcome(recordType, fieldDefs, matched, null)
    }

    private suspend fun queryRecordsTool(db: CarDatabase, args: JSONObject): JSONObject {
        val outcome = runQuery(db, args)
        outcome.error?.let { return result(false, it) }
        val rowsJson = JSONArray()
        for (r in outcome.matches) rowsJson.put(recordToJson(outcome.fieldDefs, r))
        return JSONObject().put("count", outcome.matches.size).put("records", rowsJson)
    }

    // ---- create_record / update_record -----------------------------------------------------------

    private suspend fun createRecord(db: CarDatabase, args: JSONObject): JSONObject {
        val aspectName = args.optString("aspectName")
        val recordTypeName = args.optString("recordTypeName")
        val aspect = resolveAspect(db, aspectName)
            ?: return result(false, "There's no aspect called \"$aspectName\" - call list_aspects to see what exists.")
        val recordType = resolveRecordType(db, aspect.id, recordTypeName)
            ?: return result(false, "\"$aspectName\" has no record type called \"$recordTypeName\" - call describe_aspect to see what exists.")

        val fieldDefs = db.fieldDefDao().forRecordType(recordType.id)
        val fieldsArg = args.optJSONObject("fields") ?: JSONObject()
        val (values, unknown) = buildFieldValues(fieldDefs, fieldsArg)
        if (unknown.isNotEmpty()) {
            return result(false, "\"$recordTypeName\" has no field(s) called ${unknown.joinToString(", ")} - " +
                "call describe_aspect to see the real field names. Nothing was written.")
        }

        val store = recordStore(db)
        return when (val r = store.create(recordType.id, values, RecordProvenance.USER)) {
            is RecordStore.WriteResult.Success -> result(true, "Created record #${r.recordId} in $recordTypeName.")
            is RecordStore.WriteResult.Failure -> result(false, r.reason)
        }
    }

    private suspend fun updateRecord(db: CarDatabase, args: JSONObject): JSONObject {
        val recordId = args.optLong("recordId", -1)
        if (recordId < 0) return result(false, "I need a record id to update - find it with query_records first.")
        val existing = db.engineRecordDao().getById(recordId)
            ?: return result(false, "There's no record #$recordId. Nothing was written.")

        val fieldDefs = db.fieldDefDao().forRecordType(existing.recordTypeId)
        val fieldsArg = args.optJSONObject("fields") ?: JSONObject()
        val (values, unknown) = buildFieldValues(fieldDefs, fieldsArg)
        if (unknown.isNotEmpty()) {
            return result(false, "That record's type has no field(s) called ${unknown.joinToString(", ")} - " +
                "call describe_aspect to see the real field names. Nothing was written.")
        }

        val store = recordStore(db)
        return when (val r = store.update(recordId, values)) {
            is RecordStore.WriteResult.Success -> result(true, "Updated record #${r.recordId}.")
            is RecordStore.WriteResult.Failure -> result(false, r.reason)
        }
    }

    // ---- delete_record: single unconfirmed with receipt, bulk confirm-first --------------------

    private suspend fun deleteRecord(db: CarDatabase, args: JSONObject): JSONObject {
        val recordId = args.optLong("recordId", -1)
        return if (recordId >= 0) deleteSingle(db, recordId) else deleteBulk(db, args)
    }

    private suspend fun deleteSingle(db: CarDatabase, recordId: Long): JSONObject {
        val existing = db.engineRecordDao().getById(recordId)
            ?: return result(false, "There's no record #$recordId. Nothing was deleted.")
        val recordType = db.recordTypeDao().getById(existing.recordTypeId)
        val fieldDefs = if (recordType != null) db.fieldDefDao().forRecordType(recordType.id) else emptyList()
        val receiptSubject = receiptLabel(fieldDefs, existing)

        val store = recordStore(db)
        return when (val r = store.delete(recordId)) {
            RecordStore.DeleteResult.Trashed -> result(
                true,
                "Deleted ${recordType?.name ?: "record"} #$recordId${receiptSubject?.let { " ($it)" } ?: ""}. " +
                    "It stays in trash for 30 days if you want it back.",
            )
            RecordStore.DeleteResult.NotFound -> result(false, "There's no record #$recordId. Nothing was deleted.")
            RecordStore.DeleteResult.AlreadyTrashed -> result(false, "Record #$recordId is already in trash.")
            is RecordStore.DeleteResult.Blocked -> result(
                false,
                "Can't delete record #$recordId - ${r.blockers.joinToString("; ")}. Nothing was deleted.",
            )
        }
    }

    private suspend fun deleteBulk(db: CarDatabase, args: JSONObject): JSONObject {
        if (args.optString("aspectName").isBlank() || args.optString("recordTypeName").isBlank()) {
            return result(false, "For a bulk delete I need an aspectName and recordTypeName - or give me a single recordId instead.")
        }
        val outcome = runQuery(db, args)
        outcome.error?.let { return result(false, it) }

        if (outcome.matches.isEmpty()) {
            return result(false, "Nothing matches that description - there's nothing to delete.")
        }

        val confirm = args.optBoolean("confirm", false)
        if (!confirm) {
            // Ticket 06 answer point 2: a query-matched bulk delete confirms with the count first.
            // NOTHING is written here - RecordStore.delete is never called on this branch.
            val preview = outcome.matches.take(5).map { recordToJson(outcome.fieldDefs, it) }
            return JSONObject()
                .put("success", true)
                .put("committed", false)
                .put("matchedCount", outcome.matches.size)
                .put("preview", JSONArray(preview))
                .put(
                    "message",
                    "${outcome.matches.size} record(s) match. Nothing has been deleted yet - call " +
                        "delete_record again with the same arguments and confirm: true only after " +
                        "the user agrees.",
                )
        }

        val store = recordStore(db)
        var trashed = 0
        val blocked = mutableListOf<String>()
        for (record in outcome.matches) {
            when (val r = store.delete(record.id)) {
                RecordStore.DeleteResult.Trashed -> trashed++
                is RecordStore.DeleteResult.Blocked -> blocked += "#${record.id}: ${r.blockers.joinToString("; ")}"
                RecordStore.DeleteResult.NotFound, RecordStore.DeleteResult.AlreadyTrashed -> Unit
            }
        }
        val message = buildString {
            append("Deleted $trashed of ${outcome.matches.size} matching record(s).")
            if (blocked.isNotEmpty()) append(" ${blocked.size} were blocked: ${blocked.joinToString("; ")}.")
        }
        return result(trashed > 0, message)
    }

    /** A short human-readable tag for a delete receipt - the record's first non-blank text-shaped
     * field, so "deleted the oil change" reads better than "deleted record #41". Null (no
     * parenthetical) rather than a fabricated label when nothing text-shaped is set. */
    private fun receiptLabel(fieldDefs: List<FieldDef>, record: EngineRecord): String? {
        val payload = JSONObject(record.payload)
        for (fd in fieldDefs) {
            if (fd.type != FieldType.TEXT && fd.type != FieldType.CHOICE) continue
            val v = PayloadCodec.readString(payload, fd.id)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // ---- aspect_clerk: bounded executor loop over the six read/write meta-tools ------------------

    /**
     * The executor sub-agent (ticket 07's prototype, ticket 06's answer point 1). Runs on
     * [SubAgent.DEFAULT_MODEL] (Flash) - the prototype measured 1.1-2.5s median against 4-6x
     * slower on the Pro tier, with equal reliability on field names and row counts, so Pro stays
     * reserved for the schema generator ([createAspect]/[updateAspect]) where the extra latency
     * costs nothing on an off-turn, one-shot draft.
     *
     * **Grounded date, not a guessed one** (ticket 07's own carried-forward requirement): the
     * prototype's one real failure was Flash guessing a wrong "today" with no date in its context
     * and confidently reporting no match. [system] states the real wall clock and UTC offset up
     * front, the same "never hand the model an IANA timezone id, state the offset" discipline
     * [com.kevin.legion.ai.AriaBrain.utcOffset] already uses for the live session.
     */
    private suspend fun aspectClerk(context: Context, args: JSONObject): JSONObject {
        val instruction = args.optString("instruction")
        if (instruction.isBlank()) {
            return result(false, "Tell me what to log, find, change, or remove and I can act on it.")
        }
        if (!GeminiKeyProvider.hasKey()) {
            return result(false, "I need a Gemini key to do that - add your own in Setup to keep going.")
        }

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val stamp = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }

        val system = "You are the aspect clerk: a focused worker that reads and writes the " +
            "user's personal record store through the tools you are given - list_aspects, " +
            "describe_aspect, query_records, create_record, update_record, delete_record. Right " +
            "now it is $stamp (UTC$offset) - use this as \"today\"/\"now\"/\"this week\"; never " +
            "guess a date of your own. Call describe_aspect before your first write to a record " +
            "type you have not already described in this conversation, so you use the real field " +
            "names instead of inventing one. Only write a value the user actually told you - " +
            "never guess, estimate, or infer a field value they did not give you; ask instead " +
            "by answering in prose. A bulk delete_record call always needs a confirmed second " +
            "call with confirm: true before anything is removed - never skip that. When you are " +
            "done, state plainly, in words, how many rows you wrote and how many failed - never " +
            "say something was recorded, updated, or deleted unless you actually called the tool " +
            "for it and it reported success."

        val tools = listOf(
            engineAgentTool(context, "list_aspects"),
            engineAgentTool(context, "describe_aspect"),
            engineAgentTool(context, "query_records"),
            engineAgentTool(context, "create_record"),
            engineAgentTool(context, "update_record"),
            engineAgentTool(context, "delete_record"),
        )

        return clerkResult("I couldn't reach the record-keeping worker just now - try again in a sec.") {
            SubAgent(systemInstruction = system, useSearch = false).investigate(
                context = "",
                question = instruction,
                tools = tools,
                maxModelCalls = 4,
                budgetMs = 30_000,
                mutatingToolNames = MUTATING_RECORD_TOOLS,
            )
        }
    }

    /** Wraps one meta-tool as an [AgentTool] for [aspectClerk]'s own investigate loop - the exact
     * "replay the call through dispatch" shape [LiveToolbox.agentToolsFor] already uses for the
     * five domain dispatchers, applied to this file's own six read/write tools instead of a
     * declarations lookup, since these nine are never hidden behind [LiveToolbox]'s `DISPATCHED`
     * map in the first place (ticket 06's surface is meant to be seen directly, not dispatched). */
    private fun engineAgentTool(context: Context, name: String): AgentTool {
        val decl = declarationByName(name)
        val params = decl.getJSONObject("parameters")
        val properties = params.optJSONObject("properties") ?: JSONObject()
        val requiredArr = params.optJSONArray("required") ?: JSONArray()
        val required = (0 until requiredArr.length()).map { requiredArr.getString(it) }
        return AgentTool(
            name = name,
            description = decl.getString("description"),
            params = properties,
            required = required,
            timeoutMs = 8_000,
            run = { args -> dispatch(context, name, args)?.toString() ?: "{}" },
        )
    }

    private fun declarationByName(name: String): JSONObject {
        val all = declarations()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            if (d.getString("name") == name) return d
        }
        error("no declaration named $name")
    }

    /** Same shape as [LiveToolbox]'s private `agentResult`, kept local to this file. One real
     * difference from that helper: no `requireMutation` gate here - [aspectClerk]'s own system
     * instruction already states the outcome rule as a standing loop rule, and its worded
     * rows-written/rows-failed answer IS the honesty contract (ticket 07 answer point 3), so
     * there is no separate boolean to gate on the way the five domain dispatchers need one for a
     * free-prose `question` with no declared intent. */
    internal suspend fun clerkResult(failMessage: String, call: suspend () -> AgentResult): JSONObject = when (val r = call()) {
        is AgentResult.Success -> result(true, r.text)
        AgentResult.RateLimited -> result(false, "The Gemini key just hit its rate limit - give it a minute and ask again.")
        AgentResult.KeyInvalid -> result(false, "Something's wrong with the Gemini key - worth checking it in Setup.")
        AgentResult.Offline -> result(false, "No data signal right now - ask again once you're back online.")
        AgentResult.Failed, AgentResult.Overloaded -> result(false, failMessage)
    }

    // ---- create_aspect / update_aspect: Pro-tier generator + confirm handshake -------------------

    /** Reserved for schema generation only (ticket 06 answer point 1, ticket 07's recommendation)
     * - an off-turn, one-shot draft with no latency floor to the voice loop, where Pro's extra
     * 4-6x latency costs nothing and its stronger reasoning is worth it for a structural task like
     * inventing a field list. Never used for [aspectClerk]'s own live loop. */
    private const val SCHEMA_GENERATOR_MODEL = "gemini-3.1-pro-preview"

    /** One drafted-but-uncommitted schema change, held in memory only - nothing here is
     * [Aspect]/[RecordType]/[FieldDef] rows yet. Deliberately process-lifetime, not persisted: a
     * draft that outlives the app process is not a live conversation's context anymore, and
     * losing an unconfirmed draft on process death is the same "nothing partial is ever written"
     * posture CLAUDE.md §4 rule 2 states for ingestion, applied to schema authoring. */
    internal data class PendingDraft(val json: JSONObject, val createdAtMs: Long, val targetAspectName: String?)

    private val pendingDrafts = mutableMapOf<String, PendingDraft>()
    internal const val DRAFT_TTL_MS = 10 * 60 * 1000L

    /** `internal` (not private) so [com.kevin.legion.service.EngineToolboxDraftHandshakeTest] can
     * exercise the confirm handshake's state machine directly, without a real schema-generator
     * network call - the same "pure logic split out for a network-free test" shape
     * [LiveToolbox.successOrMutationRefusal] already uses. */
    internal fun stashDraft(json: JSONObject, targetAspectName: String?): String {
        val now = System.currentTimeMillis()
        pendingDrafts.entries.removeAll { now - it.value.createdAtMs > DRAFT_TTL_MS }
        val token = UUID.randomUUID().toString().take(8)
        pendingDrafts[token] = PendingDraft(json, now, targetAspectName)
        return token
    }

    /** Consumes (removes) a pending draft if [token] is known, not expired, and (when
     * [targetAspectName] is non-null, i.e. an `update_aspect` confirm) matches the aspect the
     * draft was made against - a stale or mismatched token commits NOTHING. `internal`, see
     * [stashDraft]'s doc comment. */
    internal fun takeDraft(token: String, targetAspectName: String?): JSONObject? {
        val now = System.currentTimeMillis()
        val pending = pendingDrafts[token] ?: return null
        pendingDrafts.remove(token)
        if (now - pending.createdAtMs > DRAFT_TTL_MS) return null
        if (targetAspectName != null && !pending.targetAspectName.equals(targetAspectName, ignoreCase = true)) return null
        return pending.json
    }

    /** OpenAPI-3.0-subset schema for one field of a drafted record type (see
     * [com.kevin.legion.ai.StructuredOutputRequest]'s doc comment for the accepted shape -
     * UPPERCASE `type` values, unlike the lowercase `functionDeclarations` schema this same file
     * uses elsewhere). Deliberately narrower than the engine's full v1 vocabulary: REFERENCE,
     * PHOTO, LOCATION, and COMPUTED fields are not voice-authorable in this pass - a reference
     * needs a target record type to already exist and a delete policy decision, a computed field
     * needs a source-field pairing, and photo/location both need a capture flow this tool surface
     * has no camera/GPS access to drive. A user who wants one of those still has the hands path
     * (once the generated-forms ticket builds it); this is a deliberate v1 narrowing, not a bug. */
    private fun fieldDraftSchema(): JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("name", JSONObject().put("type", "STRING"))
                .put(
                    "type",
                    JSONObject().put("type", "STRING").put(
                        "enum",
                        JSONArray(listOf("TEXT", "NUMBER", "MONEY_CENTS", "DATE", "DATETIME", "BOOLEAN", "CHOICE", "MULTI_SELECT_CHOICE", "RATING")),
                    ),
                )
                .put("required", JSONObject().put("type", "BOOLEAN"))
                .put("choiceOptions", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))),
        )
        .put("required", JSONArray(listOf("name", "type")))

    private fun recordTypeDraftSchema(): JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("name", JSONObject().put("type", "STRING"))
                .put("fields", JSONObject().put("type", "ARRAY").put("items", fieldDraftSchema())),
        )
        .put("required", JSONArray(listOf("name", "fields")))

    private fun createAspectSchema(): JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("aspectName", JSONObject().put("type", "STRING"))
                .put("recordTypes", JSONObject().put("type", "ARRAY").put("items", recordTypeDraftSchema())),
        )
        .put("required", JSONArray(listOf("aspectName", "recordTypes")))

    private fun updateAspectSchema(): JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put(
                    "changeType",
                    JSONObject().put("type", "STRING")
                        .put("enum", JSONArray(listOf("ADD_RECORD_TYPE", "ADD_FIELD", "RENAME_ASPECT"))),
                )
                .put("newAspectName", JSONObject().put("type", "STRING").put("nullable", true))
                .put("newRecordType", recordTypeDraftSchema().put("nullable", true))
                .put("targetRecordTypeName", JSONObject().put("type", "STRING").put("nullable", true))
                .put("newField", fieldDraftSchema().put("nullable", true)),
        )
        .put("required", JSONArray(listOf("changeType")))

    private suspend fun createAspect(db: CarDatabase, args: JSONObject): JSONObject {
        val description = args.optString("description")
        if (description.isBlank()) return result(false, "Tell me what you want tracked and I can draft it.")

        if (args.optBoolean("confirm", false)) {
            val token = args.optString("draftToken")
            val draft = takeDraft(token, targetAspectName = null)
                ?: return result(false, "That draft has expired or I don't recognize it - describe the aspect again and I'll draft a fresh one. Nothing was created.")
            return commitCreateAspect(db, draft)
        }

        val agent = SubAgent(model = SCHEMA_GENERATOR_MODEL, useSearch = false)
        val response = agent.askTyped(
            context = "",
            question = "Draft a new personal-tracking aspect for this request: \"$description\". " +
                "Give it a short, plain aspectName and one or more record types, each with a " +
                "field list. Field types are TEXT, NUMBER, MONEY_CENTS (whole cents, never " +
                "dollars), DATE, DATETIME, BOOLEAN, CHOICE (with choiceOptions), " +
                "MULTI_SELECT_CHOICE (with choiceOptions), or RATING. Keep it to only what was " +
                "actually asked for - do not invent extra record types or fields.",
            structuredOutput = StructuredOutputRequest(createAspectSchema()),
        )
        val draftJson = (response as? AgentResult.Success)?.text?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return result(false, "I couldn't draft that aspect just now - try again in a sec.")

        val token = stashDraft(draftJson, targetAspectName = null)
        return JSONObject()
            .put("success", true)
            .put("committed", false)
            .put("draftToken", token)
            .put("draft", draftJson)
            .put(
                "message",
                "Draft ready, NOT saved yet: ${summarizeCreateDraft(draftJson)} Read this back to " +
                    "the user; only call create_aspect again with confirm: true and this " +
                    "draftToken once they agree.",
            )
    }

    private suspend fun commitCreateAspect(db: CarDatabase, draft: JSONObject): JSONObject {
        val aspectName = draft.optString("aspectName").ifBlank { return result(false, "The draft had no aspect name. Nothing was created.") }
        val now = System.currentTimeMillis()
        val aspectId = db.aspectDao().insert(Aspect(name = aspectName, createdAt = now, updatedAt = now))

        val recordTypes = draft.optJSONArray("recordTypes") ?: JSONArray()
        var recordTypeCount = 0
        var fieldCount = 0
        for (i in 0 until recordTypes.length()) {
            val rt = recordTypes.getJSONObject(i)
            val rtName = rt.optString("name").ifBlank { continue }
            val recordTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = rtName, createdAt = now, updatedAt = now))
            recordTypeCount++
            val fields = rt.optJSONArray("fields") ?: JSONArray()
            for (j in 0 until fields.length()) {
                insertDraftedField(db, recordTypeId, fields.getJSONObject(j), now)
                fieldCount++
            }
        }
        return result(true, "Created the \"$aspectName\" aspect with $recordTypeCount record type(s) and $fieldCount field(s).")
    }

    private suspend fun insertDraftedField(db: CarDatabase, recordTypeId: Long, fieldJson: JSONObject, now: Long) {
        val name = fieldJson.optString("name").ifBlank { return }
        val type = runCatching { FieldType.valueOf(fieldJson.optString("type")) }.getOrNull() ?: FieldType.TEXT
        val config = if (type == FieldType.CHOICE || type == FieldType.MULTI_SELECT_CHOICE) {
            val options = fieldJson.optJSONArray("choiceOptions")
            val optionList = if (options != null) (0 until options.length()).map { options.getString(it) } else emptyList()
            FieldConfig.serializeChoice(optionList)
        } else {
            null
        }
        db.fieldDefDao().insert(
            FieldDef(
                recordTypeId = recordTypeId,
                name = name,
                type = type,
                required = fieldJson.optBoolean("required", false),
                config = config,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun summarizeCreateDraft(draft: JSONObject): String {
        val aspectName = draft.optString("aspectName")
        val recordTypes = draft.optJSONArray("recordTypes") ?: JSONArray()
        val parts = (0 until recordTypes.length()).map { i ->
            val rt = recordTypes.getJSONObject(i)
            val fields = rt.optJSONArray("fields") ?: JSONArray()
            val fieldNames = (0 until fields.length()).map { fields.getJSONObject(it).optString("name") }
            "${rt.optString("name")} (${fieldNames.joinToString(", ")})"
        }
        return "an aspect called \"$aspectName\" with record type(s): ${parts.joinToString("; ")}."
    }

    private suspend fun updateAspect(db: CarDatabase, args: JSONObject): JSONObject {
        val aspectName = args.optString("aspectName")
        val description = args.optString("description")
        val aspect = resolveAspect(db, aspectName)
            ?: return result(false, "There's no aspect called \"$aspectName\" - call list_aspects to see what exists.")

        if (args.optBoolean("confirm", false)) {
            val token = args.optString("draftToken")
            val draft = takeDraft(token, targetAspectName = aspectName)
                ?: return result(false, "That draft has expired or I don't recognize it - describe the change again and I'll draft a fresh one. Nothing changed.")
            return commitUpdateAspect(db, aspect, draft)
        }
        if (description.isBlank()) return result(false, "Tell me what you want to change about \"$aspectName\" and I can draft it.")

        val recordTypes = db.recordTypeDao().listByAspect(aspect.id)
        val shapeParts = mutableListOf<String>()
        for (rt in recordTypes) {
            val fields = db.fieldDefDao().forRecordType(rt.id)
            shapeParts += "${rt.name}: ${fields.joinToString(", ") { it.name }}"
        }
        val existingShape = shapeParts.joinToString("; ")

        val agent = SubAgent(model = SCHEMA_GENERATOR_MODEL, useSearch = false)
        val response = agent.askTyped(
            context = "Aspect \"$aspectName\" currently has these record types and fields: $existingShape",
            question = "Draft exactly ONE additive change for this request: \"$description\". " +
                "changeType is ADD_RECORD_TYPE (fill newRecordType), ADD_FIELD (fill " +
                "targetRecordTypeName and newField), or RENAME_ASPECT (fill newAspectName). " +
                "Never propose removing or renaming an existing field or record type.",
            structuredOutput = StructuredOutputRequest(updateAspectSchema()),
        )
        val draftJson = (response as? AgentResult.Success)?.text?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return result(false, "I couldn't draft that change just now - try again in a sec.")

        val token = stashDraft(draftJson, targetAspectName = aspectName)
        return JSONObject()
            .put("success", true)
            .put("committed", false)
            .put("draftToken", token)
            .put("draft", draftJson)
            .put(
                "message",
                "Draft ready, NOT applied yet: ${summarizeUpdateDraft(draftJson)} Read this back " +
                    "to the user; only call update_aspect again with confirm: true and this " +
                    "draftToken once they agree.",
            )
    }

    private suspend fun commitUpdateAspect(db: CarDatabase, aspect: Aspect, draft: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        return when (draft.optString("changeType")) {
            "RENAME_ASPECT" -> {
                val newName = draft.optString("newAspectName").ifBlank { return result(false, "The draft had no new name. Nothing changed.") }
                db.aspectDao().update(aspect.copy(name = newName, updatedAt = now))
                result(true, "Renamed \"${aspect.name}\" to \"$newName\".")
            }
            "ADD_RECORD_TYPE" -> {
                val rt = draft.optJSONObject("newRecordType") ?: return result(false, "The draft had no record type. Nothing changed.")
                val rtName = rt.optString("name").ifBlank { return result(false, "The draft's record type had no name. Nothing changed.") }
                val recordTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspect.id, name = rtName, createdAt = now, updatedAt = now))
                val fields = rt.optJSONArray("fields") ?: JSONArray()
                for (j in 0 until fields.length()) insertDraftedField(db, recordTypeId, fields.getJSONObject(j), now)
                result(true, "Added record type \"$rtName\" (${fields.length()} field(s)) to \"${aspect.name}\".")
            }
            "ADD_FIELD" -> {
                val targetName = draft.optString("targetRecordTypeName")
                val target = resolveRecordType(db, aspect.id, targetName)
                    ?: return result(false, "\"${aspect.name}\" has no record type called \"$targetName\". Nothing changed.")
                val fieldJson = draft.optJSONObject("newField") ?: return result(false, "The draft had no field. Nothing changed.")
                insertDraftedField(db, target.id, fieldJson, now)
                result(true, "Added field \"${fieldJson.optString("name")}\" to \"${target.name}\".")
            }
            else -> result(false, "I don't recognize that draft's change type. Nothing changed.")
        }
    }

    private fun summarizeUpdateDraft(draft: JSONObject): String = when (draft.optString("changeType")) {
        "RENAME_ASPECT" -> "rename it to \"${draft.optString("newAspectName")}\"."
        "ADD_RECORD_TYPE" -> {
            val rt = draft.optJSONObject("newRecordType")
            val fields = rt?.optJSONArray("fields") ?: JSONArray()
            val names = (0 until fields.length()).map { fields.getJSONObject(it).optString("name") }
            "add a record type \"${rt?.optString("name")}\" with fields: ${names.joinToString(", ")}."
        }
        "ADD_FIELD" -> "add a field \"${draft.optJSONObject("newField")?.optString("name")}\" to " +
            "\"${draft.optString("targetRecordTypeName")}\"."
        else -> "an unrecognised change."
    }

    // ---- shared resolution / conversion helpers --------------------------------------------------

    private fun recordStore(db: CarDatabase): RecordStore =
        RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    private suspend fun resolveAspect(db: CarDatabase, name: String): Aspect? =
        db.aspectDao().listActive().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private suspend fun resolveRecordType(db: CarDatabase, aspectId: Long, name: String): RecordType? =
        db.recordTypeDao().listByAspect(aspectId).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Parses a spoken/typed date-shaped argument into epoch millis - a bare `yyyy-MM-dd` for a
     * [FieldType.DATE] field (midnight, device zone), or a full ISO-8601 instant/local-datetime
     * for [FieldType.DATETIME]. Returns null (never throws) on anything else, matching
     * [LiveToolbox]'s own `parseIsoDate` convention of a bad model-supplied date degrading to "no
     * value" rather than crashing tool dispatch. */
    private fun parseDateArg(raw: String, hasTimeOfDay: Boolean): Long? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return try {
            if (hasTimeOfDay) {
                try {
                    OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            } else {
                LocalDate.parse(trimmed).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Coerces one JSON-args value to the Kotlin shape [RecordStore.create]/[RecordStore.update]
     * (via [com.kevin.legion.engine.PayloadCodec.write]) expects for [fieldDef]'s type. A
     * [FieldType.COMPUTED] value is always ignored (materialized by [RecordStore], never
     * driver-supplied - matching that class's own doc comment), and a [FieldType.REFERENCE]/
     * [FieldType.PHOTO]/[FieldType.LOCATION] value is passed through best-effort even though this
     * tool surface has no schema-generator path that creates those field types yet (an existing
     * plugin-declared field of one of those types can still exist and be written to by voice). */
    private fun coerceFieldValue(fieldDef: FieldDef, raw: Any?): Any? {
        if (raw == null || raw == JSONObject.NULL) return null
        return when (fieldDef.type) {
            FieldType.COMPUTED -> null
            FieldType.TEXT, FieldType.CHOICE, FieldType.PHOTO, FieldType.LOCATION -> raw.toString()
            FieldType.NUMBER, FieldType.RATING -> (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
            FieldType.MONEY_CENTS, FieldType.REFERENCE -> (raw as? Number)?.toLong() ?: raw.toString().toLongOrNull()
            FieldType.DATE -> parseDateArg(raw.toString(), hasTimeOfDay = false)
            FieldType.DATETIME -> parseDateArg(raw.toString(), hasTimeOfDay = true)
            FieldType.BOOLEAN -> raw as? Boolean ?: raw.toString().toBooleanStrictOrNull()
            FieldType.MULTI_SELECT_CHOICE -> when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is List<*> -> raw.map { it.toString() }
                else -> listOf(raw.toString())
            }
        }
    }

    /** Builds a [FieldDef.id]-keyed value map from a name-keyed JSON args object, matching field
     * names case-insensitively (voice transcripts do not reliably preserve case). Returns the
     * names in [fieldsJson] that matched no field on this record type as a second list, so the
     * caller can refuse the whole write with a worded reason rather than silently dropping an
     * unrecognised field (CLAUDE.md §4 rule 6's "an unrecognised line is a hard failure, never a
     * silent skip", applied to a field name instead of a statement line). */
    private fun buildFieldValues(fieldDefs: List<FieldDef>, fieldsJson: JSONObject): Pair<Map<Long, Any?>, List<String>> {
        val byName = fieldDefs.associateBy { it.name.lowercase() }
        val values = mutableMapOf<Long, Any?>()
        val unknown = mutableListOf<String>()
        val keys = fieldsJson.keys()
        for (k in keys) {
            val fd = byName[k.lowercase()]
            if (fd == null) {
                unknown += k
                continue
            }
            values[fd.id] = coerceFieldValue(fd, fieldsJson.opt(k))
        }
        return values to unknown
    }

    /** True when every filter in [filters] equals the record's stored value for that field name
     * (case-insensitive field name match; an unknown filter field name matches nothing, the same
     * "unrecognised is a hard failure, not a silent pass" posture [buildFieldValues] uses). */
    private fun matchesFilters(fieldDefs: List<FieldDef>, record: EngineRecord, filters: JSONObject): Boolean {
        if (filters.length() == 0) return true
        val payload = JSONObject(record.payload)
        val byName = fieldDefs.associateBy { it.name.lowercase() }
        for (k in filters.keys()) {
            val fd = byName[k.lowercase()] ?: return false
            val wanted = coerceFieldValue(fd, filters.opt(k))
            if (fd.type == FieldType.MULTI_SELECT_CHOICE) {
                val stored = payload.opt(PayloadCodec.key(fd.id))
                val list = if (stored is JSONArray) (0 until stored.length()).map { stored.optString(it) } else emptyList()
                if (wanted !in list) return false
                continue
            }
            val actual: Any? = when (fd.type) {
                FieldType.MONEY_CENTS, FieldType.DATE, FieldType.DATETIME, FieldType.REFERENCE ->
                    PayloadCodec.readLong(payload, fd.id)
                FieldType.NUMBER, FieldType.RATING -> PayloadCodec.readDouble(payload, fd.id)
                FieldType.BOOLEAN -> payload.opt(PayloadCodec.key(fd.id))
                else -> PayloadCodec.readString(payload, fd.id)
            }
            if (actual?.toString() != wanted?.toString()) return false
        }
        return true
    }

    /** Serializes one [EngineRecord] to the JSON shape [query_records]/delete-preview hands back
     * to the model - promoted columns plus every non-null field, keyed by its real name (never
     * the raw [FieldDef.id]) so the model reads and echoes names it can act on directly. */
    private fun recordToJson(fieldDefs: List<FieldDef>, record: EngineRecord): JSONObject {
        val payload = JSONObject(record.payload)
        val out = JSONObject()
            .put("id", record.id)
            .put("createdAt", record.createdAt)
            .put("updatedAt", record.updatedAt)
        if (record.dueAt != null) out.put("dueAt", record.dueAt)
        if (record.amountCents != null) out.put("amountCents", record.amountCents)
        val fieldsOut = JSONObject()
        for (fd in fieldDefs) {
            val v = payload.opt(PayloadCodec.key(fd.id))
            if (v != null && v != JSONObject.NULL) fieldsOut.put(fd.name, v)
        }
        out.put("fields", fieldsOut)
        return out
    }
}
