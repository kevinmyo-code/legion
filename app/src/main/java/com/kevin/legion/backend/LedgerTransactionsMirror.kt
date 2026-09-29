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
     * the whole set - see [planLedgerMirror]. */
    val deletionsSkipped: Boolean,
    val alreadyPresent: Int,
    val unrecognizedProvenance: List<String>,
) {
    data class CategoryFill(val localId: Long, val category: String, val categoryPending: Boolean)
}

/** The provenance mapping [LedgerTransactionsSync.pull] has always applied: `USER` maps to
 * [IngestMethod.UNRECONCILED] (a hand-authored row has no document behind it), anything unknown is
 * refused and reported, never defaulted. */
internal fun ledgerIngestMethodFor(raw: String): IngestMethod? = when (raw) {
    "DETERMINISTIC" -> IngestMethod.DETERMINISTIC
    "LLM_RECONCILED" -> IngestMethod.LLM_RECONCILED
    "UNRECONCILED" -> IngestMethod.UNRECONCILED
    "USER" -> IngestMethod.UNRECONCILED
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
 * - **Fill a category** on a server-origin row the phone holds uncategorised, when the server now
 *   states one. **Never overwrite a category the phone has**: a person may have set it here, and
 *   that choice cannot reach the server (the gate's trigger refuses every UPDATE on
 *   `ledger_transactions`), so a server-wins rule would undo their edit on every sync.
 * - **Delete** a server-origin row ([SYNCED_SOURCE_FILE]) whose `syncId` the server no longer
 *   lists - a rule-7 supersession removed it there. Only when [complete] is true, and not when the
 *   server listed nothing at all while the phone holds server rows: an empty answer is far more
 *   likely a wrong household or a fresh database than a server that deleted a whole ledger, and
 *   this path would otherwise wipe the phone's copy on the strength of it.
 *
 * Rows with any other [LedgerTransaction.sourceFile] are never touched.
 */
fun planLedgerMirror(
    local: List<LedgerTransaction>,
    remote: List<RemoteLedgerTransaction>,
    complete: Boolean,
): LedgerMirrorPlan {
    val localBySyncId = local.associateBy { it.syncId }
    val toInsert = mutableListOf<LedgerTransaction>()
    val fills = mutableListOf<LedgerMirrorPlan.CategoryFill>()
    val unrecognized = mutableListOf<String>()
    var alreadyPresent = 0

    for (r in remote) {
        val byServerId = localBySyncId[r.serverId]
        val byOriginGuid = r.originGuid?.let { localBySyncId[it] }
        if (byServerId != null || byOriginGuid != null) {
            alreadyPresent++
            val serverCategory = r.category
            if (byServerId != null && byServerId.sourceFile == SYNCED_SOURCE_FILE &&
                byServerId.category == null && serverCategory != null
            ) {
                fills += LedgerMirrorPlan.CategoryFill(byServerId.id, serverCategory, localCategoryPending(r))
            }
            continue
        }
        val method = ledgerIngestMethodFor(r.provenance)
        if (method == null) {
            unrecognized += "${r.description} (${r.serverId}): unrecognised provenance '${r.provenance}' - not inserted"
            continue
        }
        toInsert += r.toLocalRow(method)
    }

    val localSynced = local.filter { it.sourceFile == SYNCED_SOURCE_FILE }
    val emptyAgainstHeld = remote.isEmpty() && localSynced.isNotEmpty()
    val deletable = complete && !emptyAgainstHeld
    val listed = remote.mapTo(HashSet()) { it.serverId }
    val toDelete = if (deletable) localSynced.map { it.syncId }.filterNot { it in listed } else emptyList()

    return LedgerMirrorPlan(
        toInsert = toInsert,
        categoryFills = fills,
        toDeleteSyncIds = toDelete,
        deletionsSkipped = !deletable,
        alreadyPresent = alreadyPresent,
        unrecognizedProvenance = unrecognized,
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

    fun recordSuccess(context: Context, changedRoom: Boolean, atMs: Long = System.currentTimeMillis()) {
        val wasFailing = line(context) != null
        prefs(context).edit().putLong(KEY_OK, atMs).apply()
        if (changedRoom || wasFailing) generation.update { it + 1 }
    }

    fun recordFailure(context: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_FAILED, atMs).apply()
        generation.update { it + 1 }
    }

    /** [ledgerMirrorStatusLine] over the stored instants. [short] for the HOME tile. */
    fun line(context: Context, short: Boolean = false): String? {
        val p = prefs(context)
        return ledgerMirrorStatusLine(p.getLong(KEY_OK, 0L), p.getLong(KEY_FAILED, 0L), short)
    }
}

/**
 * The sentence, or null when the latest attempt succeeded (or none was ever made - an install not
 * on the engine has nothing to confess). Unreadable and empty are different sentences (CLAUDE.md
 * section 1): with no successful read ever, the phone may hold nothing from the server at all, and
 * "$0 spent" must not read as a fact about the month.
 *
 * [short] is the HOME tile's form. That disclosure is never truncated (a trust line is not
 * furniture), so on a half-width tile the full sentence would wrap to four lines; the short one
 * still says both halves in words - not synced, and whose copy this is.
 */
fun ledgerMirrorStatusLine(lastSuccessAtMs: Long, lastFailureAtMs: Long, short: Boolean = false): String? = when {
    lastFailureAtMs <= lastSuccessAtMs -> null
    short && lastSuccessAtMs == 0L -> "Not synced - this phone only"
    short -> "Not synced - this phone's last copy"
    lastSuccessAtMs == 0L -> "Not synced: the server couldn't be read, so this shows only what is on this phone"
    else -> "Not synced: the server couldn't be read just now, so this shows what this phone last had"
}
