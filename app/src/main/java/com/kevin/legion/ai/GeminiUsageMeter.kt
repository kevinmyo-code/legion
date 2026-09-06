package com.kevin.legion.ai

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.BackgroundPassState
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.GeminiUsage
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one place a measured Gemini token count is written down, and the one place the Setup screen
 * reads it back from.
 *
 * **Why a process-wide object with an injected context**, rather than a parameter threaded through
 * every call site: [SubAgent] is constructed with a system instruction and a model and nothing
 * else, and it has roughly thirty callers across [com.kevin.legion.service.LiveToolbox],
 * [com.kevin.legion.vehicle.DiagnosticAgent] and the rest. Giving it a `Context` would mean
 * touching every one of them. The same [GeminiKeyProvider] shape - seeded once from
 * [com.kevin.legion.MidnightApplication.onCreate], read statically thereafter - costs one line at
 * startup and nothing anywhere else. Before [init] runs, every record call is a silent no-op:
 * losing the first few milliseconds of metering is not worth a crash in a hot path.
 *
 * **Metering happens at the HTTP boundary inside [SubAgent], not at the call sites.** The brief
 * offered a choice - route the other REST sites through [SubAgent.askWithUsage], or record where
 * they call - and this is a third option that is less invasive than either: `ask`, `askTyped`,
 * `askWithUsage` and `investigate` all funnel through one `postOnce`, so recording there covers
 * every present and future caller with zero changes to any of them, and cannot be forgotten by
 * the next one added. [SubAgent.askWithUsage] keeps working exactly as before for
 * [com.kevin.legion.ledger.CategoryAgent], which needs the counts returned rather than merely
 * recorded.
 *
 * **Every write is fire-and-forget on [scope] and swallows its own failures.** Observability must
 * never break the thing it observes - the same rule [com.kevin.legion.MidnightEvents]'s own `safe`
 * wrapper states, and doubly so here, where the write is a Room round trip sitting on the
 * conversation path.
 */
object GeminiUsageMeter {
    private const val TAG = "GeminiUsageMeter"

    /** How long a usage row is kept. Long enough to answer "this month", short enough that the
     *  table stays small on a phone that talks a lot. */
    private const val RETENTION_DAYS = 62L
    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    @Volatile private var appContext: Context? = null

    /**
     * Process-lifetime scope; nothing cancels it because nothing should. Mirrors
     * [com.kevin.legion.MidnightApplication]'s own reasoning for its app scope.
     *
     * **The [CoroutineExceptionHandler] is the thing that keeps metering from breaking what it
     * measures**, and it is why there is no `try`/`catch` around each write. An uncaught throw
     * inside a `launch` on a [SupervisorJob] is not contained by the supervisor - it reaches the
     * thread's default handler and takes the process with it - so a handler here is the actual
     * backstop, not a tidier spelling of one. Same rule [com.kevin.legion.MidnightEvents]'s own
     * `safe` wrapper states: observability must never crash the app it is observing.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.w(TAG, "usage metering failed (ignored): ${t.message}") },
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Test seam. Real code never calls this; [com.kevin.legion.ai.GeminiUsageMeterTest] does, so a
     * test can point the meter at an in-memory database and then put it back.
     */
    internal fun resetForTest(context: Context?) {
        appContext = context?.applicationContext
    }

    // --- Writes ---------------------------------------------------------------

    /**
     * One REST `generateContent` round trip. Every call gets its own row, keyed by a fresh UUID,
     * because each REST response reports its own independent counts and they SUM.
     *
     * Recorded even when all three counts are null: a call that happened and reported nothing is a
     * different fact from a call that never happened, and [unreportedCountSince] is what lets the
     * Setup sentence say how much of its own total is missing rather than quietly under-reporting.
     */
    fun recordRestCall(model: String, promptTokens: Int?, responseTokens: Int?, totalTokens: Int?) {
        val ctx = appContext ?: return
        scope.launch { recordRestCallNow(ctx, model, promptTokens, responseTokens, totalTokens) }
    }

    /**
     * [recordRestCall]'s body, as a suspend function.
     *
     * Split out so a test can AWAIT the write instead of racing [scope]'s fire-and-forget launch.
     * A test that polls for a row that a background coroutine may or may not have written yet is
     * a test that passes for the wrong reason on a fast machine, which is exactly the shape
     * `.scratch/hardening/issues/13-the-suite-is-green-by-luck.md` was written about. The public
     * entry point above stays fire-and-forget, because its callers are on the conversation path.
     */
    internal suspend fun recordRestCallNow(
        ctx: Context,
        model: String,
        promptTokens: Int?,
        responseTokens: Int?,
        totalTokens: Int?,
    ) {
        val now = System.currentTimeMillis()
        CarDatabase.getDatabase(ctx).geminiUsageDao().insertIfAbsent(
            sessionKey = java.util.UUID.randomUUID().toString(),
            surface = GeminiUsage.SURFACE_REST,
            model = model,
            promptTokens = promptTokens,
            responseTokens = responseTokens,
            totalTokens = totalTokens,
            reports = 1,
            at = now,
        )
    }

    /**
     * One `usageMetadata` message off a Live socket, folded into that socket's single row.
     *
     * [sessionKey] is `GeminiLiveSession.episodicSessionId` - the same id the episodic transcript
     * is grouped by, so a spend row lines up with what was actually said. Blank is ignored rather
     * than written under an empty key, which would merge every unattributable report into one row.
     *
     * Insert-then-fold rather than a Room `@Upsert`: the fold has to keep the GREATEST value seen
     * per column and increment a counter, which is arithmetic on the row's own current contents,
     * and an upsert replaces rather than accumulates. See [GeminiUsage] for why max and not sum.
     */
    fun recordLiveUsage(
        sessionKey: String,
        model: String,
        promptTokens: Int?,
        responseTokens: Int?,
        totalTokens: Int?,
    ) {
        if (sessionKey.isBlank()) return
        val ctx = appContext ?: return
        scope.launch { recordLiveUsageNow(ctx, sessionKey, model, promptTokens, responseTokens, totalTokens) }
    }

    /** [recordLiveUsage]'s body, as a suspend function - see [recordRestCallNow] for why. */
    internal suspend fun recordLiveUsageNow(
        ctx: Context,
        sessionKey: String,
        model: String,
        promptTokens: Int?,
        responseTokens: Int?,
        totalTokens: Int?,
    ) {
        val now = System.currentTimeMillis()
        val dao = CarDatabase.getDatabase(ctx).geminiUsageDao()
        // Always-insert-then-always-fold, and the insert seeds `reports = 0` and NULL
                // counts on purpose. It makes the pair exact under concurrency, which a
        // read-then-branch does not: the five-minute timer aside, these reports arrive on
        // the socket reader and each is metered on its own coroutine, so two can be in
        // flight at once. INSERT OR IGNORE is idempotent against the unique index on
        // sessionKey (at most one row is ever created, whoever wins), and foldReport is
        // then applied exactly once per report by every racer alike - so `reports` counts
        // reports and not insert-attempts, and the MAX arithmetic is order-independent.
        // Seeding the counts NULL rather than with this report's values is what stops the
        // first report being counted twice; the fold immediately below supplies them.
        dao.insertIfAbsent(
            sessionKey = sessionKey,
            surface = GeminiUsage.SURFACE_LIVE,
            model = model,
            promptTokens = null,
            responseTokens = null,
            totalTokens = null,
            reports = 0,
            at = now,
        )
        dao.foldReport(sessionKey, promptTokens, responseTokens, totalTokens, now)
        dao.trimOlderThan(now - RETENTION_DAYS * MS_PER_DAY)
    }

    /** A Live socket reached `onOpen`. Counted for today in the user's own timezone. */
    fun recordLiveConnect() {
        val ctx = appContext ?: return
        scope.launch { recordConnectNow(ctx, carriedTurn = false) }
    }

    /**
     * This socket carried a real user turn. The caller is responsible for calling this at most
     * once per socket - [com.kevin.legion.service.GeminiLiveSession] holds that flag, because the
     * socket object is the thing that knows which turns belong to it.
     *
     * Deliberately counted against TODAY rather than against the day the socket opened. A socket
     * that opened at 23:58 and was spoken into at 00:01 would otherwise increment
     * `connectsWithTurn` on a day whose `connects` never counted it, and
     * [com.kevin.legion.data.local.LiveConnectDay.connectsWithoutTurn] would go negative on one
     * day and overstate on the other. Both halves landing on the same row matters more than either
     * landing on the strictly correct one, and the `coerceAtLeast(0)` on that derived value is the
     * belt to this braces.
     */
    fun recordLiveConnectCarriedTurn() {
        val ctx = appContext ?: return
        scope.launch { recordConnectNow(ctx, carriedTurn = true) }
    }

    /**
     * Both connect counters' body, as a suspend function - see [recordRestCallNow] for why the
     * split exists. One function rather than two because the pair differ by a single column, and
     * the day they land on has to be computed identically or
     * [com.kevin.legion.data.local.LiveConnectDay.connectsWithoutTurn] can go negative.
     */
    internal suspend fun recordConnectNow(ctx: Context, carriedTurn: Boolean) {
        val now = System.currentTimeMillis()
        val dao = CarDatabase.getDatabase(ctx).liveConnectDayDao()
        val day = LocalDate.now(ZoneId.systemDefault()).toString()
        dao.ensureDay(day, now)
        if (carriedTurn) dao.bumpConnectWithTurn(day, now) else dao.bumpConnect(day, now)
    }

    // --- Reads ----------------------------------------------------------------

    /**
     * What the Setup screen needs to say a sentence about spend, or null if the tables could not
     * be read at all.
     *
     * Null is not the same as a [Spend] full of zeros and the caller must not collapse them: an
     * unreadable database and a quiet day are different sentences (CLAUDE.md section 1).
     */
    suspend fun spend(context: Context): Spend? = try {
        val db = CarDatabase.getDatabase(context)
        val usage = db.geminiUsageDao()
        val connects = db.liveConnectDayDao()
        val zone = ZoneId.systemDefault()
        val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfMonth = LocalDate.now(zone).withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val monthDay = LocalDate.now(zone).withDayOfMonth(1).toString()
        Spend(
            tokensToday = usage.totalTokensSince(startOfToday),
            callsToday = usage.rowCountSince(startOfToday),
            unreportedToday = usage.unreportedCountSince(startOfToday),
            tokensThisMonth = usage.totalTokensSince(startOfMonth),
            callsThisMonth = usage.rowCountSince(startOfMonth),
            unreportedThisMonth = usage.unreportedCountSince(startOfMonth),
            connectsThisMonth = connects.connectsSince(monthDay) ?: 0,
            connectsWithTurnThisMonth = connects.connectsWithTurnSince(monthDay) ?: 0,
            setAside = db.backgroundPassStateDao().setAsideRows(),
        )
    } catch (e: android.database.SQLException) {
        // Narrow on purpose. The only thing this function does is read Room, so a SQL failure (an
        // unreadable file, a corrupt page) and a closed database are the failures that can
        // realistically arrive - and both mean "could not read", which the caller renders in words.
        // Anything else is a bug in this file and should surface rather than be swallowed into a
        // null the screen reports as an unreadable table.
        Log.w(TAG, "could not read spend: ${e.message}")
        null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "could not read spend: ${e.message}")
        null
    }

    /**
     * The measured spend, as read. Every field is what the DATABASE says, never a derived guess.
     *
     * [tokensToday]/[tokensThisMonth] are nullable because `SUM` over rows that all reported null
     * is null, and that means "the API never told us", not "zero tokens".
     * [unreportedToday]/[unreportedThisMonth] count the calls in the window that reported no total
     * at all, which is what makes a non-null sum honest about being a floor.
     */
    data class Spend(
        val tokensToday: Long?,
        val callsToday: Int,
        val unreportedToday: Int,
        val tokensThisMonth: Long?,
        val callsThisMonth: Int,
        val unreportedThisMonth: Int,
        val connectsThisMonth: Int,
        val connectsWithTurnThisMonth: Int,
        val setAside: List<BackgroundPassState>,
    ) {
        val connectsWithoutTurnThisMonth: Int
            get() = (connectsThisMonth - connectsWithTurnThisMonth).coerceAtLeast(0)
    }
}
