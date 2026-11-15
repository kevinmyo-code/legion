package com.kevin.legion.vehicle

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The hands path for "which car is the OBD dongle in" (CLAUDE.md section 7, ADR 0035): one
 * notification whose buttons call [DongleCarController] directly through
 * [DongleCarActionReceiver], and whose body opens the Cars screen, where the same question has a
 * row per car and "a new car".
 *
 * Android shows at most three buttons, so: up to two cars, then "New car" (a VIN to look up and
 * two or fewer cars) or "Other" (opens the Cars screen). It is posted on every held or assumed
 * connect whether or not the ask was also spoken - a question that holds data needs an answer
 * path that survives a closed mic - which is why the spoken raise sets
 * `callerPostsItsOwnNotification`.
 *
 * Compulsion test (CLAUDE.md section 7): (a) anchored to the dongle connecting and what it read,
 * (b) actionable now (a button per car), (c) never about his absence or the app, (d) the Fleet
 * switch silences the spoken half; the notification itself goes once he answers.
 */
object DongleCarNotifier {
    const val CHANNEL_ID = "obd_car_channel"
    private const val NOTIFICATION_ID = 0x0BD1
    private const val MAX_ACTIONS = 3

    fun ask(context: Context, decision: DongleCar.Held, labels: Map<String, String>) {
        val cars = labels.entries.toList()
        val buttons = mutableListOf<Pair<String, PendingIntent>>()
        // The subject rides in every button, so a tap after the dongle disconnects (he has parked)
        // still answers this connection's held readings.
        val subject = decision.recordingId
        cars.take(2).forEach { (id, label) -> buttons += label to answerIntent(context, id, subject, false) }
        val canLookUp = decision.reason == DongleCar.HeldReason.UNKNOWN_VIN
        buttons += if (canLookUp && cars.size <= 2) {
            "New car" to newCarIntent(context, subject)
        } else {
            "Other" to openCars(context)
        }
        post(
            context,
            title = "Which car is the OBD dongle in?",
            body = DongleCarController.question(decision, labels),
            buttons = buttons,
            silent = false,
        )
    }

    fun assumed(context: Context, decision: DongleCar.OnCar, labels: Map<String, String>) {
        val name = labels[decision.carId] ?: "the car you last confirmed"
        val others = labels.filterKeys { it != decision.carId }.entries.toList()
        // The assumed car's id is the subject: after a disconnect it names the held window.
        val subject = decision.carId
        val buttons = mutableListOf("Yes, $name" to answerIntent(context, decision.carId, subject, false))
        if (others.size <= 2) {
            others.forEach { (id, label) -> buttons += label to answerIntent(context, id, subject, false) }
        } else {
            buttons += others.first().value to answerIntent(context, others.first().key, subject, false)
            buttons += "Other" to openCars(context)
        }
        post(
            context,
            title = "Recording as the $name (assumed)",
            body = "The OBD dongle could not read this car's VIN, so its readings go to the $name, the car " +
                "you last said it was in. Not right? Tap the right car.",
            buttons = buttons,
            silent = true,
        )
    }

    fun overrodePick(context: Context, decision: DongleCar.OnCar, labels: Map<String, String>) {
        val name = labels[decision.carId] ?: "car on file"
        val picked = labels[decision.pickOverridden] ?: "car you picked"
        post(
            context,
            title = "Recording as the $name",
            body = "The OBD dongle read the $name's ${DongleCar.vinTail(decision.vin.orEmpty())}, so live readings " +
                "go to the $name, not the $picked you picked. Your pick still answers questions about stored " +
                "data when nothing is connected.",
            buttons = emptyList(),
            silent = true,
        )
    }

    /** What a button tap did, in words. A VIN conflict carries a Replace button. */
    fun result(context: Context, outcome: DongleCarController.Outcome) {
        val conflict = DongleCarController.conflict.value
        val buttons = if (!outcome.ok && conflict != null) {
            listOf("Replace" to answerIntent(context, conflict.carId, conflict.subjectId, true))
        } else {
            emptyList()
        }
        post(
            context,
            title = if (outcome.ok) "OBD dongle car set" else "OBD dongle car not changed",
            body = outcome.message,
            buttons = buttons,
            silent = true,
        )
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        title: String,
        body: String,
        buttons: List<Pair<String, PendingIntent>>,
        silent: Boolean,
    ) {
        val app = context.applicationContext
        if (!mayPost(app)) return
        ensureChannel(app)
        val b = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openCars(app))
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        buttons.take(MAX_ACTIONS).forEach { (label, intent) -> b.addAction(0, label, intent) }
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, b.build())
    }

    private fun answerIntent(context: Context, carId: String, subjectId: String?, replace: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            (carId + subjectId + replace).hashCode(),
            Intent(context.applicationContext, DongleCarActionReceiver::class.java).apply {
                action = DongleCarActionReceiver.ACTION_ANSWER
                putExtra(DongleCarActionReceiver.EXTRA_CAR, carId)
                subjectId?.let { putExtra(DongleCarActionReceiver.EXTRA_SUBJECT, it) }
                putExtra(DongleCarActionReceiver.EXTRA_REPLACE, replace)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun newCarIntent(context: Context, subjectId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            NOTIFICATION_ID,
            Intent(context.applicationContext, DongleCarActionReceiver::class.java)
                .setAction(DongleCarActionReceiver.ACTION_NEW_CAR)
                .putExtra(DongleCarActionReceiver.EXTRA_SUBJECT, subjectId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openCars(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context.applicationContext,
            NOTIFICATION_ID,
            Intent(context.applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.FLEET_CARS)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mayPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "OBD dongle car", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Which car the OBD dongle is in, when it cannot tell by itself." }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

/** The notification's buttons: the same [DongleCarController] calls the voice path makes. */
class DongleCarActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = when (intent.action) {
                    ACTION_ANSWER -> {
                        val car = intent.getStringExtra(EXTRA_CAR) ?: return@launch
                        DongleCarController.answer(
                            app,
                            car,
                            intent.getStringExtra(EXTRA_SUBJECT),
                            intent.getBooleanExtra(EXTRA_REPLACE, false),
                        )
                    }
                    ACTION_NEW_CAR -> DongleCarController.answerNewCar(app, intent.getStringExtra(EXTRA_SUBJECT))
                    else -> return@launch
                }
                DongleCarNotifier.result(app, outcome)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing here may take the process down: say it did not finish, and where to retry.
                android.util.Log.w("DongleCar", "answer from the notification failed", e)
                DongleCarNotifier.result(
                    app,
                    DongleCarController.Outcome(
                        false,
                        "That did not finish, so the readings may still be waiting for a car. " +
                            "Check Cars, OBD dongle.",
                    ),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.kevin.legion.action.OBD_CAR_ANSWER"
        const val ACTION_NEW_CAR = "com.kevin.legion.action.OBD_CAR_NEW"
        const val EXTRA_CAR = "car_id"
        const val EXTRA_SUBJECT = "subject_id"
        const val EXTRA_REPLACE = "replace_vin"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
