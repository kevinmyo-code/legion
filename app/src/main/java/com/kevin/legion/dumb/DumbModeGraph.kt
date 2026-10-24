package com.kevin.legion.dumb

import android.app.Application
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kevin.legion.games.race.GameInviteNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dumb mode's object graph, built by hand until Hilt lands (architecture ticket 02). Every part is a
 * class with its dependencies passed in; this is the one place that picks the Android
 * implementations, and `MidnightApplication.dumb` is the single instance. Lazy throughout so a
 * process that never turns the mode on pays for none of it.
 */
class DumbModeGraph(private val app: Application) {
    val controller: DumbModeController by lazy { DumbModeController(PrefsDumbModeStore(app)) }
    val mapsChoice: MapsChoiceStore by lazy {
        PrefsMapsChoiceStore(app.getSharedPreferences(PrefsDumbModeStore.PREFS, Context.MODE_PRIVATE))
    }
    private val matrixPrefs by lazy { app.getSharedPreferences(PrefsDumbModeStore.PREFS, Context.MODE_PRIVATE) }

    /** The one blocking decision (ADR 0062, 2026-10-24): the bounce, the settings list and the drawer all ask it. */
    val blockPolicy: BlockPolicy by lazy {
        BlockPolicy(
            ownPackage = app.packageName,
            sources = AndroidBlockSources(app, AndroidKeptAppResolver(app), mapsChoice::get),
            overrides = PrefsAppOverrides(matrixPrefs),
        )
    }
    val people: ChosenPeople by lazy { ChosenPeople(PrefsChosenPeopleStore(app)) }
    val digest: HeldDigest by lazy { HeldDigest(PrefsDigestPersistence(app)) }
    val alertSwitch: AlertFallbackSwitch by lazy { AlertFallbackSwitch(app) }
    val dnd: DumbDnd by lazy { DumbDnd(AndroidDndGateway(app), PrefsDndSavedStore(app)) }

    val callScreener: DumbCallScreener by lazy {
        DumbCallScreener(
            isOn = { controller.state.value.on },
            isChosen = people::isChosenNumber,
            recent = RecentCallers(PrefsRecentCallStore(app)),
        )
    }

    val bounce: DumbBounce by lazy {
        DumbBounce(
            isOn = { controller.state.value.on },
            ownPackage = app.packageName,
            env = AndroidBounceEnvironment(app, blockPolicy),
            allowance = BounceAllowance(),
        )
    }

    private val alert: ChosenPersonAlert by lazy { ChosenPersonAlert(app, alertSwitch) }

    private val handler: HeldNotificationHandler by lazy {
        HeldNotificationHandler(
            hold = NotificationHold(
                isOn = { controller.state.value.on },
                ownPackage = app.packageName,
                inviteChannelId = GameInviteNotifier.CHANNEL_ID,
                people = { people.people.value },
            ),
            digest = digest,
            reAlert = { name, pkg, key, tap ->
                // Off by default, see BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON: an open question.
                if (BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON) bounce.allowance.allowPackage(pkg)
                alert.alert(name, appLabel(pkg), key, tap)
            },
        )
    }

    private val effects: DumbModeEffects by lazy {
        DumbModeEffects(
            isOn = DumbModeEffects.stateFlow(controller),
            dnd = dnd,
            digest = digest,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onResult = { Log.i(TAG, "DND step: $it") },
        )
    }

    /** Starts watching the mode. Called once from `Application.onCreate`. */
    fun start() = effects.start()

    /**
     * The listener's entry point. Never throws: a failure here must not take the listener, and with
     * it the now-playing feed, down. A failure is logged without any notification content.
     */
    fun onNotificationPosted(sbn: StatusBarNotification, cancel: (String) -> Unit) {
        if (!controller.state.value.on) return
        runCatching { handler.handle(sbn, cancel) }
            .onFailure { Log.w(TAG, "Could not handle a notification from ${sbn.packageName}", it) }
    }

    /** The app's own label for the re-alert text, or its package name when the system will not say. */
    fun appLabel(packageName: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "DumbMode"
    }
}
