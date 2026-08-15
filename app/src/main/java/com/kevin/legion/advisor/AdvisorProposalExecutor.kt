package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.goals.GoalController
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.location.ReminderController
import com.kevin.legion.meals.MealController
import com.kevin.legion.notes.NotesController
import com.kevin.legion.sleep.SleepController
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.workouts.WorkoutController
import org.json.JSONException
import org.json.JSONObject

/**
 * Executes an advisor's STORED proposal against the record, and only the stored one - ticket 03
 * answer call 1 ("the live model never supplies the values - it only names a proposal - so
 * nothing can drift"), built for ticket 18. [AdvisorBriefs] declares which
 * [AdvisorBrief.writableOps] each aspect may propose; this object is where that allowlist is
 * actually ENFORCED against a raw `proposalJson` string, and where each op name maps to the ONE
 * existing write path it stands for - "set_meal_target" here calls the exact same
 * [MealController.setTarget] the direct-dictation `set_meal_target` live tool does, never a
 * bespoke write of its own. Two write paths, one truth, same "tool layer and screen cannot drift"
 * posture [GoalController]'s own class doc already states for goals specifically.
 *
 * **Every op below is an INTENTION** (goal, target, plan, maintenance interval, reminder), never
 * an ACTUAL - this file simply never imports `logMeal`/`logWorkoutSet`/`logBodyweight`/`logSleep`/
 * `logServiceDirect`, any delete, or any recategorise, so there is no runtime branch that could
 * ever be wired to one by a future edit that only reads the allowlist and assumes it's enough.
 * [AdvisorAspect.HOME]'s brief carries `writableOps = emptySet()` (ticket 09), so [execute] refuses
 * every op for it before ever reaching the `when` below - HOME hands a concrete ask back to the
 * aspect advisor that owns it, it never writes.
 */
object AdvisorProposalExecutor {

    /** What running one proposal did. [message] is what `accept_proposal`'s tool response hands
     * back to the live model to speak - true either way, since a refusal must be said in words
     * (CLAUDE.md §4 rule 7's "said in words" discipline applied to a rejected write), never a
     * silent no-op the driver has no way to notice.
     *
     * [WriteFailed] exists because three of the controllers below ([WorkoutController.generatePlan],
     * [SleepController.setTarget], [ReminderController.add]) signal failure by RETURNING A SPOKEN
     * FAILURE SENTENCE as a normal `String` rather than throwing - nothing was written, but the
     * string alone is indistinguishable from a success message without string-matching it, which
     * would be fragile and rot the moment a controller's wording changes. So this file never reads
     * the message to decide success; instead each op that wraps one of those three controllers reads
     * its OWN write back through the DAO afterward and reports [WriteFailed] only when that read-back
     * proves nothing landed. `accept_proposal` (`service/LiveToolbox.kt`) must treat [WriteFailed]
     * like [Refused] for the row's lifecycle - leave it retryable, never mark it `accepted`. */
    sealed class ExecuteResult {
        data class Ok(val message: String) : ExecuteResult()
        data class Refused(val message: String) : ExecuteResult()
        data class WriteFailed(val message: String) : ExecuteResult()
    }

    /**
     * Parses [proposalJson], checks its `op` against [brief]'s [AdvisorBrief.writableOps], and -
     * only if it is on that aspect's own allowlist - runs the matching write. [brief]'s own
     * [AdvisorBrief.aspect] is what every op below writes AGAINST (e.g. [setGoal] always uses
     * `brief.aspect.key`, never a value read out of the proposal itself) - a proposal claiming a
     * different aspect than the brief that authored it is not trusted, so a BIO advisor's proposal
     * can never land a goal under `cred` merely by saying so in its own JSON.
     */
    suspend fun execute(context: Context, brief: AdvisorBrief, proposalJson: String): ExecuteResult {
        val obj = try {
            JSONObject(proposalJson)
        } catch (e: JSONException) {
            return ExecuteResult.Refused(
                "That proposal didn't parse into something I can act on - let me ask again and get a cleaner one.",
            )
        }
        val op = obj.optString("op").takeIf { it.isNotBlank() }
            ?: return ExecuteResult.Refused("That proposal doesn't name what to write - let me re-check and propose again.")

        if (op !in brief.writableOps) {
            return ExecuteResult.Refused(
                "That's not something the ${brief.aspect.key} advisor is allowed to write - \"$op\" isn't on its list.",
            )
        }

        return when (op) {
            AdvisorBriefs.OP_SET_GOAL -> setGoal(context, brief, obj)
            "set_meal_target" -> setMealTarget(context, obj)
            "set_sleep_target" -> setSleepTarget(context, obj)
            "create_workout_plan" -> createWorkoutPlan(context, obj)
            "set_budget" -> setBudget(context, obj)
            "set_maintenance_item" -> setMaintenanceItem(context, obj)
            "set_reminder" -> setReminder(context, obj)
            "add_task" -> addTask(context, obj)
            else -> ExecuteResult.Refused("I don't know how to write a \"$op\" proposal.")
        }
    }

    // --- goal (BIO/LOG/FLEET/CRED) -------------------------------------------------------------

    private suspend fun setGoal(context: Context, brief: AdvisorBrief, obj: JSONObject): ExecuteResult {
        val statement = obj.optString("statement").trim()
        if (statement.isBlank()) return ExecuteResult.Refused("That proposal didn't include a goal statement.")
        val targetValue = if (obj.has("targetValue") && !obj.isNull("targetValue")) obj.optDouble("targetValue") else null
        val unit = obj.optString("unit").trim().takeIf { it.isNotBlank() }
        val metricKey = obj.optString("metricKey").trim().takeIf { it.isNotBlank() }
        val deadlineEpoch = if (obj.has("deadlineEpoch") && !obj.isNull("deadlineEpoch")) obj.optLong("deadlineEpoch") else null

        return when (
            val outcome = GoalController.setGoal(
                context, aspect = brief.aspect.key, statement = statement, targetValue = targetValue,
                unit = unit, metricKey = metricKey, deadlineEpoch = deadlineEpoch,
            )
        ) {
            is GoalController.SetOutcome.Created -> ExecuteResult.Ok("Goal set: $statement.")
            is GoalController.SetOutcome.Revised -> ExecuteResult.Ok("Updated the goal: $statement.")
            is GoalController.SetOutcome.Unchanged -> ExecuteResult.Ok("That's already the current goal - nothing changed.")
        }
    }

    // --- BIO -------------------------------------------------------------------------------------

    private suspend fun setMealTarget(context: Context, obj: JSONObject): ExecuteResult {
        if (!obj.has("caloriesKcal")) return ExecuteResult.Refused("That proposal didn't include a calorie target.")
        val calories = obj.optInt("caloriesKcal")
        val protein = obj.optDouble("proteinG", 0.0)
        val carbs = obj.optDouble("carbsG", 0.0)
        val fat = obj.optDouble("fatG", 0.0)
        return ExecuteResult.Ok(MealController.setTarget(context, calories, protein, carbs, fat))
    }

    /** [SleepController.setTarget] rejects <=0 / >24h / NaN / Infinite by RETURNING a spoken
     * failure sentence rather than throwing (a proposal built from a malformed `targetHours` -
     * e.g. `optDouble` on missing/non-numeric JSON - can reach here as NaN). That is input sanity,
     * not a safe-range floor: nothing here clamps toward a "safe" number, it only refuses one that
     * is not a real duration at all - the constraint against safety floors (calorie minimums, rate
     * caps) is about substituting a value Kevin didn't ask for, which this never does. Verified,
     * not string-matched: read the target back and require its `updatedAt` be no older than the
     * call, so a write that silently did nothing is caught even if the controller's wording drifts. */
    private suspend fun setSleepTarget(context: Context, obj: JSONObject): ExecuteResult {
        if (!obj.has("targetHours")) return ExecuteResult.Refused("That proposal didn't include a target.")
        val now = System.currentTimeMillis()
        val message = SleepController.setTarget(context, obj.optDouble("targetHours"), now)
        val landed = CarDatabase.getDatabase(context).sleepTargetDao()
            .currentTarget(com.kevin.legion.meals.dayStartEpoch(now))
        return if (landed != null && landed.updatedAt >= now) {
            ExecuteResult.Ok(message)
        } else {
            ExecuteResult.WriteFailed(message)
        }
    }

    /** [WorkoutController.generatePlan] itself calls a sub-agent (`WorkoutPlanAgent.write`) to draft
     * the plan's shape - the SAME behaviour the direct-dictation `create_workout_plan` live tool
     * already has today. The proposal carries only `goal` (the prose the advisor and driver already
     * discussed and agreed on); nothing here supplies session/rep/set numbers itself, so this is
     * still "execute the stored proposal", not the live model inventing plan values. */
    private suspend fun createWorkoutPlan(context: Context, obj: JSONObject): ExecuteResult {
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return ExecuteResult.Refused("That proposal didn't say what the plan is for.")
        // generatePlan returns "I couldn't put a plan together just now..." as a normal String,
        // never a thrown exception, when its sub-agent write comes back null (network/rate-limit) -
        // nothing is written in that case. Verified by read-back, not by matching that sentence:
        // require the current plan's updatedAt be no older than this call.
        val now = System.currentTimeMillis()
        val message = WorkoutController.generatePlan(context, goal)
        val landed = CarDatabase.getDatabase(context).workoutPlanDao()
            .currentPlan(com.kevin.legion.workouts.weekStartEpoch(now))
        return if (landed != null && landed.updatedAt >= now) {
            ExecuteResult.Ok(message)
        } else {
            ExecuteResult.WriteFailed(message)
        }
    }

    // --- CRED ------------------------------------------------------------------------------------

    /** `amountCents` is a `Long`, never a `Double` - CLAUDE.md §4 rule 3, unmodified by the fact
     * that this money value came from an advisor rather than a statement/receipt ingestion. */
    private suspend fun setBudget(context: Context, obj: JSONObject): ExecuteResult {
        val category = obj.optString("category").trim()
        if (category.isBlank()) return ExecuteResult.Refused("That proposal didn't name a category.")
        if (!obj.has("amountCents")) return ExecuteResult.Refused("That proposal didn't include an amount.")
        val cents = obj.optLong("amountCents")
        LedgerController.setBudget(
            context = context,
            entity = LedgerEntity.US,
            category = category,
            month = java.time.YearMonth.now(java.time.ZoneId.systemDefault()),
            amountCents = cents,
        )
        return ExecuteResult.Ok("Budget set: ${formatMoney(cents, LedgerEntity.US.currency)} a month for $category.")
    }

    // --- FLEET -----------------------------------------------------------------------------------

    /** Writes an interval only - never `lastDoneMileage`/`lastDoneDate`/`neverDone`, which are
     * claims about work actually performed (an ACTUAL) and stay off this allowlist entirely. Scoped
     * to [VehicleController.currentVehicle] - FLEET's own digest ([com.kevin.legion.advisor.digest
     * .FleetDigestBuilder]) is built against the same active car, so the proposal the advisor saw
     * and the vehicle this writes against are guaranteed to match. */
    private suspend fun setMaintenanceItem(context: Context, obj: JSONObject): ExecuteResult {
        val serviceName = obj.optString("serviceName").trim()
        if (serviceName.isBlank()) return ExecuteResult.Refused("That proposal didn't name a service.")
        val intervalMiles = if (obj.has("intervalMiles") && !obj.isNull("intervalMiles")) obj.optInt("intervalMiles") else null
        val intervalMonths = if (obj.has("intervalMonths") && !obj.isNull("intervalMonths")) obj.optInt("intervalMonths") else null
        if (intervalMiles == null && intervalMonths == null) {
            return ExecuteResult.Refused("That proposal didn't include an interval - miles or months.")
        }

        val vehicle = VehicleController.currentVehicle(context)
        val db = CarDatabase.getDatabase(context)
        val existing = db.maintenanceItemDao().get(vehicle.obdMac, serviceName)
        val item = (existing ?: MaintenanceItem(vehicleId = vehicle.obdMac, serviceName = serviceName)).copy(
            intervalMiles = intervalMiles ?: existing?.intervalMiles,
            intervalMonths = intervalMonths ?: existing?.intervalMonths,
        )
        db.maintenanceItemDao().upsert(item)

        val everyPhrase = listOfNotNull(
            item.intervalMiles?.let { "$it miles" },
            item.intervalMonths?.let { "$it months" },
        ).joinToString(" / ")
        return ExecuteResult.Ok("Set $serviceName on the ${VehicleController.displayLabel(vehicle)} to every $everyPhrase.")
    }

    // --- LOG -------------------------------------------------------------------------------------

    /** [ReminderController.add] can still fail past this file's own blank checks: its private
     * `normalizeLabel` strips filler words ("location", "place", "spot", "address") and can reduce
     * a place like "location" to blank on its own, at which point `add` RETURNS "I need both a
     * place and what to remind you about." rather than throwing - nothing is written. Verified by
     * read-back through [ReminderController.activeFor] (which applies the SAME `normalizeLabel`,
     * so it looks under the label `add` actually tried to use), not by matching that sentence. */
    private suspend fun setReminder(context: Context, obj: JSONObject): ExecuteResult {
        val place = obj.optString("place").trim()
        val text = obj.optString("text").trim()
        if (place.isBlank() || text.isBlank()) {
            return ExecuteResult.Refused("That proposal was missing a place or what to remind about.")
        }
        val now = System.currentTimeMillis()
        val message = ReminderController.add(context, place, text)
        val landed = ReminderController.activeFor(context, place).any { it.text == text && it.createdAt >= now }
        return if (landed) ExecuteResult.Ok(message) else ExecuteResult.WriteFailed(message)
    }

    /** `date` is optional and, unlike [ReminderController.add], never place-triggered - a plain
     * open task, matching LOG's own digest vocabulary ("open task" = no `startsAt`, no repeat). */
    private suspend fun addTask(context: Context, obj: JSONObject): ExecuteResult {
        val text = obj.optString("text").trim()
        if (text.isBlank()) return ExecuteResult.Refused("That proposal didn't say what the task is.")
        val list = NotesController.theList(context)
        val dateRaw = obj.optString("date").trim()
        val startsAt = if (dateRaw.isBlank()) {
            null
        } else {
            try {
                java.time.LocalDate.parse(dateRaw)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null // an unparseable date degrades to a dateless task rather than refusing the whole write.
            }
        }
        NotesController.addItemDue(context, list.id, text, startsAt)
        return ExecuteResult.Ok("Added: $text.")
    }
}
