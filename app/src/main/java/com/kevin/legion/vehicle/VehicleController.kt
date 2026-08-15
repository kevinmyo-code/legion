package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
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
    private val SERVICE_KEYWORDS = listOf(
        "Oil Change" to listOf("oil"),
        "Tire Rotation" to listOf("tire rotation", "tires rotated", "rotated the tires"),
        "Brake Pads" to listOf("brake pad", "brakes", "brake"),
        "Brake Fluid" to listOf("brake fluid"),
        "Air Filter" to listOf("air filter"),
        "Cabin Air Filter" to listOf("cabin filter", "cabin air filter"),
        "Spark Plugs" to listOf("spark plug"),
        "Coolant Flush" to listOf("coolant", "antifreeze"),
        "Transmission Fluid" to listOf("transmission fluid", "transmission"),
        "Battery" to listOf("battery"),
    )

    /**
     * Registers the car's year/make/model, triggers maintenance-interval lookup.
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
     */
    suspend fun registerDirect(context: Context, year: Int, make: String, model: String): String {
        if (year < 1900 || make.isBlank() || model.isBlank())
            return "I need a valid year, make, and model to register the car."
        val vehicleId = ActiveVehicle.current(context)
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val existing = dao.getByMac(vehicleId)
        val now = System.currentTimeMillis()
        val name = existing?.name?.takeIf { it.isNotBlank() && it != "this car" } ?: model
        val vehicle: Vehicle
        if (existing == null) {
            // Genuinely new row - nothing to preserve, a real INSERT.
            vehicle = Vehicle(
                obdMac = vehicleId,
                name = name,
                make = make,
                model = model,
                year = year,
                personaPrompt = "",
                onboarded = false,
                confirmed = true,
            )
            dao.upsert(vehicle)
        } else {
            // Existing row: a targeted identity write, not a rebuild - see the
            // function doc and VehicleDao.setIdentity for why.
            dao.setIdentity(vehicleId, year, make, model, existing.trim, name, now)
            vehicle = existing.copy(year = year, make = make, model = model, name = name, confirmed = true, updatedAt = now)
        }
        val found = applyServiceIntervals(context, vehicle)

        return if (found > 0) {
            "Got it, this is the $year $make $model now. Pulled up $found maintenance items so I can keep track of intervals."
        } else {
            "Got it, this is the $year $make $model now. Couldn't find a maintenance schedule online, but I'll track it as you log things."
        }
    }

    /**
     * Records the driver-reported odometer reading and resets the trip
     * accumulator. [vehicleId] is the fleet-wide-voice override (ticket 01,
     * "category B" stored-data tool) - null means the active car, unchanged.
     */
    suspend fun setOdometer(context: Context, miles: Int, vehicleId: String? = null): String {
        if (miles < 100 || miles > 999_999)
            return "That reading doesn't look right — odometer should be between 100 and 999,999 miles."
        val vehicle = vehicleFor(context, vehicleId)
        val now = System.currentTimeMillis()
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
            return "I don't have this car on file yet, so I can't attach that reading to it. " +
                "Tell me the year, make and model first and I'll record $miles miles straight after."
        }
        return listOf(
            "Got it, $miles on the clock. I'll keep track from here.",
            "Noted, $miles miles. Let's see how long till the next thing breaks.",
            "$miles it is. Filed away.",
        ).random()
    }

    /**
     * Normalises a free-text service name (from Gemini, either a spoken log or a
     * looked-up interval) onto the app's canonical vocabulary so the same real
     * service always lands on the same [MaintenanceItem] row. Falls back to
     * titlecasing the raw name when it matches none of the 9 canonical keywords -
     * that fallback can still vary phrasing-to-phrasing (accepted; see
     * [refreshServiceIntervals]'s doc for the bug this specifically closes).
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
            ?: serviceName.trim().replaceFirstChar { it.titlecase() }
    }

    /**
     * Logs completed maintenance and clears the item's "due" status.
     * [vehicleId] is the fleet-wide-voice override (ticket 01) - null means
     * the active car, unchanged.
     */
    suspend fun logServiceDirect(context: Context, serviceName: String, vehicleId: String? = null): String {
        val canonical = canonicalizeServiceName(serviceName)
        val db = CarDatabase.getDatabase(context)
        val vehicle = vehicleFor(context, vehicleId)
        val mileage = currentMileage(vehicle)
        val now = System.currentTimeMillis()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = vehicle.obdMac, serviceName = canonical, mileage = mileage, date = now))
        val existing = db.maintenanceItemDao().get(vehicle.obdMac, canonical)
        // neverDone MUST be cleared here, not just in the backfill path. Marking
        // something never-done and then actually doing it is the normal sequence,
        // and isDue checks neverDone first and returns true unconditionally - so
        // leaving it set left a just-completed service reading as permanently
        // overdue, forever, re-injected into the live prompt every turn.
        db.maintenanceItemDao().upsert(
            (existing ?: MaintenanceItem(vehicleId = vehicle.obdMac, serviceName = canonical))
                .copy(lastDoneMileage = mileage, lastDoneDate = now, neverDone = false)
        )
        return listOf(
            "Nice, logged the $canonical at $mileage miles. I'll let you know when it's due again.",
            "Got it — $canonical done at $mileage. One less thing to worry about.",
            "Logged: $canonical at $mileage miles.",
        ).random()
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
    ): String {
        if (!neverDone && mileage == null && milesAgo == null && date == null) {
            return "I need something to go on — a mileage, how long ago, a date, or that it's never been done."
        }
        val canonical = canonicalizeServiceName(serviceName)
        val db = CarDatabase.getDatabase(context)
        val vehicle = vehicleFor(context, vehicleId)
        val existing = db.maintenanceItemDao().get(vehicle.obdMac, canonical)
        val base = existing ?: MaintenanceItem(vehicleId = vehicle.obdMac, serviceName = canonical)

        val updated = mergeBackfillAnchors(base, mileage, milesAgo, date, neverDone, currentMileage(vehicle))
        db.maintenanceItemDao().upsert(updated)

        return if (neverDone) {
            "Got it, marking $canonical as never done — I'll flag it as overdue."
        } else {
            listOf(
                "Noted — $canonical, backfilled from what you remember.",
                "Got it, filed $canonical into the record.",
                "Logged $canonical from memory.",
            ).random()
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
    ): String {
        if (make.isBlank() || model.isBlank()) return "I need at least a make and model to add a car."
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val label = listOf(year.takeIf { it >= 1900 }?.toString().orEmpty(), make, model, trim)
            .filter { it.isNotBlank() }.joinToString(" ")

        // Adding the car you already have on file is a correction, not a second
        // car. Say so rather than quietly growing a duplicate fleet.
        dao.getAllIncludingArchived().firstOrNull {
            it.make.equals(make, true) && it.model.equals(model, true) &&
                (year < 1900 || it.year == year)
        }?.let {
            return "You've already got a ${displayLabel(it)} on file. " +
                "If that one's wrong, tell me to correct it instead of adding another."
        }

        val vehicle = Vehicle(
            obdMac = ActiveVehicle.newVehicleId(),
            name = name.ifBlank { model },
            make = make,
            model = model,
            year = year,
            trim = trim,
            personaPrompt = "",
            odometerBaseline = 0,
            odometerBaselineAt = 0L,
            tripMilesSinceBaseline = 0.0,
            onboarded = false,
            confirmed = true,
        )
        dao.upsert(vehicle)
        val found = applyServiceIntervals(context, vehicle)
        val active = currentVehicle(context)
        return buildString {
            append("Added the $label. ")
            if (found > 0) append("Pulled up $found maintenance items for it. ")
            append("You're still on the ${displayLabel(active)} - say switch to the $model when you want me on that one.")
        }
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
        if (updated == existing) return "Nothing to change there - it's already a ${displayLabel(existing)}."
        // Targeted write (ticket 13): identity columns only, via
        // VehicleDao.setIdentity - the odometer, persona and archive state on
        // this row ride along untouched instead of round-tripping through a
        // whole-row upsert of a struct built from a read that could be stale.
        dao.setIdentity(vehicleId, updated.year, updated.make, updated.model, updated.trim, updated.name, System.currentTimeMillis())
        // Only re-pull intervals when the actual car changed, not on a rename.
        val identityChanged = updated.year != existing.year ||
            !updated.make.equals(existing.make, true) || !updated.model.equals(existing.model, true)
        val found = if (identityChanged) applyServiceIntervals(context, updated) else 0
        return buildString {
            append("Fixed - that one's a ${displayLabel(updated)} now. Its history stayed with it. ")
            if (found > 0) append("Refreshed $found maintenance items for the corrected car.")
        }
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
        if (vehicle.archived) return "The ${displayLabel(vehicle)} is archived - want me to bring it back first?"
        ActiveVehicle.select(context, vehicleId)
        return "You're on the ${displayLabel(vehicle)} now."
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

    fun displayLabel(vehicle: Vehicle): String =
        listOf(
            vehicle.year.takeIf { it > 0 }?.toString().orEmpty(),
            vehicle.make, vehicle.model, vehicle.trim,
        ).filter { it.isNotBlank() }.joinToString(" ")

    /** Current odometer estimate: driver-reported baseline plus GPS trip distance since. */
    fun currentMileage(vehicle: Vehicle): Int =
        vehicle.odometerBaseline + vehicle.tripMilesSinceBaseline.roundToInt()

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
     */
    suspend fun dueItems(context: Context, vehicle: Vehicle): List<MaintenanceItem> {
        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val mileage = currentMileage(vehicle)
        val now = System.currentTimeMillis()
        return items.filter { isDue(it, mileage, now) }
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

    /**
     * Looks up typical manufacturer maintenance intervals for any vehicle that
     * hasn't been onboarded yet (seeded by [seedVehicle] or [handleRegister]).
     * Safe to call repeatedly - a no-op once onboarded. Meant to run once at
     * service startup.
     */
    suspend fun onboardPendingVehicles(context: Context) {
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        for (vehicle in dao.getAll()) {
            if (vehicle.onboarded || vehicle.make.isBlank() || vehicle.model.isBlank()) continue
            applyServiceIntervals(context, vehicle)
        }
    }

    // --- Registration -------------------------------------------------

    /** Online lookup of default maintenance intervals; persists them and marks [vehicle] onboarded. Returns the count found. */
    private suspend fun applyServiceIntervals(context: Context, vehicle: Vehicle): Int {
        // Canonicalize at SEED time, not just on refresh and voice writes. The
        // seed used to store Gemini's raw phrasing ("Engine Air Filter"), while
        // every later write canonicalized ("Air Filter") and then looked the row
        // up by exact name - missing it, and creating a second, interval-less row
        // holding the anchor. The real row kept its interval and sat in UNKNOWN
        // forever. serviceName is half the primary key, so both sides have to
        // agree on it or nothing ever matches.
        val items = canonicalizeAndDedupe(lookupServiceIntervals(context, vehicle))
        val db = CarDatabase.getDatabase(context)
        if (items.isNotEmpty()) db.maintenanceItemDao().insertAll(items)
        // Targeted write (ticket 13): flips onboarded only, via
        // VehicleDao.markOnboarded. Every caller of this function already
        // guarantees vehicle's row exists (a fresh insert just above in
        // registerDirect/addVehicle, or a row pulled straight from getAll() in
        // onboardPendingVehicles), so this is never a no-op in practice.
        db.vehicleDao().markOnboarded(vehicle.obdMac, System.currentTimeMillis())
        return items.size
    }

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

    /**
     * Re-runs [lookupServiceIntervals] and updates the interval fields on the
     * vehicle's existing rows, WITHOUT touching what the driver has already
     * told us (lastDoneMileage/lastDoneDate/neverDone). Deliberately does not
     * use [MaintenanceItemDao.insertAll] - its IGNORE conflict strategy would
     * silently no-op on every row that already exists, which is exactly the
     * common case here (refreshing an already-onboarded car). Returns the
     * count of items written (created or updated).
     *
     * **Names are canonicalized before the lookup and the upsert both.** Gemini's
     * lookup prompt does not constrain it to the app's vocabulary, so it can come
     * back with "Engine Oil & Filter Change" for what [logServiceDirect] already
     * filed as "Oil Change" - an exact-name match against the existing row then
     * fails, INSERTS a second anchor-less row for the same real service, and that
     * duplicate silently pollutes [unknownItems] (and compounds on every future
     * refresh, since it never matches either). Running every looked-up name
     * through [canonicalizeServiceName] before both the `dao.get` lookup and the
     * upsert puts it back on the same key spoken logs use, for the 9 canonical
     * services. Names outside that vocabulary still fall back to titlecase and
     * can still vary call-to-call - accepted, not fixed by this pass.
     */
    suspend fun refreshServiceIntervals(context: Context, vehicle: Vehicle): Int {
        val looked = lookupServiceIntervals(context, vehicle)
        if (looked.isEmpty()) return 0
        val dao = CarDatabase.getDatabase(context).maintenanceItemDao()
        val seenCanonicalNames = mutableSetOf<String>()
        var written = 0
        for (item in looked) {
            val canonicalName = canonicalizeServiceName(item.serviceName)
            // Two looked-up items canonicalizing to the same name (e.g. Gemini
            // returning both "Oil Change" and "Engine Oil & Filter Change" in one
            // response) would otherwise upsert twice for one logical service -
            // keep the first, skip the rest.
            if (!seenCanonicalNames.add(canonicalName)) continue
            val existing = dao.get(vehicle.obdMac, canonicalName)
            val merged = if (existing != null) {
                existing.copy(intervalMiles = item.intervalMiles, intervalMonths = item.intervalMonths)
            } else {
                item.copy(serviceName = canonicalName)
            }
            dao.upsert(merged)
            written++
        }
        return written
    }

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
     */
    private suspend fun lookupServiceIntervals(context: Context, vehicle: Vehicle): List<MaintenanceItem> {
        val trim = vehicle.trim.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val prompt = "Use search to find the manufacturer's published NORMAL scheduled maintenance " +
            "intervals - the standard/light-duty schedule, NOT the severe or heavy-duty one - for a " +
            "${vehicle.year} ${vehicle.make} ${vehicle.model}$trim. Respond with ONLY a raw JSON array " +
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
        } ?: return emptyList()

        return parseIntervals(vehicle.obdMac, raw)
    }

    private fun parseIntervals(vehicleId: String, raw: String): List<MaintenanceItem> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return emptyList()

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
            emptyList()
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
     */
    internal fun isDue(item: MaintenanceItem, currentMileage: Int, now: Long): Boolean {
        if (item.neverDone) return true
        if (item.lastDoneMileage == null && item.lastDoneDate == null) return false
        val mileageDue = item.intervalMiles != null && item.lastDoneMileage != null &&
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
        val candidates = items.filter { !isUnknown(it) && !isDue(it, currentMileage, now) }

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
            name = "this car",
            make = "",
            model = "",
            year = 0,
            personaPrompt = "",
        )
}
