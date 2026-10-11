package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The marker a pulled row carries in [LedgerTransaction.sourceFile]. "synced" rather than a
 * fabricated filename (CLAUDE.md section 4 rule 5: never invent a fact the source did not state);
 * the server carries no filename for a row a pull downloads.
 *
 * **It is also the identity of a server-origin row**, and the one thing that lets
 * [LedgerTransactionsSync.mirror] delete safely: only a pull ever writes it, so a row with any other
 * [LedgerTransaction.sourceFile] - a voice-logged pending charge (`"voice"`), a statement this
 * phone parsed itself, anything minted here and not yet on the server - is never a delete
 * candidate. [com.kevin.legion.data.local.LedgerTransactionDao.deleteSyncedBySyncIds] repeats the
 * guard in SQL so a wrong id list cannot get past it either.
 */
const val SYNCED_SOURCE_FILE = "synced"

/**
 * What one mirror pass will do to Room, decided from the rows alone so it can be tested without a
 * database. [LedgerTransactionsSync.mirror] applies it in one transaction.
 */
data class LedgerMirrorPlan(
    val toInsert: List<LedgerTransaction>,
    /** Local row id to the category the server now states, filled only where the phone had none. */
    val categoryFills: List<CategoryFill>,
    /** `syncId`s of server-origin rows the server no longer lists. */
    val toDeleteSyncIds: List<String>,
    /** True when deletions were NOT planned because the server's list could not be trusted to be
     * the whole set, or because this pass refused a row it could not store (a value from a newer
     * build) - see [planLedgerMirror]. */
    val deletionsSkipped: Boolean,
    val alreadyPresent: Int,
    val unrecognizedProvenance: List<String>,
    /** The distinct raw provenance strings this pass could not map, for the log line: the
     * per-row sentences in [unrecognizedProvenance] carry merchant names, which a log should not. */
    val unrecognizedValues: Set<String> = emptySet(),
) {
    data class CategoryFill(val localId: Long, val category: String, val categoryPending: Boolean)
}

/** The provenance mapping [LedgerTransactionsSync.pull] has always applied: `USER` maps to
 * [IngestMethod.UNRECONCILED] (a hand-authored row has no document behind it), anything unknown is
 * refused and reported, never defaulted.
 *
 * **Why an unknown value is refused rather than stored under an UNKNOWN member** (2026-10-11, the
 * day a pre-BANK_API build met the engine's first `BANK_API` rows): every trust check in the app is
 * `== UNRECONCILED` (about twenty sites), so a new [IngestMethod] member falls into the plain,
 * counted-as-proven branch of all of them - the opposite of "never counted as verified". And a row
 * stored under a placeholder is "already present" on every later pull, so updating the app would
 * never repair it. A refused row is simply absent, and the mirror re-lists the whole set every
 * time, so the first build that knows the value stores it correctly. What a refusal MUST do is
 * block the deletions in the same pass - see [planLedgerMirror] - and be said in words
 * ([ledgerMirrorStatusLine]). */
internal fun ledgerIngestMethodFor(raw: String): IngestMethod? = when (raw) {
    "DETERMINISTIC" -> IngestMethod.DETERMINISTIC
    "LLM_RECONCILED" -> IngestMethod.LLM_RECONCILED
    "UNRECONCILED" -> IngestMethod.UNRECONCILED
    "USER" -> IngestMethod.UNRECONCILED
    "BANK_API" -> IngestMethod.BANK_API
    else -> null
}

/**
 * The phone's [LedgerTransaction.categoryPending] for a server row.
 *
 * **The two flags do not mean the same thing.** On the server `category_pending` defaults TRUE on
 * every uncategorised row - "nobody has categorised this yet". On the phone it means "this category
 * is an unconfirmed AI guess" and the ledger says "category guessed, not confirmed" beside such a
 * row. Copying it verbatim would label every uncategorised server row a guess. A row with no category has
 * nothing to confirm, so it is false; a row with one carries the server's flag.
 */
internal fun localCategoryPending(remote: RemoteLedgerTransaction): Boolean =
    remote.category != null && remote.categoryPending

/** `category_source` on the engine's transaction list when a person's override is what shows. */
internal const val CATEGORY_SOURCE_PERSON = "person"

/**
 * **THE precedence between the phone's category and the engine's, for one server-origin row, in
 * one place** (backend-etl ticket 14 option 2). The server's half of the same order lives in
 * `server/ingest/category_overrides.py`: person override, then rule override, then the row's own
 * stored category.
 *
 * 1. **A person's choice made on THIS phone that has not reached the server yet** ([personWriteQueued])
 *    wins over anything the server says: the server's value is older than it.
 * 2. **A server `person` category** (someone chose it - here and already sent, or on another client)
 *    wins over whatever the phone holds, including a category the phone's own rules filled in.
 * 3. **Otherwise the phone's category stands** - a server `rule` or `stored` category only fills a
 *    row the phone holds uncategorised. A category a person set on the phone is never overwritten
 *    by a rule, and neither is one the phone's rules chose (both come from the same rules).
 * 4. A server with no category changes nothing: the phone never un-categorises a row because the
 *    server has not caught up (a deleted override, an engine not yet backfilled).
 *
 * Returns the fill to apply, or null for "leave the phone's row as it is".
 */
internal fun mirroredCategoryFill(
    local: LedgerTransaction,
    remote: RemoteLedgerTransaction,
    personWriteQueued: Boolean,
): LedgerMirrorPlan.CategoryFill? {
    val serverCategory = remote.category
    val alreadyShowsIt = local.category == serverCategory && !local.categoryPending
    return when {
        local.sourceFile != SYNCED_SOURCE_FILE || serverCategory == null || personWriteQueued -> null
        remote.categorySource == CATEGORY_SOURCE_PERSON ->
            if (alreadyShowsIt) null else LedgerMirrorPlan.CategoryFill(local.id, serverCategory, false)
        local.category == null -> LedgerMirrorPlan.CategoryFill(local.id, serverCategory, localCategoryPending(remote))
        else -> null
    }
}

/** A server row as a new Room row. `syncId = serverId`, so a later pull recognises it. */
internal fun RemoteLedgerTransaction.toLocalRow(ingestMethod: IngestMethod): LedgerTransaction =
    LedgerTransaction(
        sourceFile = SYNCED_SOURCE_FILE,
        // Reverses LedgerReconcile's upload mapping (accountNickname = txn.accountId).
        accountId = accountNickname,
        currency = LedgerCurrency.valueOf(currency),
        txnDate = txnDateEpochMs,
        description = description,
        amountCents = amountCents,
        balanceCents = balanceCents,
        lineRef = lineRef,
        ingestMethod = ingestMethod,
        syncId = serverId,
        sourceFileId = null,
        category = category,
        categoryPending = localCategoryPending(this),
        pendingLoggedAt = pendingLoggedAtMs,
    )

/**
 * Make Room's server-origin rows match the engine's list (`server/api/ledger.py`'s
 * `LedgerTransactionViewSet` doc states the contract: the full list is the set).
 *
 * - **Insert** a server row the phone has never seen - recognised by `serverId` among local
 *   `syncId`s, or by its `origin_guid` (a row this phone minted and once uploaded).
 * - **Fill or replace a category** on a server-origin row by [mirroredCategoryFill]'s precedence -
 *   read it there, it is the one statement of the rule. In short: a server `person` category wins,
 *   a server `rule`/`stored` one only fills a gap, and a choice still queued on this phone
 *   ([pendingPersonServerIds]) is never overwritten by the older server value.
 * - **Delete** a server-origin row ([SYNCED_SOURCE_FILE]) whose `syncId` the server no longer
 *   lists - a rule-7 supersession removed it there. Only when [complete] is true, and not when the
 *   server listed nothing at all while the phone holds server rows: an empty answer is far more
 *   likely a wrong household or a fresh database than a server that deleted a whole ledger, and
 *   this path would otherwise wipe the phone's copy on the strength of it. **And not when this
 *   pass refused any row** ([LedgerMirrorPlan.unrecognizedProvenance]): the rows it could not
 *   store may be exactly the ones that replaced what the server stopped listing.
 *
 * Rows with any other [LedgerTransaction.sourceFile] are never touched.
 */
fun planLedgerMirror(
    local: List<LedgerTransaction>,
    remote: List<RemoteLedgerTransaction>,
    complete: Boolean,
    pendingPersonServerIds: Set<String> = emptySet(),
): LedgerMirrorPlan {
    val localBySyncId = local.associateBy { it.syncId }
    val toInsert = mutableListOf<LedgerTransaction>()
    val fills = mutableListOf<LedgerMirrorPlan.CategoryFill>()
    val unrecognized = mutableListOf<String>()
    val unrecognizedValues = sortedSetOf<String>()
    var alreadyPresent = 0

    for (r in remote) {
        val byServerId = localBySyncId[r.serverId]
        val byOriginGuid = r.originGuid?.let { localBySyncId[it] }
        val method = ledgerIngestMethodFor(r.provenance)
        when {
            byServerId != null || byOriginGuid != null -> {
                alreadyPresent++
                byServerId
                    ?.let { mirroredCategoryFill(it, r, personWriteQueued = r.serverId in pendingPersonServerIds) }
                    ?.let { fills += it }
            }
            method == null -> {
                unrecognized += "${r.description} (${r.serverId}): unrecognised provenance '${r.provenance}' - " +
                    "not inserted"
                unrecognizedValues += r.provenance
            }
            else -> toInsert += r.toLocalRow(method)
        }
    }

    val localSynced = local.filter { it.sourceFile == SYNCED_SOURCE_FILE }
    val emptyAgainstHeld = remote.isEmpty() && localSynced.isNotEmpty()
    // **Never delete what this pass refused to replace** (2026-10-11). The engine removes a
    // server-origin row when something supersedes it (a rule-7 supersession, a bank feed replacing
    // a card export). If the superseding rows are the ones this build cannot store, deleting the old
    // ones leaves the phone with neither: a pre-BANK_API build deleted 842 rows and stored none of
    // 906, then still said "Synced". A refusal therefore blocks every deletion in the pass.
    val deletable = complete && !emptyAgainstHeld && unrecognized.isEmpty()
    val listed = remote.mapTo(HashSet()) { it.serverId }
    val toDelete = if (deletable) localSynced.map { it.syncId }.filterNot { it in listed } else emptyList()

    return LedgerMirrorPlan(
        toInsert = toInsert,
        categoryFills = fills,
        toDeleteSyncIds = toDelete,
        deletionsSkipped = !deletable,
        alreadyPresent = alreadyPresent,
        unrecognizedProvenance = unrecognized,
        unrecognizedValues = unrecognizedValues,
    )
}

/**
 * When the engine's ledger was last read, and whether the latest attempt failed - so the Money
 * surfaces can say in words that they are showing the phone's copy (CLAUDE.md section 7: "the
 * phone reads from its Room replica when the server is down ... it says so in words").
 * Install-scoped, like every pull cursor in this package.
 */
object LedgerMirrorStatus {
    private const val PREFS = "ledger_transactions_mirror_status"
    private const val KEY_OK = "last_success_at_ms"
    private const val KEY_FAILED = "last_failure_at_ms"

    /** How many server rows the LAST successful pass refused to store (a value this build does not
     * know). Overwritten by every success, so a pass that refuses nothing clears it. */
    private const val KEY_REFUSED = "last_refused_rows"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val generation = MutableStateFlow(0)

    /**
     * Bumps whenever a mirror pass changed Room or its status changed. HOME refreshes on
     * `ON_RESUME` only, and the foreground mirror is fire-and-forget from that same resume, so
     * without this the first open after install would render "$0" and keep it until the next
     * resume even though the rows had arrived a second later.
     */
    val changes: StateFlow<Int> = generation.asStateFlow()

    /** [refusedRows] is what the pass could not store; it is part of the status because a pass that
     * refused rows is not "Synced" - see [ledgerMirrorStatusLine]. */
    fun recordSuccess(
        context: Context,
        changedRoom: Boolean,
        refusedRows: Int = 0,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val wasSaying = line(context) != null
        prefs(context).edit().putLong(KEY_OK, atMs).putInt(KEY_REFUSED, refusedRows).apply()
        if (changedRoom || wasSaying || refusedRows > 0) generation.update { it + 1 }
    }

    fun recordFailure(context: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_FAILED, atMs).apply()
        generation.update { it + 1 }
    }

    /** [ledgerMirrorStatusLine] over the stored instants. [short] for the HOME tile. */
    fun line(context: Context, short: Boolean = false): String? {
        val p = prefs(context)
        return ledgerMirrorStatusLine(
            p.getLong(KEY_OK, 0L), p.getLong(KEY_FAILED, 0L), short, p.getInt(KEY_REFUSED, 0),
        )
    }
}

/**
 * The sentence, or null when the latest attempt succeeded and refused nothing (or none was ever
 * made - an install not on the engine has nothing to confess). Unreadable and empty are different
 * sentences (CLAUDE.md section 1): with no successful read ever, the phone may hold nothing from the
 * server at all, and "$0 spent" must not read as a fact about the month.
 *
 * **A pass that refused rows is not "Synced" either** (2026-10-11). The server was reachable, but
 * [refusedRows] of its rows carried a value this build does not know, so they were not stored and
 * nothing was removed on their account: the figures are a stale copy, and the Money screen said
 * "Bank data through Sep 5" with no hint that the October rows were sitting unread on the server.
 * The sentence tells the person what to do about it.
 *
 * If the latest attempt FAILED as well as an earlier pass refusing rows, the failure leads and the
 * refusal follows in the long form; the tile's [short] form keeps only the failure, since the
 * refusal is re-stated by the next successful pass.
 *
 * [short] is the HOME tile's form. That disclosure is never truncated (a trust line is not
 * furniture), so on a half-width tile the full sentence would wrap to four lines; the short one
 * still says both halves in words - not synced, and whose copy this is.
 */
fun ledgerMirrorStatusLine(
    lastSuccessAtMs: Long,
    lastFailureAtMs: Long,
    short: Boolean = false,
    refusedRows: Int = 0,
): String? {
    val failing = lastFailureAtMs > lastSuccessAtMs
    val failure = when {
        !failing -> null
        short && lastSuccessAtMs == 0L -> "Not synced - this phone only"
        short -> "Not synced - this phone's last copy"
        lastSuccessAtMs == 0L -> "Not synced: the server couldn't be read, so this shows only what is on this phone"
        else -> "Not synced: the server couldn't be read just now, so this shows what this phone last had"
    }
    val refusal = when {
        refusedRows <= 0 -> null
        short -> "Some bank rows need a newer app - update LEGION"
        else -> "Some bank rows need a newer app ($refusedRows not shown). Update LEGION."
    }
    return when {
        failure == null -> refusal
        short || refusal == null -> failure
        else -> "$failure $refusal"
    }
}
