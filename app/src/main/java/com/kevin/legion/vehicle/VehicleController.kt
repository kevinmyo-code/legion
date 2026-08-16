package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.util.relativeAge
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * The "maintenance brain". Each physical car is identified by the Bluetooth
 * MAC of its OBD-II adapter (each car has its own dongle), so plugging in a
 * different car automatically switches persona, odometer, and service
 * schedule. Handles three voice flows:
 *
 *  - registering a new car ("this car is a 2003 BMW 330i") - which triggers a
 *    one-time online lookup of typical maintenance intervals via [AriaBrain].
 *  - odometer updates ("my odometer is at 142,500") - the driver is the
 *    source of truth; between updates, mileage is estimated from GPS-or-OBD
 *    per-tick distance in [com.kevin.legion.vehicle.TelemetryRecorder.run].
 *  - logging completed work ("I just changed the oil") - recorded as a
 *    [ServiceRecord] and clears the item's "due" status.
 *
 * Plugs into the same local-command chain as [com.kevin.legion.location.PlaceController]
 * etc - runs before the cloud brain, returns null for anything that isn't one
 * of these.
 */
object VehicleController {
    private const val TAG = "VehicleController"

    // Used when no OBD adapter is connected (testing, or before pairing).
    const val DEFAULT_VEHICLE_ID = "default"

    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Outcome of a voice/tool write that can now fail structurally rather than by string-matching
     * the reply (ticket 05, `.scratch/fleet-maintenance/issues/05-an-edit-that-actually-sticks.md`
     * - "the no-op guard is law now"). [success] is derived from the underlying targeted write's
     * row count, never asserted; [message] is exactly what the caller speaks either way. Closes the
     * gap ticket 05 itself named: `LiveToolbox` used to hardcode `success = true` above every one
     * of these calls, so the JSON envelope handed to Gemini said the call succeeded even when
     * [message] was a refusal - a false success one layer up from the database write it wraps.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

    /**
     * Default assistant personality (ported from Midnight AI's Zero persona, which was
     * already character-only and stated no identity claim, so it carries over as-is).
     * Single global assistant identity now - no per-car companion, no Zero-vs-car-self
     * split (that entire mechanism, `CompanionIdentity`, was retired in the pivot).
     *
     * Character: Motoko Kusanagi's composure + Judy Alvarez's directness + Panam
     * Palmer's bluntness. The "never coy, never possessive, never hot-and-cold" line is
     * load-bearing, not flavour: without it the model drifts straight into the yandere
     * and tsundere companion tropes. Keep it.
     *
     * Whatever seeds onboarding defaults (ui/ is a clean-slate rebuild, nothing wired
     * yet) must seed this VERBATIM, not paraphrase it - Midnight AI shipped two
     * contradicting copies of this text for weeks before anyone noticed.
     */
    val DEFAULT_PERSONA = "Composed and competent: you have seen a lot and you don't need to prove it. " +
        "Direct - you say what you mean, plainly, no hedging and no games. Blunt when it matters, " +
        "warm underneath, never soft about it. You get invested in this car and this driver. " +
        "Never coy, never possessive, never sulking or running hot-and-cold for attention: if " +
        "something bothers you, you say it once and move on. Economical - you notice things and " +
        "mention them, you don't narrate. You like night drives and cars with histories."

    // Canonical service name -> phrases that indicate it. Matched by LONGEST
    // keyword, never by list order - see canonicalizeServiceName for why that
    // distinction is load-bearing rather than cosmetic.
    //
    // "Brake Fluid" is its own entry deliberately: the severe-service lookup
    // prompt asks for it by name, so a row titled exactly that is an ordinary
    // seed result, and without an entry the bare "brake" keyword swallowed it.
    //
    // Expanded from ten to seventeen entries (ticket 14 review, BLOCKING 1b,
    // `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`,
    // 2026-08-15): ticket 02's sourced 1998 XJ research
    // (`.scratch/fleet-maintenance/research/1998-xj-schedule.md` §5.1) counted 26 distinct factory
    // service strings against the original ten keywords - "differential fluid", "transfer case
    // fluid", "serpentine belt", "ignition cables", and "steering/ball-joint lubrication" all had
    // NO canonical entry at all, so two differently-worded LLM responses for any of those concepts
    // became two rows, the exact `Axle Fluid` / `Axle Lubricant` / `Axle Lubricant Service` shape
    // ticket 01 measured on Kevin's real phone. "Manual Transmission Fluid" is a NEW entry
    // alongside the pre-existing "Transmission Fluid" rather than a rename of it - the research
    // (§5.3) flagged automatic/manual/transfer-case as three separate factory items sharing one
    // ambiguous canonical name; splitting out the manual case is additive (longest-match already
    // makes "manual transmission fluid" beat the shorter "transmission fluid"/"transmission"
    // keywords, so nothing already stored as "Transmission Fluid" changes behaviour) rather than
    // reopening the automatic-transmission naming, which is out of this ticket's scope. "PCV Valve"
    // is included even though ticket 02 found the XJ has no PCV item (CCV system, not serviceable) -
    // this table is shared across every vehicle on the fleet, not just the XJ, and PCV is a real
    // canonical concept on plenty of other cars.
    private val SERVICE_KEYWORDS = listOf(
        "Oil Change" to listOf("oil"),
        "Tire Rotation" to listOf("tire rotation", "tires rotated", "rotated the tires"),
        "Brake Pads" to listOf("brake pad", "brake lining", "brakes", "brake"),
        "Brake Fluid" to listOf("brake fluid"),
        "Air Filter" to listOf("air filter", "air cleaner element"),
        "Cabin Air Filter" to listOf("cabin filter", "cabin air filter"),
        "Spark Plugs" to listOf("spark plug"),
        "Ignition Cables" to listOf("ignition cable", "spark plug wire", "plug wire"),
        "Coolant Flush" to listOf("coolant", "antifreeze"),
        "Transmission Fluid" to listOf("transmission fluid", "transmission"),
        "Manual Transmission Fluid" to listOf("manual transmission fluid", "manual transmission"),
        "Transfer Case Fluid" to listOf("transfer case fluid", "transfer case"),
        "Differential Fluid" to listOf(
            "differential fluid", "differential service", "front and rear axles", "axle fluid",
            "axle lubricant", "gear oil", "rear axle fluid", "front axle fluid",
        ),
        "Serpentine Belt" to listOf("serpentine belt", "drive belt", "accessory belt"),
        "Chassis Lubrication" to listOf(
            "lubricate steering", "steering linkage", "ball joint", "chassis lubrication", "grease fitting",
        ),
        "PCV Valve" to listOf("pcv valve", "pcv"),
        "Battery" to listOf("battery"),
    )

    // Common maintenance verbs/articles that carry no identity - stripped before the near-miss
    // token-overlap comparison below, so "Drain and refill the front axle" and "Front axle fluid
    // service" compare on their NOUNS ("front", "axle") rather than diluting the overlap ratio with
    // words every factory sentence shares regardless of what job it names.
    //
    // "fluid" is deliberately in here too, found by the unit test it broke: "Transfer Case Fluid"
    // vs the real-shape dataset's existing "Transmission Fluid" share only the word "fluid" (1 of
    // 2 significant words on the shorter side), which lands EXACTLY on the 0.5 threshold and would
    // wrongly near-miss-match two genuinely different services. "Fluid" is a generic solvent-word
    // that shows up across transmission/differential/transfer-case/brake/coolant concepts alike, so
    // like "service"/"change" it carries no identity of its own and is stripped the same way.
    private val NEAR_MISS_STOPWORDS = setOf(
        "a", "an", "and", "as", "at", "of", "or", "the", "to",
        "adjust", "change", "check", "drain", "fluid", "flush", "inspect", "necessary", "refill",
        "replace", "replacement", "service", "tension",
    )

    /**
     * Registers the car's year/make/model/trim/engine. **No longer triggers a maintenance-interval
     * lookup** (ticket 14, `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`):
     * registering a car used to silently call [applyServiceIntervals] - the mechanism that put 54
     * rows and 49 empty anchors across Kevin's roster without him ever asking for one. A car now
     * starts with an EMPTY schedule and says so; populating it is a deliberate, driver-triggered
     * diff-and-confirm (see `vehicle/PopulateSchedule.kt`), never a side effect of registration.
     *
     * **Ticket 13 rewrite (2026-08-15).** This used to build a brand new [Vehicle]
     * from scratch on every call - even when a row already existed - which
     * silently dropped `voiceName`, `personaTraits`, `trim`, `archived` and
     * `lastOdometerPromptAt` back to their defaults, unticketed data loss noticed
     * only while diagnosing the same ticket's Jeep-row bug. Now: a brand new id
     * (no row on file yet) still gets a real, fully-specified [Vehicle] inserted;
     * an EXISTING row is corrected through [VehicleDao.setIdentity], which
     * touches only the identity columns and leaves everything else - including
     * the odometer and persona fields this function used to carry forward by
     * hand - untouched by construction rather than by remembering to list them.
     *
     * [trim]/[engine] default to blank so every pre-existing caller (voice `register_vehicle`,
     * which has no engine slot) keeps compiling unchanged; ticket 14's manual-input form is the
     * first caller to actually supply them. A blank [trim]/[engine] on an EXISTING row leaves the
     * stored value alone (same "blank means don't touch" convention [correctVehicle] uses) rather
     * than clobbering it back to empty.
     */
    suspend fun registerDirect(context: Context, year: Int, make: String, model: String, trim: String = "", engine: String = ""): String {
        if (year < 1900 || make.isBlank() || model.isBlank())
            return "I need a valid year, make, and model to register the car."
        val vehicleId = ActiveVehicle.current(context)
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val existing = dao.getByMac(vehicleId)
        val now = System.currentTimeMillis()
        // Ticket 04's label rule deleted the "this car" sentinel at the source (seedVehicle no
        // longer writes it, and the archived rows that had it are cleared on process start), so
        // the `&& it != "this car"` half of this check is dead weight now, same as AriaBrain's -
        // removed rather than left to rot.
        val name = existing?.name?.takeIf { it.isNotBlank() } ?: model
        if (existing == null) {
            // Genuinely new row - nothing to preserve, a real INSERT.
            dao.upsert(
                Vehicle(
                    obdMac = vehicleId,
                    name = name,
                    make = make,
                    model = model,
                    year = year,
                    trim = trim,
                    engine = engine,
                    personaPrompt = "",
                    onboarded = false,
                    confirmed = true,
                )
            )
        } else {
            // Existing row: a targeted identity write, not a rebuild - see the
            // function doc and VehicleDao.setIdentity for why.
            dao.setIdentity(vehicleId, year, make, model, trim.ifBlank { existing.trim }, name, now)
            if (engine.isNotBlank()) dao.setEngine(vehicleId, engine, now)
        }

        return "Got it, this is the $year $make $model now. No maintenance schedule on file yet - " +
            "populate it from the factory recommendation whenever you're ready."
    }

    /**
     * Records the driver-reported odometer reading and resets the trip
     * accumulator. [vehicleId] is the fleet-wide-voice override (ticket 01,
     * "category B" stored-data tool) - null means the active car, unchanged.
     *
     * **The ONE write behind ticket 10's odometer entry**
     * (`.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`): the voice tool
     * (`set_odometer`) and [com.kevin.legion.ui.fleet.SetOdometerDialog] (FLEET's CARS pane, ticket
     * 09's/14's future forms meant to reuse it too) are both thin callers of exactly this function,
     * so the validation and drift-logging below apply identically no matter which one calls it.
     *
     * **Drift, computed and logged, never shown (ticket 10 §5, Kevin's ruling).** A manual reading
     * always wins and resets [Vehicle.tripMilesSinceBaseline] to zero - that's standing, not
     * reopened here - but the instant before it does is the ONLY moment anyone will ever be able to
     * measure whether [TelemetryRecorder]'s speed-integration estimate is actually running low on
     * THIS car, which ticket 03 found it does (~5-15%, one-directional). So the delta is computed
     * and logged (`Log.i`, never returned in [WriteOutcome.message], never a figure competing with
     * the reading itself) right here, off the pre-write [vehicle] this function already read.
     *
     * **Below-estimate readings are questioned, never refused (ticket 10 §7).** An odometer only
     * goes up, but the driver's own dash always wins even against a lower estimate - the estimator
     * running low is real, not evidence of a typo, so this still writes the reading; only the reply
     * differs, via [odometerQuestionNote].
     */
    suspend fun setOdometer(context: Context, miles: Int, vehicleId: String? = null): WriteOutcome {
        if (miles < 100 || miles > 999_999)
            return WriteOutcome(false, "That reading doesn't look right — odometer should be between 100 and 999,999 miles.")
        val vehicle = vehicleFor(context, vehicleId)
        val now = System.currentTimeMillis()

        // Read BEFORE the write below zeroes tripMilesSinceBaseline out from under it - see this
        // function's own doc for why this is the only moment either of these can be computed.
        val baselineSet = vehicle.odometerBaseline > 0
        val priorEstimate = currentMileage(vehicle)
        if (baselineSet) {
            val driftMiles = miles - priorEstimate
            Log.i(
                TAG,
                "odometer confirmed: vehicle=${vehicle.obdMac} driverReading=$miles priorEstimate=$priorEstimate " +
                    "driftMiles=$driftMiles (positive = estimator ran low, matching ticket 03's ~5-15% finding; " +
                    "never shown to the driver - ticket 10 §5)",
            )
        }

        // Targeted write (ticket 13): touches only the odometer baseline fields,
        // so a concurrent trip-mile tick or identity edit can't be clobbered by
        // this call the way a whole-row upsert of a possibly-stale `vehicle`
        // could be.
        //
        // The row count is CHECKED, not discarded. Since ticket 13 stopped
        // seedVehicle persisting placeholders, `vehicle` may be an in-memory
        // placeholder for a car that has no row - and a targeted UPDATE against
        // a missing row writes nothing while succeeding at the SQL level.
        // Answering "Got it, 142,500 on the clock" to a reading that went
        // nowhere is the same false-success ticket 13 was opened to remove (a
        // write that reported success and changed nothing); it would just have
        // moved one layer down. So an unregistered car is TOLD it is
        // unregistered, and given the next step, rather than silently swallowing
        // the number.
        val written = CarDatabase.getDatabase(context).vehicleDao()
            .setOdometerBaseline(vehicle.obdMac, miles, now, now)
        if (written == 0) {
            return WriteOutcome(
                false,
                "I don't have this car on file yet, so I can't attach that reading to it. " +
                    "Tell me the year, make and model first and I'll record $miles miles straight after.",
            )
        }

        val questionNote = odometerQuestionNote(miles, priorEstimate, baselineSet)
        return WriteOutcome(
            true,
            if (questionNote != null) {
                "$questionNote Filed: $miles on the clock."
            } else {
                listOf(
                    "Got it, $miles on the clock. I'll keep track from here.",
                    "Noted, $miles miles. Let's see how long till the next thing breaks.",
                    "$miles it is. Filed away.",
                ).random()
            },
        )
    }

    /**
     * Ticket 10 §7's validation rule, extracted pure so it is unit-testable without Room: a
     * below-[priorEstimate] reading is QUESTIONED IN WORDS, never blocked - the estimator running
     * low is a real, expected possibility (ticket 03), and the driver's dash always wins. Returns
     * `null` (nothing to question) when [baselineSet] is false - the FIRST-EVER reading on a car has
     * no prior real reading to have drifted from, so comparing it against [priorEstimate] (which
     * would just be leftover trip miles against a baseline of 0) would be a false alarm.
     */
    internal fun odometerQuestionNote(miles: Int, priorEstimate: Int, baselineSet: Boolean): String? =
        if (baselineSet && miles < priorEstimate) {
            "That's lower than my last estimate of $priorEstimate - the estimator can run low, so I'm taking your reading as the real one."
        } else {
            null
        }

    /**
     * Normalises a free-text service name (from Gemini, either a spoken log or a
     * looked-up interval) onto the app's canonical vocabulary so the same real
     * service always lands on the same [MaintenanceItem] row. Falls back to
     * word-by-word titlecasing the raw name when it matches none of
     * [SERVICE_KEYWORDS]' entries (seventeen as of ticket 14's review, up from ten -
     * see that list's own doc comment) - that fallback can still vary phrasing-to-phrasing
     * (accepted; [looksLikeExistingItem] is the guard against that variance mattering, ticket
     * 07/08, and [nearMissServiceName] is the weaker guard for phrasings that fall through the
     * keyword table entirely, ticket 14's review).
     */
    /**
     * Free-text service name -> one of [SERVICE_KEYWORDS]' canonical names, or a
     * titlecased passthrough when nothing matches.
     *
     * Matches on the LONGEST matching keyword, not the first in list order. The
     * old first-match-wins was quietly wrong in a way no LLM phrasing was needed
     * to trigger: keywords are substrings, and "cabin air filter" CONTAINS "air
     * filter", so "log the cabin air filter" stamped the engine air filter's row
     * and said so out loud. Same shape sent "brake fluid" to "Brake Pads" via the
     * bare "brake" keyword. Longest-match makes the specific phrase beat the
     * general one regardless of declaration order, so adding an entry can no
     * longer silently shadow an existing one.
     */
    internal fun canonicalizeServiceName(serviceName: String): String {
        val lower = serviceName.lowercase()
        return SERVICE_KEYWORDS
            .mapNotNull { (canonical, kws) ->
                kws.filter { it in lower }.maxByOrNull { it.length }?.let { canonical to it.length }
            }
            .maxByOrNull { it.second }
            ?.first
            ?: titlecaseWords(serviceName.trim())
    }

    /**
     * Word-by-word Title Case, not [canonicalizeServiceName]'s old fallback
     * (`replaceFirstChar { it.titlecase() }`, which only capitalised the FIRST
     * character of the WHOLE string). That bug is the duplicate engine tickets
     * 07/08 found: a hand-typed `"transfer case fluid"` stored as
     * `"Transfer case fluid"` while the LLM seed independently produced
     * `"Transfer Case Fluid"` - two different strings, and since `serviceName`
     * is half of [MaintenanceItem]'s composite primary key, two different rows
     * for the same real service. Pinned by
     * `VehicleControllerServiceNameTest.canonicalizeServiceName titlecases every word`.
     */
    private fun titlecaseWords(raw: String): String =
        raw.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.titlecase() }
        }

    /**
     * The demoted, comparator-only half of [canonicalizeServiceName] (ticket 07's ruling: hand-add
     * storage is verbatim, canonicalisation is for DETECTION only, never a rewrite). Canonicalises
     * both [typedName] and every name in [existingNames], compares case-insensitively, and returns
     * the COLLIDING EXISTING name verbatim (never a rewritten form of [typedName]) - or null if
     * nothing collides.
     *
     * Two callers, one function, deliberately: the hand-add duplicate warning ("this looks like Oil
     * Change - add anyway?", ticket 07) and matching a spoken service to the schedule item it should
     * reset (ticket 08's `logServiceDirect`/`logPastServiceDirect`) are the SAME comparison - "does
     * this name refer to a service already on the schedule" - so they share one implementation
     * rather than drifting into two.
     */
    internal fun looksLikeExistingItem(typedName: String, existingNames: List<String>): String? {
        val canonicalTyped = canonicalizeServiceName(typedName)
        return existingNames.firstOrNull { canonicalizeServiceName(it).equals(canonicalTyped, ignoreCase = true) }
    }

    /**
     * Bounded near-miss detector for names that [looksLikeExistingItem] cannot catch because they
     * fall OUTSIDE [SERVICE_KEYWORDS] entirely (ticket 14's review, BLOCKING 1b,
     * `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`). Two different
     * LLM phrasings of a concept with no keyword entry [canonicalizeServiceName] both onto the
     * word-by-word titlecase fallback - which titlecases whatever raw words it was given, so two
     * different sentences for the same real job produce two different strings. That is the exact
     * `Air Filter` / `Air Filter Replacement` / `Engine Air Filter` shape ticket 01 measured, just
     * for a concept the keyword table does not cover yet - and expanding the table (this same
     * ticket's other half) can only ever close today's gaps, never the next one an LLM invents a new
     * phrasing for.
     *
     * **Detection only, same rule as [looksLikeExistingItem] (ticket 07: "the canonicaliser is a
     * comparator, never a rewriter").** This function never resolves a near-miss in either
     * direction - the caller ([buildPopulateDiff][com.kevin.legion.vehicle.buildPopulateDiff])
     * surfaces it as its own question ("this looks like X already on file - same thing?"), and
     * nothing writes until the driver answers explicitly, same as every other populate-diff row.
     *
     * **Token-overlap, not edit distance.** Two factory phrasings of the same job share NOUNS
     * ("axle", "differential", "belt") far more reliably than they share character sequences - a
     * verb-heavy sentence ("Drain and refill the front axle") edit-distances poorly against a noun
     * phrase ("Front axle fluid service") even when both name the same job. [NEAR_MISS_STOPWORDS]
     * strips the maintenance verbs and articles that would otherwise dilute the overlap ratio with
     * words every factory sentence shares regardless of what it is naming, then compares what is
     * left as sets.
     *
     * **Threshold 0.5 of the SHORTER name's significant word count** (at least half of the smaller
     * side's remaining words must appear in the other) - conservative on purpose, so this only
     * fires when a real majority of the identifying content overlaps. Below 0.5, unrelated
     * single-word concepts ("Belt" vs "Battery" both losing their only word to no match, or a
     * two-word overlap-of-one like "Front Bumper" vs "Front Axle" sharing just "front") start
     * colliding; ticket 02's one real audit of factory phrasing variance is what this number is
     * checked against, pinned by `VehicleControllerServiceNameTest`.
     *
     * Returns the EXISTING name verbatim (same contract as [looksLikeExistingItem]) or null.
     */
    internal fun nearMissServiceName(candidate: String, existingNames: List<String>): String? {
        val candidateTokens = significantTokens(candidate)
        if (candidateTokens.isEmpty()) return null
        return existingNames.firstOrNull { existing ->
            val existingTokens = significantTokens(existing)
            if (existingTokens.isEmpty()) return@firstOrNull false
            val overlap = candidateTokens.intersect(existingTokens).size
            val smallerSide = minOf(candidateTokens.size, existingTokens.size)
            overlap.toDouble() / smallerSide >= 0.5
        }
    }

    /** [NEAR_MISS_STOPWORDS]-filtered lowercase word set for [nearMissServiceName]'s comparison. */
    private fun significantTokens(raw: String): Set<String> =
        raw.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in NEAR_MISS_STOPWORDS }
            .toSet()

    /**
     * Logs completed maintenance and clears the item's "due" status.
     * [vehicleId] is the fleet-wide-voice override (ticket 01) - null means
     * the active car, unchanged. [costCents] is optional (ticket 11 §2, cost
     * capture at log time) - `Long` cents, never a `Double` (CLAUDE.md §4 rule
     * 3); the caller (the log-a-service UI form or `log_service`'s voice tool,
     * both dollar-denominated at their own edge) converts to cents BEFORE
     * calling this, so this function never sees a fractional dollar amount.
     */
    suspend fun logServiceDirect(context: Context, serviceName: String, vehicleId: String? = null, costCents: Long? = null): WriteOutcome {
        val canonical = canonicalizeServiceName(serviceName)
        val db = CarDatabase.getDatabase(context)
        val vehicle = vehicleFor(context, vehicleId)
        val mileage = currentMileage(vehicle)
        val now = System.currentTimeMillis()

        // Ticket 08's matching rule: canonicalise the spoken name and every
        // existing item's name, and compare case-insensitively - never an
        // exact-name `get`, which missed hand-typed items stored verbatim
        // (ticket 07) and orphaned an anchor on any phrasing mismatch. Reuses
        // the same comparator ticket 07 built for the hand-add duplicate
        // warning, because "does this name refer to a service already on the
        // schedule" is one question with two callers, not two questions.
        val existingItems = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val matchedName = looksLikeExistingItem(serviceName, existingItems.map { it.serviceName })

        // The ServiceRecord is ALWAYS written - work done is a true fact
        // regardless of what the schedule knows (ticket 08 decision). Filed
        // under the matched item's own stored name when one exists, so a
        // hand-typed schedule name and its service history read as the same
        // service; otherwise under the canonical form of what was said.
        val targetName = matchedName ?: canonical
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = vehicle.obdMac, serviceName = targetName, mileage = mileage, date = now, costCents = costCents))

        if (matchedName != null) {
            // Targeted write (ticket 05): touches only the anchor columns - no
            // read-modify-write, so a concurrent interval edit can't be lost
            // between the read above and this write. neverDone is cleared here
            // (setAnchor's own contract), not just in the backfill path: marking
            // something never-done and then actually doing it is the normal
            // sequence, and isDue checks neverDone first unconditionally, so
            // leaving it set would read a just-completed service as permanently
            // overdue.
            val written = db.maintenanceItemDao().setAnchor(vehicle.obdMac, matchedName, mileage, now, now)
            // matchedName came from a read moments ago; only a concurrent
            // delete/rename between that read and this write can zero it, and
            // ticket 05's no-op law says that must be reported, not assumed.
            if (written == 0) {
                return WriteOutcome(
                    false,
                    "Logged the $matchedName at $mileage miles, but the schedule item disappeared before I could " +
                        "reset it - it may have just been deleted.",
                )
            }
            return WriteOutcome(
                true,
                listOf(
                    "Nice, logged the $matchedName at $mileage miles. I'll let you know when it's due again.",
                    "Got it — $matchedName done at $mileage. One less thing to worry about.",
                    "Logged: $matchedName at $mileage miles.",
                ).random(),
            )
        }

        // No existing item matched (ticket 08): create it and SAY SO, under the
        // canonical form of what was said - a bad canonicalisation is then
        // visible immediately, not discovered three days later on a database
        // pull the way Kevin's silently-created Brake Fluid/Brake Pads rows were.
        db.maintenanceItemDao().upsert(
            MaintenanceItem(vehicleId = vehicle.obdMac, serviceName = canonical, lastDoneMileage = mileage, lastDoneDate = now, neverDone = false)
        )
        return WriteOutcome(
            true,
            "Logged the $canonical at $mileage miles. Nothing on your schedule matched, so I've added $canonical " +
                "as an item - it has no interval yet, tell me one when you want it tracked.",
        )
    }

    /**
     * Backfills a maintenance item from memory rather than a precise moment
     * ("I changed the oil about 3,000 miles ago", "never rotated the tires").
     * Unlike [logServiceDirect] this writes ONLY the maintenance_items anchor,
     * never a [ServiceRecord] - a remembered approximation doesn't belong in the
     * precise service ledger the driver builds by logging real work as it happens.
     *
     * At least one of [mileage] / [milesAgo] / [date] / [neverDone] must be given;
     * with nothing concrete, nothing is written and the driver is asked again.
     *
     * [vehicleId] is the fleet-wide-voice override (ticket 01), last per the
     * ticket's convention - null means the active car, unchanged.
     */
    suspend fun logPastServiceDirect(
        context: Context,
        serviceName: String,
        mileage: Int? = null,
        milesAgo: Int? = null,
        date: Long? = null,
        neverDone: Boolean = false,
        vehicleId: String? = null,
    ): WriteOutcome {
        if (!neverDone && mileage == null && milesAgo == null && date == null) {
            return WriteOutcome(false, "I need something to go on — a mileage, how long ago, a date, or that it's never been done.")
        }
        val canonical = canonicalizeServiceName(serviceName)
        val db = CarDatabase.getDatabase(context)
        val vehicle = vehicleFor(context, vehicleId)

        // Same comparator-based matching as logServiceDirect (ticket 08) - see
        // its doc. A backfill against an item that doesn't exist yet creates one,
        // same as a fresh log.
        val existingItems = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val matchedName = looksLikeExistingItem(serviceName, existingItems.map { it.serviceName })
        val targetName = matchedName ?: canonical

        // Ticket 08's backfill-conflict rule: a remembered approximation may not
        // silently beat a precise ServiceRecord. Real damage on Kevin's device -
        // log_service wrote a record AND its anchor at 118,374; fourteen seconds
        // later a mileage-only backfill (no date argument at all) overwrote that
        // anchor to 118,483 and NULLED its date.
        //
        // [date] here is the backfill's OWN claimed date, not "now" - comparing
        // against wall-clock call time would never catch the real incident (the
        // conflicting record was logged BEFORE the backfill call, so it can
        // never be "at or after" a `now` that only gets later). When [date] is
        // null (mileage/milesAgo-only, exactly the incident's shape, and also
        // `neverDone`), the merge sets no date at all - there is then no way to
        // tell whether the backfill precedes or follows an existing record, so
        // ANY logged record for this item is treated as a conflict (queried
        // with `atOrAfterMs = 0`, i.e. since the epoch - every real record date
        // is positive). When [date] IS given, only a record at or after THAT
        // date conflicts: an older backfill (e.g. "also did this back in 2021")
        // is not contradicted by a record from today.
        if (db.serviceRecordDao().hasRecordAtOrAfter(vehicle.obdMac, targetName, date ?: 0L)) {
            // The refusal is branched and NAMES THE WAY OUT. Both halves were review findings
            // (2026-08-15): one message covering both cases was factually wrong for `neverDone`
            // (nothing is being moved backward - the anchor is being cleared), and a refusal with
            // no stated escape turns the conservative choice into a dead end the driver cannot
            // reason about. Refusing beats guessing, but only if the driver can see what to do
            // next; an unexplained refusal is its own kind of silence.
            return WriteOutcome(
                false,
                if (neverDone) {
                    "I've got a logged $targetName on file, so I can't mark it as never done - that record " +
                        "says otherwise. If the record is the wrong one, say so and we'll sort that out first."
                } else {
                    "I've already got a logged $targetName that's at least as recent as this, so I'm leaving " +
                        "the schedule alone rather than overwrite something precise with an approximation. " +
                        "If this one really was later, give me the date and I'll take it."
                },
            )
        }

        val base = existingItems.firstOrNull { it.serviceName == targetName }
            ?: MaintenanceItem(vehicleId = vehicle.obdMac, serviceName = targetName)
        val merged = mergeBackfillAnchors(base, mileage, milesAgo, date, neverDone, currentMileage(vehicle))

        if (matchedName != null) {
            // Targeted write (ticket 05): touches only the anchor columns.
            val written = if (neverDone) {
                db.maintenanceItemDao().setNeverDone(vehicle.obdMac, targetName, System.currentTimeMillis())
            } else {
                db.maintenanceItemDao().setAnchor(vehicle.obdMac, targetName, merged.lastDoneMileage, merged.lastDoneDate, System.currentTimeMillis())
            }
            // matchedName came from a read moments ago; a concurrent
            // delete/rename is the only way this zeroes, and ticket 05's no-op
            // law says that must be reported, not assumed.
            if (written == 0) {
                return WriteOutcome(
                    false,
                    "I found $targetName a moment ago but couldn't write to it just now - it may have just been removed.",
                )
            }
        } else {
            // Genuine insert (ticket 05: upsert survives for this case only).
            db.maintenanceItemDao().upsert(merged)
        }

        return if (neverDone) {
            WriteOutcome(true, "Got it, marking $targetName as never done — I'll flag it as overdue.")
        } else {
            WriteOutcome(
                true,
                listOf(
                    "Noted — $targetName, backfilled from what you remember.",
                    "Got it, filed $targetName into the record.",
                    "Logged $targetName from memory.",
                ).random(),
            )
        }
    }

    /**
     * Voice-driven interval edit (ticket 05, decision 2 - "voice and screen both, and voice reads
     * the value back"). Matches [serviceName] against the existing schedule via the same
     * canonicalised, case-insensitive comparator [logServiceDirect] uses (ticket 08's mechanism),
     * writes a targeted [MaintenanceItemDao.setIntervals] tagged `CONFIRMED`, and RE-READS the row
     * before replying - Kevin's original voice attempt reported success and changed nothing, and a
     * read-back cannot be produced from a write that did not land, which is the entire fix.
     *
     * `CONFIRMED` (never `SEEDED`) because a spoken edit through this exact tool IS the explicit
     * confirmation ticket 05 decided a driver-owned interval requires before anything may touch it
     * again - the factory populate (ticket 14) skips a `CONFIRMED` row rather than silently
     * overwriting it.
     *
     * At least one of [intervalMiles] / [intervalMonths] must be given.
     */
    suspend fun setMaintenanceInterval(
        context: Context,
        serviceName: String,
        intervalMiles: Int? = null,
        intervalMonths: Int? = null,
        vehicleId: String? = null,
    ): WriteOutcome {
        if (intervalMiles == null && intervalMonths == null) {
            return WriteOutcome(false, "I need a mileage interval or a time interval to set - which one?")
        }
        val canonical = canonicalizeServiceName(serviceName)
        val db = CarDatabase.getDatabase(context)
        val vehicle = vehicleFor(context, vehicleId)
        val now = System.currentTimeMillis()

        val existingItems = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val matchedName = looksLikeExistingItem(serviceName, existingItems.map { it.serviceName })
        val targetName = matchedName ?: canonical

        if (matchedName != null) {
            // MERGE, never blanket-write. [MaintenanceItemDao.setIntervals] is an unconditional
            // `SET intervalMiles = :miles, intervalMonths = :months`, so passing a null through for
            // an axis the driver never mentioned writes SQL NULL and DESTROYS it.
            //
            // Found on review, 2026-08-15, before this reached the device, and it was not
            // theoretical: 34 of the 54 rows on Kevin's phone carry both axes, including every
            // intervalled item on the Jeep - `Oil Change 3,000 mi / 3 mo` among them. "Change the
            // oil interval to 7,500" would have silently nulled the 3-month clock.
            //
            // Worse, the read-back below could not have caught it. It re-reads the row and reports
            // what is actually stored, so it would have confirmed the damaged row as correct -
            // the safety mechanism reporting success over the failure it exists to catch. An
            // unowned column is not made safe by a targeted query if the CALLER hands that query a
            // null for a field it was never asked to touch. `AdvisorProposalExecutor` already
            // merges this way; this path did not.
            val existing = existingItems.firstOrNull { it.serviceName == targetName }
            val finalMiles = intervalMiles ?: existing?.intervalMiles
            val finalMonths = intervalMonths ?: existing?.intervalMonths
            val written = db.maintenanceItemDao().setIntervals(vehicle.obdMac, targetName, finalMiles, finalMonths, "CONFIRMED", now)
            if (written == 0) {
                return WriteOutcome(
                    false,
                    "I found $targetName a moment ago but couldn't write to it just now - it may have just been removed.",
                )
            }
        } else {
            db.maintenanceItemDao().upsert(
                MaintenanceItem(
                    vehicleId = vehicle.obdMac, serviceName = targetName,
                    intervalMiles = intervalMiles, intervalMonths = intervalMonths, intervalSource = "CONFIRMED",
                )
            )
        }

        // The read-back itself (ticket 05 decision 2): re-read rather than
        // trust the values just sent, so a write this function's own logic
        // somehow missed can never still be spoken as fact.
        val after = db.maintenanceItemDao().get(vehicle.obdMac, targetName)
            ?: return WriteOutcome(false, "I set that, but couldn't read it back to confirm - check the schedule when you get a chance.")

        val everyPhrase = listOfNotNull(
            after.intervalMiles?.let { "$it miles" },
            after.intervalMonths?.let { "$it months" },
        ).joinToString(" / ")
        val lastPhrase = when {
            after.neverDone -> "never done yet"
            after.lastDoneMileage != null -> "last done at ${after.lastDoneMileage}"
            else -> "no history logged yet"
        }
        return WriteOutcome(true, "$targetName is now every $everyPhrase, $lastPhrase.")
    }

    /**
     * Edits an EXISTING [ServiceRecord]'s mileage/cost (ticket 11 §2 - "Kevin's two existing
     * records can get costs added retroactively, and a mistyped mileage is fixable"). A targeted
     * write via [ServiceRecordDao.editMileageAndCost], mirroring [setMaintenanceInterval]'s own
     * "write, then re-read rather than trust the caller's own input" discipline - the returned
     * message states what is ACTUALLY on the row afterward, never an echo of what was asked for.
     *
     * [mileageMiles] and [costCents] are both written unconditionally (the edit form shows both
     * fields pre-filled with the record's current values, so "unchanged" and "explicitly cleared"
     * are indistinguishable at this layer by design - same shape [MaintenanceItemDao.setIntervals]'s
     * form-driven callers already use). `Long` cents throughout (CLAUDE.md §4 rule 3) - the caller
     * converts a driver-typed dollar string to cents before this is ever invoked.
     */
    suspend fun editServiceRecordDirect(context: Context, id: Long, mileageMiles: Int, costCents: Long?): WriteOutcome {
        val db = CarDatabase.getDatabase(context)
        val written = db.serviceRecordDao().editMileageAndCost(id, mileageMiles, costCents)
        if (written == 0) {
            return WriteOutcome(false, "Couldn't save that - the record may have just been deleted.")
        }
        val after = db.serviceRecordDao().getById(id)
            ?: return WriteOutcome(false, "Saved, but couldn't read it back to confirm - check the history when you get a chance.")
        val costPhrase = after.costCents?.let { " at $${formatCents(it)}" }.orEmpty()
        return WriteOutcome(true, "Updated ${after.serviceName}: ${after.mileage} miles$costPhrase.")
    }

    /**
     * Soft-deletes a [ServiceRecord] (ticket 11 §2). **LOCAL ONLY** - see [ServiceRecord.deleted]'s
     * own doc comment for why `service_records`' `Mode.UNION` sync makes a cross-device tombstone
     * structurally impossible here, unlike [writeDeleteItem][com.kevin.legion.ui.fleet.writeDeleteItem]'s
     * `maintenance_items` delete. Every caller-facing surface must say "on this phone" in words - see
     * the message below and the UI's own wording where this is offered.
     */
    suspend fun deleteServiceRecordDirect(context: Context, id: Long): WriteOutcome {
        val written = CarDatabase.getDatabase(context).serviceRecordDao().softDelete(id)
        return if (written == 0) {
            WriteOutcome(false, "Couldn't delete that - it may have already been removed.")
        } else {
            WriteOutcome(true, "Deleted from this phone. This delete doesn't sync - it won't remove the record from your other phone.")
        }
    }

    /**
     * The active car - the driver's picked profile, else the connected adapter's,
     * else the default placeholder - creating it if needed. See [ActiveVehicle].
     *
     * **Signature is frozen** (fleet-wide voice, ticket 01): 35 call sites depend
     * on this exact `(context) -> Vehicle` shape. [vehicleFor] is the sibling
     * that adds an explicit override without touching any of them.
     */
    suspend fun currentVehicle(context: Context): Vehicle = vehicleFor(context, null)

    /**
     * [currentVehicle] with an explicit override (fleet-wide voice, ticket 01,
     * "category B" stored-data tools): [vehicleId] null falls back to today's
     * [ActiveVehicle.current] behaviour exactly, so every existing caller of
     * [currentVehicle] is unaffected. A non-null [vehicleId] is expected to
     * already be a real, resolved id (normally from [VehicleResolver], which
     * validated it against the actual roster before ever getting here) but
     * this still seeds a placeholder on a miss rather than throwing, for the
     * same defensive reason [currentVehicle] itself does - a caller handed a
     * stale or hand-typed id should never crash the tool dispatch.
     */
    suspend fun vehicleFor(context: Context, vehicleId: String?): Vehicle {
        val id = vehicleId ?: ActiveVehicle.current(context)
        return CarDatabase.getDatabase(context).vehicleDao().getByMac(id) ?: seedVehicle(id)
    }

    /**
     * Saves the car's facts from the AI Profile form: make / model / year, and the
     * current odometer. A new odometer reading becomes a fresh baseline (trip
     * accumulator reset). Changing make/model marks the vehicle un-onboarded so the
     * next service-start re-looks-up its maintenance intervals.
     */
    // DEAD CODE, AND A LOADED GUN. No callers anywhere in app/src (confirmed by
    // ticket 13's audit, 2026-08-15, and again on review). It is the LAST
    // remaining whole-row `upsert(v.copy(...))` against an existing row - exactly
    // the shape ticket 13 removed everywhere else, because it is what overwrote a
    // real car's identity and odometer back to blank on Kevin's phone.
    //
    // It is harmless only while nothing calls it. Its own doc above describes an
    // "AI Profile form" that does not exist; the day someone builds that form and
    // wires it here, the bug is live again with no review to catch it.
    //
    // OWNED BY: .scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md
    // (question 3 - manual identity entry is that ticket's job, and this function
    // is the vestigial version of it). Rewrite it onto VehicleDao.setIdentity +
    // setOdometerBaseline, or delete it, when 14 is built. Do NOT wire a caller to
    // it as it stands.
    suspend fun saveVehicleFacts(
        context: Context,
        make: String,
        model: String,
        trim: String,
        year: Int,
        odometer: Int,
    ) {
        val v = currentVehicle(context)
        val mk = make.trim()
        val md = model.trim()
        val tr = trim.trim()
        val factsChanged = (mk.isNotBlank() && mk != v.make) || (md.isNotBlank() && md != v.model) ||
            (tr != v.trim) || (year > 0 && year != v.year)
        val newOdo = odometer > 0 && odometer != v.odometerBaseline
        val finalMake = if (mk.isNotBlank()) mk else v.make
        val finalModel = if (md.isNotBlank()) md else v.model
        CarDatabase.getDatabase(context).vehicleDao().upsert(
            v.copy(
                make = finalMake,
                model = finalModel,
                trim = tr,
                year = if (year > 0) year else v.year,
                odometerBaseline = if (odometer > 0) odometer else v.odometerBaseline,
                odometerBaselineAt = if (newOdo) System.currentTimeMillis() else v.odometerBaselineAt,
                tripMilesSinceBaseline = if (newOdo) 0.0 else v.tripMilesSinceBaseline,
                onboarded = if (factsChanged && mk.isNotBlank() && md.isNotBlank()) false else v.onboarded,
                // The driver saved the car-facts form (or onboarding register_car):
                // once make + model are present this car's identity is confirmed, so
                // recall/spec lookups stop reporting on the default mascot seed.
                confirmed = v.confirmed || (finalMake.isNotBlank() && finalModel.isNotBlank()),
            )
        )
    }

    /**
     * True once the driver has stated/confirmed the car's identity (not the
     * default seed). [vehicleId] is the fleet-wide-voice override (ticket 01) -
     * null means the active car, unchanged.
     */
    suspend fun isConfirmed(context: Context, vehicleId: String? = null): Boolean = vehicleFor(context, vehicleId).confirmed

    /** "2003 BMW 330i ZHP" from the driver-entered facts (blank parts dropped); "" if nothing set. */
    /** Active (non-archived) car profiles, for the picker and the CARS roster. */
    suspend fun allVehicles(context: Context): List<Vehicle> =
        CarDatabase.getDatabase(context).vehicleDao().getAll()

    /** Every car including archived, for the roster's "Show archived" toggle. */
    suspend fun allVehiclesIncludingArchived(context: Context): List<Vehicle> =
        CarDatabase.getDatabase(context).vehicleDao().getAllIncludingArchived()

    /**
     * Hides a car from the roster and picker without destroying anything
     * (car manager, 2026-07-16). Its drives, odometer and service history stay.
     *
     * Re-stamps `updatedAt` so this rides the ordinary LWW path to other devices -
     * archiving is just an edit, and treating it as one is why it needs no special
     * sync handling.
     *
     * If the archived car was ACTIVE, the selection is cleared back to "follow the
     * adapter". Leaving it selected would strand the driver on a car the picker no
     * longer lists, with no visible way to change it.
     */
    suspend fun archive(context: Context, vehicleId: String) = setArchived(context, vehicleId, true)

    /** Brings an archived car back into the roster. */
    suspend fun unarchive(context: Context, vehicleId: String) = setArchived(context, vehicleId, false)

    private suspend fun setArchived(context: Context, vehicleId: String, archived: Boolean) {
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        // Existence check only - the write itself is targeted (ticket 13),
        // touching archived only, so it can't clobber a concurrent edit to any
        // other column the way the old read-then-whole-row-write could.
        dao.getByMac(vehicleId) ?: return
        dao.setArchived(vehicleId, archived, System.currentTimeMillis())
        if (archived && ActiveVehicle.selected(context) == vehicleId) {
            ActiveVehicle.select(context, null)
        }
    }

    /**
     * Adds a car to the fleet WITHOUT making it active or touching any existing
     * row (fleet voice, 2026-08-09).
     *
     * **Why this is separate from [registerDirect].** `registerDirect` overwrites
     * whichever car is currently active - it exists to answer "this car is a 2003
     * BMW". Asked to ADD a car, the model called it anyway, and the driver's
     * Outlander silently became a Ford F-150, taking 5242 stored readings with it
     * under the wrong badge. Adding and correcting are different verbs and now
     * have different entry points; nothing here can reach an existing row.
     *
     * Deliberately does NOT call [ActiveVehicle.select]. A driver naming a second
     * car mid-drive has not stopped driving the first one, and silently moving the
     * active selection is the same class of surprise as the overwrite above.
     * Switching is its own explicit action.
     */
    suspend fun addVehicle(
        context: Context,
        year: Int,
        make: String,
        model: String,
        trim: String = "",
        name: String = "",
        // Ticket 14's manual-input field, alongside trim - default blank so every pre-existing
        // caller (voice `manage_vehicle` "add" action, which has no engine slot) keeps compiling.
        engine: String = "",
    ): String {
        if (make.isBlank() || model.isBlank()) return "I need at least a make and model to add a car."
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        // Deliberately NOT VehicleController.label (ticket 04's label rule): this confirms exactly
        // the facts the driver just stated, trim included, right after stating them - dropping trim
        // here would silently discard something they just said, unlike an ambient "which car is
        // this" label elsewhere.
        val label = listOf(year.takeIf { it >= 1900 }?.toString().orEmpty(), make, model, trim)
            .filter { it.isNotBlank() }.joinToString(" ")

        // Adding the car you already have on file is a correction, not a second
        // car. Say so rather than quietly growing a duplicate fleet.
        dao.getAllIncludingArchived().firstOrNull {
            it.make.equals(make, true) && it.model.equals(model, true) &&
                (year < 1900 || it.year == year)
        }?.let {
            return "You've already got a ${label(it)} on file. " +
                "If that one's wrong, tell me to correct it instead of adding another."
        }

        val vehicle = Vehicle(
            obdMac = ActiveVehicle.newVehicleId(),
            name = name.ifBlank { model },
            make = make,
            model = model,
            year = year,
            trim = trim,
            engine = engine,
            personaPrompt = "",
            odometerBaseline = 0,
            odometerBaselineAt = 0L,
            tripMilesSinceBaseline = 0.0,
            onboarded = false,
            confirmed = true,
        )
        dao.upsert(vehicle)
        // No more automatic applyServiceIntervals call here (ticket 14) - the new row starts with
        // an empty schedule, same as registerDirect.
        val active = currentVehicle(context)
        return "Added the $label. No maintenance schedule on file yet - populate it whenever you're " +
            "ready. You're still on the ${label(active)} - say switch to the $model when you want me on that one."
    }

    /**
     * Corrects the stored facts on ONE named car, leaving every other field and
     * all of its history alone. Null means "don't touch".
     *
     * This is the repair path for a row that got the wrong badge written onto it -
     * the readings stay put, only the identity changes.
     */
    suspend fun correctVehicle(
        context: Context,
        vehicleId: String,
        year: Int? = null,
        make: String? = null,
        model: String? = null,
        trim: String? = null,
        name: String? = null,
        // Ticket 14's manual-input field. Null means "don't touch", matching every other param
        // here - written through its OWN targeted query ([VehicleDao.setEngine]) rather than folded
        // into [VehicleDao.setIdentity], so a correction that never mentions engine can never
        // silently blank it, and an engine-only correction never has to restate the whole identity.
        engine: String? = null,
    ): String {
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val existing = dao.getByMac(vehicleId) ?: return "I couldn't find that car on file."
        val updated = existing.copy(
            year = year?.takeIf { it >= 1900 } ?: existing.year,
            make = make?.takeIf { it.isNotBlank() } ?: existing.make,
            model = model?.takeIf { it.isNotBlank() } ?: existing.model,
            trim = trim ?: existing.trim,
            name = name?.takeIf { it.isNotBlank() } ?: existing.name,
            confirmed = true,
        )
        val identityChanged = updated != existing
        val engineChanged = engine != null && engine.trim() != existing.engine
        if (!identityChanged && !engineChanged) return "Nothing to change there - it's already a ${label(existing)}."
        val now = System.currentTimeMillis()
        if (identityChanged) {
            // Targeted write (ticket 13): identity columns only, via
            // VehicleDao.setIdentity - the odometer, persona and archive state on
            // this row ride along untouched instead of round-tripping through a
            // whole-row upsert of a struct built from a read that could be stale.
            dao.setIdentity(vehicleId, updated.year, updated.make, updated.model, updated.trim, updated.name, now)
        }
        if (engineChanged) dao.setEngine(vehicleId, engine!!.trim(), now)
        // No more automatic applyServiceIntervals call here (ticket 14) - correcting a car's badge
        // no longer silently re-seeds its schedule. A populate is a deliberate, separate action.
        return "Fixed - that one's a ${label(updated)} now. Its history stayed with it."
    }

    /**
     * Applies a vPIC-decoded [VinDecoder.DecodedVin] onto the stored [vehicleId] row - ticket 04's
     * VIN identity write-back (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
     * `13-the-jeep-row-lost-its-identity.md`). Before this, `VehicleSpecController.refreshFromVin`
     * decoded and stored the `vehicle_specs` row and threw away [VinDecoder.decode]'s identity
     * fields entirely - Kevin's `vehicle_specs` held a fully-decoded Jeep VIN since 2026-07-26
     * while `vehicles` still read `make=''`, `model=''`, `year=0`.
     *
     * **Policy, verbatim from [VinDecoder]'s own class doc**: a decode is "a confirmable
     * suggestion, never a silent overwrite of driver-entered facts." Applied per field,
     * independently, across year/make/model/trim - never [com.kevin.legion.data.local.Vehicle.name],
     * which a VIN decode has no way to supply:
     *  - blank/zero on file -> filled from the decode.
     *  - already set and the decode has nothing to say (year absent, trim absent) -> that field is
     *    skipped entirely, neither a fill nor a conflict.
     *  - already set and the decode AGREES -> left alone. A same-value write would be pointless
     *    and would re-stamp the LWW sync clock for nothing, the same discipline [correctVehicle]'s
     *    own no-op branch follows.
     *  - already set and the decode DISAGREES -> **not written, anywhere.** A conflict on ANY
     *    field aborts the WHOLE write, not just that field.
     *
     *    Two reasons, and the second is the load-bearing one. First, a half-applied identity
     *    (some fields decoded-and-written, others left silently disagreeing) is exactly the
     *    confused state ticket 04 exists to close, not a smaller version of it. Second, and more
     *    importantly: **a disagreement is evidence the decode may not describe this car at all.**
     *    A VIN misread off the OBD port, a transposed character, a dongle moved to another
     *    vehicle - all present as "one field doesn't match". Filling the BLANK fields from a
     *    decode you have concrete reason to distrust does not half-fix the row, it writes another
     *    vehicle's facts into it, silently, and they would then read as this car's own. Declining
     *    the whole write is the only option that cannot make the row less true than it was.
     *
     *    The driver resolves the conflict - by typing the correction, or by accepting vPIC's value
     *    through [correctVehicle] - and reconciles again.
     *
     * **Never sets [com.kevin.legion.data.local.Vehicle.confirmed].** Deliberately does not reuse
     * [VehicleDao.setIdentity] (which always stamps `confirmed = 1`, right for a DRIVER stating the
     * car's identity) - see [VehicleDao.applyDecodedIdentity]'s own doc for why a vPIC lookup
     * filling in blanks must not silently mark the car driver-confirmed on the driver's behalf.
     */
    suspend fun applyDecodedIdentity(
        context: Context,
        vehicleId: String,
        decoded: VinDecoder.DecodedVin?,
    ): IdentityWriteResult {
        if (decoded == null || !decoded.isUsable) return IdentityWriteResult.Unusable
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val existing = dao.getByMac(vehicleId) ?: return IdentityWriteResult.NoSuchVehicle

        data class Field(val name: String, val onFile: String, val decodedValue: String?)
        val fields = listOf(
            Field("year", existing.year.takeIf { it > 0 }?.toString().orEmpty(), decoded.year.takeIf { it > 0 }?.toString()),
            Field("make", existing.make, decoded.make.takeIf { it.isNotBlank() }),
            Field("model", existing.model, decoded.model.takeIf { it.isNotBlank() }),
            Field("trim", existing.trim, decoded.trim.takeIf { it.isNotBlank() }),
        )

        val conflicts = mutableListOf<FieldConflict>()
        val changed = mutableListOf<String>()
        for (f in fields) {
            val dv = f.decodedValue ?: continue // the decode has nothing to say about this field
            when {
                f.onFile.isBlank() -> changed += f.name
                // Compared TRIMMED and case-insensitively. Both halves are load-bearing and
                // neither is cosmetic, because a false conflict here is expensive: it aborts the
                // whole write (see the doc above) and shows the driver a disagreement that is not
                // one. Case covers "4WD" vs "4wd". Trim covers the asymmetry between the two
                // sources - VinDecoder.parse trims and title-cases what it decodes, while
                // correctVehicle stores driver-typed make/model/trim with no trim() at all
                // (:488-490), so a stray trailing space from a text field would otherwise read as
                // "Cherokee " != "Cherokee". Caught on review, 2026-08-15.
                !f.onFile.trim().equals(dv.trim(), ignoreCase = true) ->
                    conflicts += FieldConflict(f.name, onFile = f.onFile, decoded = dv)
                // else: already agrees - no-op for this field
            }
        }

        if (conflicts.isNotEmpty()) return IdentityWriteResult.Conflict(conflicts)
        if (changed.isEmpty()) return IdentityWriteResult.NothingToDo

        val finalYear = if ("year" in changed) decoded.year else existing.year
        val finalMake = if ("make" in changed) decoded.make else existing.make
        val finalModel = if ("model" in changed) decoded.model else existing.model
        val finalTrim = if ("trim" in changed) decoded.trim else existing.trim
        val written = dao.applyDecodedIdentity(vehicleId, finalYear, finalMake, finalModel, finalTrim, System.currentTimeMillis())
        // The row existed a moment ago (the getByMac above) but a targeted UPDATE can still touch
        // zero rows if it was archived/removed in between - report that honestly rather than claim
        // Applied for a write that changed nothing (VehicleDao.applyDecodedIdentity's own doc).
        if (written == 0) return IdentityWriteResult.NoSuchVehicle
        return IdentityWriteResult.Applied(changed)
    }

    /** Makes [vehicleId] the car every stored-data tool answers about. */
    suspend fun switchTo(context: Context, vehicleId: String): String {
        val vehicle = CarDatabase.getDatabase(context).vehicleDao().getByMac(vehicleId)
            ?: return "I couldn't find that car on file."
        if (vehicle.archived) return "The ${label(vehicle)} is archived - want me to bring it back first?"
        ActiveVehicle.select(context, vehicleId)
        return "You're on the ${label(vehicle)} now."
    }

    /**
     * Creates a car profile that is NOT tied to a dongle and makes it active
     * (car profiles, 2026-07-16).
     *
     * This is how a driver with one dongle and two cars keeps them apart: the id
     * is synthetic ([ActiveVehicle.newVehicleId]) rather than a MAC, so moving the
     * dongle between cars no longer merges them into one vehicle - and, critically,
     * no longer makes them fight over one LWW `vehicles` row and overwrite each
     * other's `odometerBaseline` on every sync.
     */
    suspend fun createCarProfile(
        context: Context,
        year: Int,
        make: String,
        model: String,
        trim: String = "",
    ): Vehicle {
        val id = ActiveVehicle.newVehicleId()
        val vehicle = Vehicle(
            obdMac = id,
            name = model.ifBlank { "New car" },
            make = make, model = model, year = year, trim = trim,
            personaPrompt = "",
            odometerBaseline = 0,
            odometerBaselineAt = 0L,
            tripMilesSinceBaseline = 0.0,
            onboarded = false,
            confirmed = true,
        )
        CarDatabase.getDatabase(context).vehicleDao().upsert(vehicle)
        ActiveVehicle.select(context, id)
        return vehicle
    }

    /**
     * Records a "this drive belongs to another car" correction and applies it now
     * (car manager, 2026-07-16).
     *
     * Writes a RULE rather than re-keying the rows directly: `obd_samples` syncs
     * UNION on an identity that INCLUDES vehicleId, so a plain UPDATE would leave
     * the originals on Drive under the old id, and the next sync would re-insert
     * them - cloning the drive onto both cars instead of moving it, permanently, on
     * every device. See [com.kevin.legion.data.local.DriveReassignment].
     *
     * The local apply here is just for immediacy; [com.kevin.legion.sync.SyncEngine]
     * re-applies the rule on every pass, which is what makes it converge.
     */
    suspend fun reassignDrive(
        context: Context,
        fromVehicleId: String,
        toVehicleId: String,
        fromMs: Long,
        toMs: Long,
    ) {
        if (fromVehicleId == toVehicleId) return
        val db = CarDatabase.getDatabase(context)
        db.driveReassignmentDao().insert(
            DriveReassignment(
                syncId = java.util.UUID.randomUUID().toString(),
                vehicleId = fromVehicleId,
                fromMs = fromMs,
                toMs = toMs,
                newVehicleId = toVehicleId,
                updatedAt = System.currentTimeMillis(),
            )
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE `obd_samples` SET `vehicleId` = ? WHERE `vehicleId` = ? AND `timestamp` BETWEEN ? AND ?",
            arrayOf(toVehicleId, fromVehicleId, fromMs, toMs),
        )
    }

    /**
     * `year make model trim` - every stated identity fact, including trim. **Not the driver-facing
     * label anymore** (ticket 04, `.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`) -
     * see [label] for that. This is kept for the two kinds of caller that genuinely need the
     * fuller, trim-inclusive spec rather than the one label rule: sub-agent grounding text
     * ([DiagnosticAgent.diagnose]/[SymptomAgent.triage]/[ColdStartAgent.analyze]/[MaintenanceAgent.answer]
     * all reason more precisely with a real trim, e.g. "330i" vs "330Ci ZHP", than a driver would
     * ever want spoken back as a label) and driver-facing CONFIRMATION sentences that echo exactly
     * the facts just stated/corrected ([addVehicle]'s "Added the ..." line), where dropping trim
     * would silently discard something the driver just said. Every surface that names a car TO the
     * driver as an ambient label - screen or spoken - goes through [label], not this.
     */
    fun displayLabel(vehicle: Vehicle): String =
        listOf(
            vehicle.year.takeIf { it > 0 }?.toString().orEmpty(),
            vehicle.make, vehicle.model, vehicle.trim,
        ).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * `year make model` - [displayLabel] minus trim. The `spec` half of [label]'s rule, and also
     * the building block [com.kevin.legion.ui.fleet.CarRows.carLabel]/[com.kevin.legion.ui.fleet.CarRows.carSpecPrefix]
     * use for the two-line narrow-width variant of the same rule (ticket 04's "nickname on top,
     * spec beneath" shape) - trim is excluded there for the identical reason it is excluded here,
     * so the two shapes never disagree about what "the spec" is.
     */
    fun identitySpec(vehicle: Vehicle): String =
        listOf(
            vehicle.year.takeIf { it > 0 }?.toString().orEmpty(),
            vehicle.make, vehicle.model,
        ).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * THE one car-label rule (ticket 04, resolved 2026-08-15,
     * `.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`) - every surface that names a
     * car to the driver, screen and speech alike, renders through this function. It replaced
     * twelve-then-twenty-four ad-hoc call sites across [displayLabel] and raw [Vehicle.name] reads,
     * each with its own drifted precedence and its own last-resort literal ("This car", "THIS CAR",
     * "vehicle", "an unnamed car", "Fleet", a raw [Vehicle.obdMac]) - RENAME visibly worked on three
     * of them and silently did nothing on the rest.
     *
     * ```
     * nickname blank, spec blank -> "a car you haven't named yet"
     * nickname blank             -> spec
     * spec blank                 -> nickname
     * spec CONTAINS nickname     -> spec        (de-duplication: a car named after its own spec -
     *                                             Kevin's own Jeep - must not read twice)
     * otherwise                  -> "nickname (spec)"
     * ```
     *
     * `spec` is [identitySpec] - YEAR MAKE MODEL, never [displayLabel]'s trim-inclusive form. Trim
     * breaks the de-duplication clause exactly when a driver has named the car after its own spec
     * (the common case for a car with no real nickname): `displayLabel` on Kevin's Jeep is
     * "1998 Jeep Cherokee Limited", which does not contain his stored name "1998 Jeep Cherokee",
     * so the clause would miss and the row would render "1998 Jeep Cherokee (1998 Jeep Cherokee
     * Limited)". Trim still belongs on a detail surface - just not this one.
     *
     * Never blank. The single last-resort string replaces every retired literal above; **it is not
     * a magic value written to the database** the way `"this car"` used to be - a blank
     * [Vehicle.name] means "unknown", full stop, and this function is where that state gets worded,
     * every time, rather than a sentinel some callers remembered to filter and most did not.
     *
     * Where there is no room for one line, [com.kevin.legion.ui.fleet.CarRows.carLabel] /
     * [com.kevin.legion.ui.fleet.CarRows.carSpecPrefix] implement the identical precedence split
     * across two lines (nickname on top, spec beneath) rather than combining them - a deliberate,
     * named two-shape API per this rule, not a second rule.
     */
    fun label(vehicle: Vehicle): String {
        val nickname = vehicle.name.trim()
        val spec = identitySpec(vehicle)
        return when {
            nickname.isBlank() && spec.isBlank() -> "a car you haven't named yet"
            nickname.isBlank() -> spec
            spec.isBlank() -> nickname
            spec.contains(nickname, ignoreCase = true) -> spec
            else -> "$nickname ($spec)"
        }
    }

    /** Current odometer estimate: driver-reported baseline plus GPS-or-OBD trip distance since. */
    fun currentMileage(vehicle: Vehicle): Int =
        vehicle.odometerBaseline + vehicle.tripMilesSinceBaseline.roundToInt()

    /**
     * The estimate caveat phrase alone - "estimated, last confirmed 3 days ago" / "estimated, never
     * confirmed" - or `null` when [vehicle]'s current mileage IS the driver's own last typed
     * reading with nothing accrued since (ticket 10: "the confirmed reading renders bare - it is a
     * fact Kevin stated. Only the estimate carries the caveat.").
     *
     * Split out from [mileageLabel] so a caller that only needs to warn a DERIVED figure - most
     * notably `get_next_service`'s "due in N miles", itself downstream of this same estimate via
     * [computeNextService] - can embed the phrase into its own sentence rather than duplicating the
     * bare/estimate branch a second time. `internal` for direct unit testing, no Context/Room.
     */
    internal fun mileageCaveat(vehicle: Vehicle, now: Long = System.currentTimeMillis()): String? {
        // Nothing to show at all - no number, so no caveat. This is the ONLY reason to return null
        // besides a genuinely confirmed reading.
        if (currentMileage(vehicle) <= 0) return null

        // Bare ONLY when the figure IS the driver's own typed reading with nothing accrued since.
        if (vehicle.odometerBaseline > 0 && vehicle.tripMilesSinceBaseline == 0.0) return null

        // Everything else is an estimate, including - especially - a car whose baseline was never
        // set at all but which has accumulated trip miles.
        //
        // That case used to return null and render BARE, which was backwards: it is the LEAST
        // confirmed number the app can produce, pure dead reckoning against an anchor that has
        // never existed, and it was the one figure escaping the caveat entirely. A car can reach it
        // by ordinary use - `AddCarDialog` never asks for a reading, and TelemetryRecorder's
        // accumulation is keyed only on the row existing, not on any baseline being set.
        //
        // The old guard conflated "no drift to measure against" with "nothing to label". Ticket
        // 10's rule is about whether the number IS the driver's stated reading, not about whether a
        // delta is computable - and its decision text says it without a loophole: "no threshold to
        // tune, no window where a drifting number renders bare". Caught on review, 2026-08-15.
        return if (vehicle.odometerBaselineAt <= 0L) {
            "estimated, never confirmed"
        } else {
            "estimated, last confirmed ${relativeAge(vehicle.odometerBaselineAt, now)}"
        }
    }

    /**
     * The number half of [mileageLabel] alone - `"227,900 mi"` bare, `"about 227,900 mi"` once
     * anything has accrued since the last reading. Split out for [FleetUiState.mileageValueText]
     * (that state's own doc explains why the CARS pane can't render the combined string as one
     * [com.kevin.legion.ui.common.DeckRow] value) - the "about" prefix rides WITH the number here
     * rather than the caveat, so splitting the two apart for display never drops or duplicates a
     * word from [mileageLabel]'s own sentence. Blank when there is nothing to show at all
     * ([currentMileage] <= 0) - see [mileageLabel]'s doc for why. `internal` for direct unit
     * testing, no Context/Room.
     */
    internal fun mileageValueText(vehicle: Vehicle): String {
        val mileage = currentMileage(vehicle)
        if (mileage <= 0) return ""
        val prefix = if (mileageCaveat(vehicle) == null) "" else "about "
        return "$prefix${groupThousandsLong(mileage.toLong())} mi"
    }

    /**
     * The current-mileage figure, formatted exactly the way ticket 10 decided, for every surface
     * that SPEAKS it as one sentence - `ask_maintenance`'s pre-seeded context (via
     * [com.kevin.legion.vehicle.MaintenanceAgent.answer]) and any future caller that has no reason
     * to split it: `"about 227,900 mi - estimated, last confirmed 3 days ago"` between readings,
     * `"227,900 mi"` bare the instant a reading is taken. No threshold, no window where a drifting
     * number renders bare - see [mileageCaveat]'s doc for the exact bare/estimate split, and ticket
     * 03's research for WHY an estimate needs the caveat at all (~5-15% low, always in the same
     * direction, never the other way). FLEET's CARS pane renders [mileageValueText] and
     * [mileageCaveat] as two separate lines instead of calling this directly - see
     * [FleetUiState.mileageValueText]'s own doc.
     *
     * Blank when there is nothing to show at all ([currentMileage] <= 0) - callers already say
     * "odometer not set" in their own words elsewhere ([NextService.odometerUnset],
     * [FleetUiState.odometerUnset]); this function is not where that sentence lives.
     * `internal` for direct unit testing, no Context/Room.
     */
    internal fun mileageLabel(vehicle: Vehicle, now: Long = System.currentTimeMillis()): String {
        val value = mileageValueText(vehicle)
        if (value.isBlank()) return ""
        val caveat = mileageCaveat(vehicle, now) ?: return value
        return "$value - $caveat"
    }

    /**
     * Maintenance items currently due, by mileage or time, for [vehicle].
     *
     * An item needs a KNOWN anchor to be due at all - see [isDue]. This used to
     * treat a null [MaintenanceItem.lastDoneMileage] as 0 (`?: 0`) and fall back
     * to `vehicle.odometerBaselineAt` for the time branch, which meant every
     * freshly-looked-up interval (all anchors null, nothing done yet) read as
     * due immediately. On a high-mileage car that made the companion cry wolf
     * about the entire schedule the moment it was seeded (AriaBrain.kt injects
     * this list into the system instruction). Unknown items are now excluded
     * outright; see [unknownItems] for surfacing them separately, honestly, as
     * "I don't know" rather than "overdue".
     *
     * `odometerUnset = vehicle.odometerBaseline == 0` guards [isDue]'s mileage axis the same way
     * [ui.fleet.chooseDueAxis] guards it in the render path - see [isDue]'s doc (ticket 15).
     */
    suspend fun dueItems(context: Context, vehicle: Vehicle): List<MaintenanceItem> {
        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val mileage = currentMileage(vehicle)
        val now = System.currentTimeMillis()
        return items.filter { isDue(it, mileage, vehicle.odometerBaseline == 0, now) }
    }

    /**
     * Items with no anchor at all - the driver has never told us when they were
     * last done and [MaintenanceItem.neverDone] hasn't been confirmed either.
     * These are deliberately excluded from [dueItems] (see its doc) and from
     * [nextService]'s ranking; they're surfaced separately so the companion can
     * ask rather than assume.
     */
    suspend fun unknownItems(context: Context, vehicle: Vehicle): List<MaintenanceItem> {
        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        return items.filter { isUnknown(it) }
    }

    /** Unit the driver asked about ("how many miles until X" vs. "how many days"). */
    enum class ScheduleUnit { MILES, DAYS }

    /** One soonest-due item on a single axis - see [NextService]. */
    data class ServiceCandidate(
        val serviceName: String,
        val remaining: Long,
        val unit: ScheduleUnit,
    )

    /**
     * The soonest-due anchored item on EACH axis, for "what's coming up next".
     *
     * There is deliberately no single cross-unit "winner": ranking a miles-only
     * item against a months-only item requires either converting one unit into
     * the other (a rate estimate - miles per day - which Kevin explicitly
     * rejected) or picking an arbitrary tie-break rule that has nothing to do
     * with which is actually more urgent. So both axes are reported separately
     * and the caller phrases it as "X, N miles or M days, whichever comes
     * first" when the same item leads both, or names both leaders when they
     * differ. See [computeNextService]'s doc for the full reasoning and the
     * ranking bug this replaced.
     *
     * @param byMiles soonest not-yet-due item with a mileage threshold, null if none.
     * @param byTime soonest not-yet-due item with a time threshold, null if none.
     * @param unknownCount / [unknownNames] items excluded because their anchor is
     *   unknown (not because they're not due) - callers should say "and I don't
     *   know about N others" rather than silently omitting them.
     * @param odometerUnset true when the vehicle has never had an odometer
     *   reading, so [byMiles] was skipped entirely (see [computeNextService]).
     * @param allDue true when the schedule has items, but EVERY one of them is
     *   already due (or unknown-free-and-due) - there is nothing left to rank as
     *   "next" because nothing is left in the not-yet-due candidate pool. This is
     *   a materially different state from "no schedule at all" (that's
     *   [computeNextService] returning null outright) and needs its own honest
     *   phrasing rather than either "nothing due" or "no schedule yet".
     */
    data class NextService(
        val byMiles: ServiceCandidate?,
        val byTime: ServiceCandidate?,
        val unknownCount: Int,
        val unknownNames: List<String>,
        val odometerUnset: Boolean,
        val allDue: Boolean,
    )

    /** The soonest-due anchored, not-yet-due item on each axis for [vehicle]. Null if there is nothing to report at all. */
    suspend fun nextService(context: Context, vehicle: Vehicle): NextService? {
        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        return computeNextService(items, currentMileage(vehicle), System.currentTimeMillis(), vehicle.odometerBaseline)
    }

    /** True if it's been over a month since the driver last confirmed the odometer. */
    fun odometerCheckInDue(vehicle: Vehicle): Boolean =
        System.currentTimeMillis() - vehicle.odometerBaselineAt >= MONTH_MS &&
            System.currentTimeMillis() - vehicle.lastOdometerPromptAt >= MONTH_MS

    /** Records that Aria just asked for an odometer update, so it doesn't nag again too soon. */
    suspend fun markOdometerPrompted(context: Context, vehicle: Vehicle) {
        // Targeted write (ticket 13): touches lastOdometerPromptAt only.
        val now = System.currentTimeMillis()
        CarDatabase.getDatabase(context).vehicleDao().markOdometerPrompted(vehicle.obdMac, now, now)
    }

    // (2026-07-19) trackTripMileage was DELETED. It was a separate GPS-only
    // service loop writing tripMilesSinceBaseline - dead on a head unit with no
    // GPS antenna (the primary test car), and a second odometer alongside
    // TelemetryRecorder's. TelemetryRecorder.run is now the single odometer
    // writer, computing per-tick miles from GPS when a fix exists and OBD speed
    // (PID 010D) when it doesn't, so the persisted estimate advances either way.

    // onboardPendingVehicles was DELETED (ticket 14, 2026-08-15,
    // `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`). It fired once
    // at every service start (AriaForegroundService.onCreate) and silently seeded EVERY car that had
    // a make/model but had not yet been onboarded - the mechanism that put 54 rows and 49 empty
    // anchors across Kevin's roster without him ever asking for one (ticket 01's audit). A new car
    // now starts with an EMPTY schedule and says so ("no maintenance schedule on file yet"); the
    // only way a schedule ever gets written now is a deliberate, driver-triggered populate
    // (`vehicle/PopulateSchedule.kt`) that shows a diff and writes NOTHING until each row is
    // individually accepted. Its old caller in AriaForegroundService.kt was removed with it.

    // applyServiceIntervals was DELETED alongside it - it was the writer onboardPendingVehicles and
    // the (also now-removed) automatic calls in registerDirect/addVehicle/correctVehicle all shared:
    // canonicalize-and-dedupe, then insertAll (IGNORE) tagged SEEDED, then flip `onboarded`. Nothing
    // calls it anymore. Ticket 14's populate diff needs the LOOKUP half of what it did (see
    // [fetchFactorySchedule] below) but never the blind-write half - every row it proposes is shown
    // to the driver first, and every accepted row is tagged CONFIRMED (ticket 06 decision b: "any
    // driver action that names the value moves it to CONFIRMED... accepting a populate diff row"),
    // never SEEDED. `VehicleDao.markOnboarded` and `Vehicle.onboarded` are left in place (removing a
    // column for no gain is not this ticket's job) but nothing writes `onboarded` anymore either -
    // see the column's own doc comment in `data/local/Vehicle.kt`.

    // --- Registration -------------------------------------------------

    /**
     * The read-only half of what [lookupServiceIntervals] used to feed straight into a blind write
     * (ticket 14): asks the LLM for [vehicle]'s factory schedule and returns it canonicalized and
     * deduped, WITHOUT writing anything or touching [Vehicle.onboarded] - a populate diff (built by
     * `vehicle/PopulateSchedule.kt`'s `buildPopulateDiff`) is what decides what, if anything,
     * actually lands, and nothing here may pre-empt that. Canonicalizing here, not just at diff time,
     * matters for the SAME reason the old seed canonicalized before its own write: a lookup
     * returning both "Oil Change" and "Engine Oil & Filter Change" must collapse to ONE candidate
     * row before the diff ever compares it against the driver's own schedule, or the diff would
     * offer to "add" two different names for the same real service.
     *
     * **`null` propagates straight through from [lookupServiceIntervals] - a genuine lookup failure,
     * never silently downgraded to "found nothing."** See that function's own doc: the two must stay
     * distinguishable, because [buildPopulateDiff] would otherwise read a failed network call as "the
     * factory schedule has zero items" and flag every item already on file as not-in-schedule.
     */
    suspend fun fetchFactorySchedule(context: Context, vehicle: Vehicle): List<MaintenanceItem>? =
        lookupServiceIntervals(context, vehicle)?.let { canonicalizeAndDedupe(it) }

    /**
     * Canonicalizes each looked-up item's name and drops later collisions, so a
     * lookup returning both "Oil Change" and "Engine Oil & Filter Change" yields
     * one row rather than two upserts fighting over the same primary key.
     */
    private fun canonicalizeAndDedupe(items: List<MaintenanceItem>): List<MaintenanceItem> {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val canonical = canonicalizeServiceName(item.serviceName)
            if (!seen.add(canonical)) null else item.copy(serviceName = canonical)
        }
    }

    // refreshServiceIntervals was DELETED (ticket 05, 2026-08-15). It had zero
    // callers in app/src/main - dead code that did the merge-intervals-onto-
    // existing-rows job ticket 14's populate now owns properly, with a diff
    // and a driver confirmation. Dead code that looks like a working feature
    // is exactly what made "I changed the oil interval to 7,500" plausible to
    // Kevin in the first place (ticket 05's own finding) - it does not get to
    // sit next to the rebuild. If ticket 14 needs this shape again, it should
    // be built against the targeted `MaintenanceItemDao.setIntervals` write,
    // not resurrected as a whole-row upsert.

    /**
     * Asks [AriaBrain] (with search grounding) for the manufacturer's NORMAL scheduled
     * maintenance intervals for this car.
     *
     * **Normal, not severe (Kevin, 2026-08-15 - ticket 06 question 6).** This prompt used to
     * demand the SEVERE / heavy-duty schedule, and that single word is what put a 3,000-mile
     * oil interval on Kevin's 1998 Cherokee. Ticket 02 went and read the factory schedules:
     * Chrysler's Schedule A (normal) is **7,500 miles or 6 months**, Schedule B (severe) is
     * **3,000 miles with no time interval at all**. The model had answered correctly; the
     * question was wrong. Severe is deliberately not offered as a setting - a car that genuinely
     * lives a hard life gets its items edited by hand.
     *
     * **The item list was removed, and that is the other half of the fix.** The old prompt named
     * "brake fluid" and "cabin air filter" and asked for "6 to 12 objects". Ticket 02 established
     * that the XJ's factory schedule contains **no brake fluid service at all** (only a monthly
     * level check) and that **the XJ has no cabin air filter**. So the prompt was naming items
     * the vehicle does not have and setting a quota that had to be filled somehow - and both
     * duly appeared on Kevin's phone as invented rows. A lookup must be allowed to return a
     * short answer, and must never be told in advance what it will find.
     *
     * Whatever comes back is still an LLM's retrieval, not a figure the car stated - CLAUDE.md
     * §4 rule 5. Labelling it as an estimate is ticket 06's job, not this function's.
     *
     * **[Vehicle.engine] is folded in when present (ticket 14).** Ticket 02's research is explicit
     * about why: a 1998 XJ's factory schedule differs by engine (a 4.0L I6 and a 2.5L I4 disagree on
     * plugs and capacities), so naming it disambiguates the same way [trim] already does - engine
     * first, trim after, since "4.0L I6 Limited" reads naturally in that order and the LLM has both
     * pieces of context either way.
     *
     * **Returns `null` on a genuine lookup failure - network error, or a response that doesn't parse
     * as JSON - never the same `emptyList()` a well-formed `[]` produces (ticket 14 fix, caught on
     * review before this reached a populate).** The two used to collapse onto one value, which is
     * exactly the silent-failure shape CLAUDE.md's reconciliation-gate posture exists to prevent
     * elsewhere: [fetchFactorySchedule] feeds this straight into [buildPopulateDiff], and an empty
     * factory list reads there as "the manufacturer publishes NO schedule for this car" - every
     * active item on file would then show as `notInFactorySchedule`, a network hiccup dressed up as
     * "delete everything." [PopulateScreen][com.kevin.legion.ui.fleet.PopulateScreen] surfaces `null`
     * as a retryable error rather than ever building a diff from it.
     */
    private suspend fun lookupServiceIntervals(context: Context, vehicle: Vehicle): List<MaintenanceItem>? {
        val engine = vehicle.engine.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val trim = vehicle.trim.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val prompt = "Use search to find the manufacturer's published NORMAL scheduled maintenance " +
            "intervals - the standard/light-duty schedule, NOT the severe or heavy-duty one - for a " +
            "${vehicle.year} ${vehicle.make} ${vehicle.model}$engine$trim. Respond with ONLY a raw JSON array " +
            "(no markdown, no commentary, no code fences) of objects, each with keys " +
            "\"service\" (short title-case name like \"Oil Change\"), \"intervalMiles\" (integer or null), " +
            "and \"intervalMonths\" (integer or null). Include ONLY items the manufacturer actually " +
            "publishes a scheduled interval for on THIS vehicle. Do not add common items the schedule " +
            "does not list, do not invent an interval for an item the schedule gives none for (use null " +
            "for that field), and do not pad the list to a particular length - a short, correct answer " +
            "is better than a long one. Return an empty array if you cannot find the schedule."
        val raw = try {
            AriaBrain.get(context).structuredQuery(prompt)
        } catch (e: Exception) {
            Log.w(TAG, "Service interval lookup failed: ${e.message}")
            null
        } ?: return null

        return parseIntervals(vehicle.obdMac, raw)
    }

    /** `null` on anything that doesn't parse as JSON - see [lookupServiceIntervals]'s doc for why that must not collapse onto a genuinely empty `[]`. */
    private fun parseIntervals(vehicleId: String, raw: String): List<MaintenanceItem>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null

        return try {
            val arr = JSONArray(raw.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("service").trim()
                if (name.isBlank()) return@mapNotNull null
                MaintenanceItem(
                    vehicleId = vehicleId,
                    serviceName = name,
                    intervalMiles = if (o.isNull("intervalMiles")) null else o.optInt("intervalMiles").takeIf { it > 0 },
                    intervalMonths = if (o.isNull("intervalMonths")) null else o.optInt("intervalMonths").takeIf { it > 0 },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse service intervals: ${e.message}")
            null
        }
    }

    // --- Pure decision logic (no Context, no Android - unit-testable directly) ----

    /**
     * True if [item] is due, given [currentMileage] and [now]. `internal` so
     * [MaintenanceScheduleTest] can drive it directly without Room or a Context
     * (per CLAUDE.md §11's testing convention: unit tests run on a plain JVM with
     * an unmocked android.jar, so platform/DB calls must stay out of what gets
     * tested).
     *
     *  - [MaintenanceItem.neverDone] is always due - it's a known, actionable fact.
     *  - No anchor at all (both lastDone* null, and not neverDone) is UNKNOWN, not
     *    due - see [isUnknown] and [dueItems]'s doc for why this used to be a bug.
     *  - Otherwise due if either its own mileage or time threshold has been
     *    crossed. A missing interval/anchor for one axis just excludes that axis,
     *    it never counts as due on its own.
     *
     * **[odometerUnset] guards the mileage axis, matching [ui.fleet.chooseDueAxis]'s render-path
     * guard exactly** (`.scratch/fleet-maintenance/issues/15-isdue-and-the-digest-inherit-the-same-two-gaps.md`).
     * Before this parameter existed, this function computed `mileageDue` off `currentMileage` with
     * no regard for whether the odometer had ever been confirmed, so an item could be sorted into
     * OVERDUE by [dueItems]/[buildDueRows]/[buildScheduleRows] off an odometer nobody set - the sort
     * path was silently less honest than the render path ticket 09 already fixed. **The signal is
     * `vehicle.odometerBaseline == 0`, never `currentMileage > 0`**: [currentMileage] is
     * `odometerBaseline + tripMilesSinceBaseline`, and [com.kevin.legion.vehicle.TelemetryRecorder]'s
     * trip-mile accumulation runs unconditionally on `odometerBaseline`, so a car can read a positive
     * `currentMileage` while the odometer itself is still unconfirmed - the exact case this guard
     * exists for. Every caller either already has this value in scope (the two [ui.fleet.FleetRows]
     * builders take it as their own parameter) or derives it trivially from the [Vehicle] it already
     * holds ([dueItems], [computeNextService]).
     */
    internal fun isDue(item: MaintenanceItem, currentMileage: Int, odometerUnset: Boolean, now: Long): Boolean {
        if (item.neverDone) return true
        if (item.lastDoneMileage == null && item.lastDoneDate == null) return false
        val mileageDue = !odometerUnset && item.intervalMiles != null && item.lastDoneMileage != null &&
            currentMileage - item.lastDoneMileage >= item.intervalMiles
        val timeDue = item.intervalMonths != null && item.lastDoneDate != null &&
            now - item.lastDoneDate >= item.intervalMonths.toLong() * MONTH_MS
        return mileageDue || timeDue
    }

    /** True if [item] has no anchor at all and hasn't been confirmed never-done. */
    internal fun isUnknown(item: MaintenanceItem): Boolean =
        !item.neverDone && item.lastDoneMileage == null && item.lastDoneDate == null

    /**
     * Resolves the mileage anchor for [logPastServiceDirect]'s "milesAgo" phrasing
     * ("I did it about 3,000 miles ago") into an absolute mileage: [mileage] wins
     * if given directly, else [currentMileage] minus [milesAgo], floored at 0 so a
     * driver overestimating "miles ago" past their current mileage never produces
     * a negative anchor.
     *
     * [milesAgo] itself is clamped to >= 0 first: it arrives from a voice tool
     * argument (Gemini's function-call args are not schema-validated for sign),
     * and a NEGATIVE milesAgo would otherwise compute `currentMileage - (-x)` =
     * `currentMileage + x`, a lastDoneMileage GREATER than currentMileage - a
     * service anchor dated into the future, which [isDue]'s subtraction assumes
     * can never happen.
     */
    internal fun resolveBackfillMileage(mileage: Int?, milesAgo: Int?, currentMileage: Int): Int? =
        mileage ?: milesAgo?.coerceAtLeast(0)?.let { (currentMileage - it).coerceAtLeast(0) }

    /**
     * Pure merge logic behind [logPastServiceDirect]. `internal` for direct unit
     * testing (see [isDue]'s note). Extracted so the anchor-merge rules can be
     * tested without Room or a Context.
     *
     *  - `neverDone = true` REPLACES any prior anchor and clears BOTH
     *    lastDoneMileage/lastDoneDate - it's the driver overriding a prior guess,
     *    not adding to it.
     *  - Supplying any concrete anchor (mileage/milesAgo/date) always clears
     *    [MaintenanceItem.neverDone] back to false - a driver correcting "never
     *    done" with a real anchor is un-confirming that fact.
     *  - Supplying ONLY a mileage anchor clears any stale [MaintenanceItem.lastDoneDate]
     *    (and vice versa), rather than pairing a fresh value with an old one from
     *    a different actual event. Supplying BOTH keeps both.
     */
    internal fun mergeBackfillAnchors(
        base: MaintenanceItem,
        mileage: Int?,
        milesAgo: Int?,
        date: Long?,
        neverDone: Boolean,
        currentMileage: Int,
    ): MaintenanceItem {
        if (neverDone) {
            return base.copy(neverDone = true, lastDoneMileage = null, lastDoneDate = null)
        }
        val resolvedMileage = resolveBackfillMileage(mileage, milesAgo, currentMileage)
        val hasMileage = resolvedMileage != null
        val hasDate = date != null
        return base.copy(
            neverDone = false,
            lastDoneMileage = when {
                hasMileage -> resolvedMileage
                hasDate -> null
                else -> base.lastDoneMileage
            },
            lastDoneDate = when {
                hasDate -> date
                hasMileage -> null
                else -> base.lastDoneDate
            },
        )
    }

    /**
     * Pure ranking logic behind [nextService]. `internal` for direct unit testing
     * (see [isDue]'s note).
     *
     * **Why there is no single cross-axis winner (rewritten, see the old design's
     * bug below).** A prior version picked one axis per dual-axis item by
     * comparing `milesRemaining / intervalMiles` against `daysRemaining /
     * intervalMonths` - "percent of interval remaining" looked like a same-item,
     * axis-free normalization, but it algebraically reduces to
     * `milesUsed/daysElapsed >= intervalMiles/intervalDays`, i.e. comparing the
     * driver's actual driving PACE since last service against the interval's
     * designed pace. That is a rate estimate wearing a disguise, and Kevin
     * explicitly rejected rate estimation - so this axis pick is gone, and with
     * it any notion of a single "winner" across items measured in different
     * units. There is no unit-free way to say a mileage remaining is "sooner"
     * than a time remaining without inventing a rate.
     *
     * **The bug this also fixes.** The old code additionally picked
     * `milesWinner ?: daysWinner` - ANY miles-tagged candidate beat EVERY
     * days-tagged candidate regardless of magnitude, so an item due in 29,990
     * miles could bury an item due tomorrow just because the buried item's own
     * dual-axis resolved to DAYS. Reporting both axes independently makes that
     * class of bug structurally impossible: nothing is ever discarded for being
     * the "wrong" unit.
     *
     * Rules:
     *  - Only anchored, not-yet-due items are candidates (due items already need
     *    attention now, not "next"; unknown items can't be ranked at all).
     *  - A dual-axis item (both intervalMiles+lastDoneMileage AND
     *    intervalMonths+lastDoneDate present) is evaluated on BOTH axes and may
     *    legitimately be the leader in both [NextService.byMiles] and
     *    [NextService.byTime] at once - it is never collapsed onto one axis.
     *  - [NextService.byMiles] = the candidate with the smallest milesRemaining
     *    across all candidates that have a mileage anchor; same for
     *    [NextService.byTime] on daysRemaining. No cross-axis comparison ever
     *    happens.
     *  - [odometerBaseline] == 0 means the mileage estimate is meaningless (the
     *    driver has never given a real reading): [NextService.byMiles] is always
     *    null in that case ([NextService.odometerUnset] records why), but
     *    [NextService.byTime] is computed normally.
     */
    internal fun computeNextService(
        items: List<MaintenanceItem>,
        currentMileage: Int,
        now: Long,
        odometerBaseline: Int,
    ): NextService? {
        val odometerUnset = odometerBaseline == 0
        val unknown = items.filter { isUnknown(it) }
        val candidates = items.filter { !isUnknown(it) && !isDue(it, currentMileage, odometerUnset, now) }

        var byMiles: ServiceCandidate? = null
        var byTime: ServiceCandidate? = null

        for (item in candidates) {
            if (!odometerUnset && item.intervalMiles != null && item.lastDoneMileage != null) {
                val milesRemaining = (item.intervalMiles - (currentMileage - item.lastDoneMileage)).toLong()
                val currentBest = byMiles?.remaining ?: Long.MAX_VALUE
                if (milesRemaining < currentBest) {
                    byMiles = ServiceCandidate(item.serviceName, milesRemaining, ScheduleUnit.MILES)
                }
            }
            if (item.intervalMonths != null && item.lastDoneDate != null) {
                val intervalMs = item.intervalMonths.toLong() * MONTH_MS
                val elapsedMs = now - item.lastDoneDate
                val daysRemaining = (intervalMs - elapsedMs) / DAY_MS
                val currentBest = byTime?.remaining ?: Long.MAX_VALUE
                if (daysRemaining < currentBest) {
                    byTime = ServiceCandidate(item.serviceName, daysRemaining, ScheduleUnit.DAYS)
                }
            }
        }

        // null is reserved for "no schedule at all" (a fresh install / nothing
        // logged yet). A car with a fully-logged, well-used schedule where
        // every anchored item has already crossed its threshold is a DIFFERENT
        // state (see NextService.allDue's doc) - it must not collapse onto the
        // same null the caller uses to say "register the car or log a service".
        if (items.isEmpty()) return null

        return NextService(
            byMiles = byMiles,
            byTime = byTime,
            unknownCount = unknown.size,
            unknownNames = unknown.map { it.serviceName },
            odometerUnset = odometerUnset,
            allDue = byMiles == null && byTime == null && unknown.isEmpty(),
        )
    }

    /**
     * Formats one [ServiceCandidate.remaining] value into the spoken fragment
     * `get_next_service` embeds mid-sentence, e.g. "50 miles" or "1 day" -
     * `internal` for direct unit testing (see [isDue]'s note).
     *
     * Miles round to the nearest 50 HERE, at presentation only - the exact
     * value keeps flowing everywhere else ([nextService]'s real return value,
     * the DUE tab, any future caller); "about 2950 miles" pairs a hedge with a
     * suspiciously precise figure, "about 2950 miles" -> "about 3000 miles"
     * reads like an estimate that's actually being spoken as one. Days are
     * never rounded - the day count is small enough that rounding it away
     * would make a real "3 days" read as a nonsensical "about 0 days".
     *
     * Miles can never be 0 or negative here: [computeNextService] only ranks
     * candidates that are `!isDue`, and the mileage-due check is a `>=`
     * threshold, so at least 1 mile always remains on that axis - there is no
     * zero-miles case to handle. Days CAN legitimately be 0: `daysRemaining` is
     * integer division of milliseconds, so anything under 24 hours floors to
     * 0, reachable on the last day before a time-based service. This function
     * reports that as the bare word "today" (not "0 days") - callers embed it
     * into whichever sentence shape fits (e.g. "X is due today." rather than
     * "X is next, about today out.") since the surrounding sentence differs by
     * call site.
     */
    /** Comma-grouped digits. Mirrors `ui/fleet/FleetRows.groupThousands` for `Long`. */
    private fun groupThousandsLong(n: Long): String =
        n.toString().reversed().chunked(3).joinToString(",").reversed()

    internal fun formatRemaining(remaining: Long, unit: ScheduleUnit): String = when (unit) {
        ScheduleUnit.MILES -> when {
            // Under 50 miles, report the exact figure: rounding would speak "50
            // miles" to a driver with 10 left, five times the real margin.
            // Above that, round DOWN to a multiple of 50, never to nearest -
            // round-to-nearest sends 75 up to "100 miles", which overstates, and
            // overstating remaining service life is the one direction this must
            // never be wrong in. Flooring only ever tells the driver they have
            // less room than they do, which costs an early oil change at worst.
            // Thousands are grouped because this string sits beside
            // groupThousands()-formatted figures in the same row ("every 7,500
            // mi - last at 73,500"), and an instrument that writes 7400 next to
            // 7,500 looks broken. Observed on device 2026-08-07.
            remaining < 50L -> if (remaining == 1L) "1 mile" else "$remaining miles"
            else -> "${groupThousandsLong((remaining / 50) * 50)} miles"
        }
        ScheduleUnit.DAYS -> when {
            remaining <= 0L -> "today"
            remaining == 1L -> "1 day"
            else -> "$remaining days"
        }
    }

    // --- Seeding -------------------------------------------------

    /**
     * Creates a placeholder [Vehicle] for an id we haven't seen before. **Never
     * persisted, for ANY id (ticket 13, 2026-08-15,
     * `.scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md`).**
     *
     * A car exists when the driver says so - through [registerDirect],
     * [addVehicle], [createCarProfile] or [correctVehicle], every one of them an
     * explicit driver action - not when a lookup against an id we've never seen
     * happens to miss. Before this fix, EVERY caller of [vehicleFor] (and so
     * [currentVehicle]) fell back to this function persisting a blank row on any
     * miss, and [com.kevin.legion.vehicle.TelemetryRecorder.run] calls
     * [currentVehicle] every 30 seconds while driving - the highest-frequency
     * caller of this whole path. Per the ticket's resolution, that combination is
     * what actually destroyed a real car's identity and odometer on Kevin's
     * phone: a transient miss against a row that DID exist a moment before
     * persisted a blank placeholder over it via [VehicleDao.upsert]'s whole-row
     * REPLACE, silently, with no driver action anywhere in the chain - the exact
     * writer was not pinned down beyond that in the ticket itself, but removing
     * this function's ability to persist closes every variant of it at once, which
     * is why Kevin chose this fix over a narrower one. Returning an unpersisted
     * object still gives every caller a usable placeholder to read from; it just
     * never becomes a row nobody asked for.
     *
     * A placeholder states nothing it does not know. Until 2026-08-03 the
     * [DEFAULT_VEHICLE_ID] branch seeded a specific, fully-specified car - a 1998
     * Jeep Cherokee named "Midnight", Midnight AI's own car, carried over by the
     * port - which meant a fresh install asserted as fact that the driver owned a
     * vehicle nobody had told it about.
     *
     * That is not cosmetic. `default` is a shared sentinel id, so the fabricated
     * Cherokee became the row that real imported history attached itself to when
     * the Midnight AI import collided with it (see
     * [com.kevin.legion.data.MidnightImport.SENTINEL_VEHICLE_ID]): the database
     * ended up asserting a 1998 Jeep with a 2020 Mitsubishi's VIN. A blank
     * placeholder is still a placeholder and can still be collided with, but it
     * cannot contribute a false claim of its own to the wreck.
     *
     * The sentinel-only never-persist carve-out this function used to have
     * (2026-08-12) is now the rule for every id, not a special case for one of
     * them - collapsed here, its reasoning kept above rather than deleted.
     */
    private fun seedVehicle(vehicleId: String): Vehicle =
        Vehicle(
            obdMac = vehicleId,
            // Blank, not the retired "this car" sentinel (ticket 04's label rule) - a placeholder
            // states nothing it does not know, and "unnamed" is exactly the state [label] already
            // words correctly. A magic string here is exactly what let this leak past whichever
            // surface's own filter forgot it - see the ticket for the two archived rows it wrote
            // permanently before this fix.
            name = "",
            make = "",
            model = "",
            year = 0,
            personaPrompt = "",
        )

    /**
     * One-time cleanup for the retired `"this car"` sentinel (ticket 04's label rule,
     * `.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`): a data write through
     * [com.kevin.legion.data.local.VehicleDao.clearThisCarSentinel], not a migration - `name` has
     * always been a plain TEXT column with no CHECK constraint, so this changes no schema. The two
     * rows carrying it are both archived and invisible today, which is exactly why they would
     * otherwise survive to trap the next label surface that forgets to filter it. Idempotent (a
     * no-op UPDATE once no row matches), so [com.kevin.legion.MidnightApplication] runs it
     * unconditionally on every process start rather than tracking a run-once flag.
     */
    suspend fun clearThisCarSentinel(context: Context) {
        CarDatabase.getDatabase(context).vehicleDao().clearThisCarSentinel(System.currentTimeMillis())
    }
}
