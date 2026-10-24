package com.kevin.legion.dumb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.kevin.legion.media.SpotifyController

/**
 * The entries dumb mode's HOME shows, in order (Kevin, ADR 0062; Brief added after Calendar by
 * ai-upgrade ticket 02, opening the same modal as HOME's Brief chip; WhatsApp added after Messages
 * 2026-10-23, shown only when installed; All apps added after Books 2026-10-24). This is HOME's short
 * list, no longer an allowlist: since 2026-10-24 Matrix mode blocks feeds ([BlockModel]) and every
 * other app stays open, reachable through All apps. (This comment used to say everything not listed
 * is hidden, the browser included, and that the Brief showed News links as text for that reason.)
 */
enum class KeptEntry(val label: String) {
    PHONE("Phone"),
    MESSAGES("Messages"),
    WHATSAPP("WhatsApp"),
    MAPS("Maps"),
    CAMERA("Camera"),
    MUSIC("Music"),
    TODOS("To-dos"),
    CALENDAR("Calendar"),
    BRIEF("Brief"),
    BOOKS("Books"),
    ALL_APPS("All apps"),
    ASSISTANT("Assistant"),
    ;

    /**
     * True for the entries that always hand off to another app; the rest are LEGION's own screens.
     * Maps is neither: it opens Google Maps or LEGION's navigation by [MapsChoice], so it is handled
     * apart from this flag.
     */
    val isExternal: Boolean
        get() = this == PHONE || this == MESSAGES || this == WHATSAPP || this == CAMERA || this == MUSIC
}

/** The Maps entry's target, a setting on the Matrix mode settings screen (Kevin, 2026-10-23, ADR 0062). */
enum class MapsChoice { GOOGLE_MAPS, LEGION_NAV }

/** Where the Maps choice lives. Read live on every use: a change must reach the bounce at once. */
interface MapsChoiceStore {
    fun get(): MapsChoice
    fun set(choice: MapsChoice)
}

/** [MapsChoiceStore] over the Matrix mode preferences file. Google Maps is the default. */
class PrefsMapsChoiceStore(private val prefs: SharedPreferences) : MapsChoiceStore {
    override fun get(): MapsChoice =
        runCatching { MapsChoice.valueOf(prefs.getString(KEY, null).orEmpty()) }.getOrDefault(MapsChoice.GOOGLE_MAPS)

    override fun set(choice: MapsChoice) {
        prefs.edit().putString(KEY, choice.name).commit()
    }

    private companion object {
        const val KEY = "maps_choice"
    }
}

/** What tapping Maps does. Pure, so the not-installed branch is tested without a phone. */
sealed interface MapsRoute {
    data class Google(val packageName: String) : MapsRoute

    /** LEGION's own navigation; [notice] is the sentence to show when Google Maps was wanted and is absent. */
    data class LegionNav(val notice: String?) : MapsRoute

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val NOT_INSTALLED_NOTICE = "Google Maps is not installed on this phone, so Maps opened LEGION navigation."
        const val NOT_OPENED_NOTICE = "Google Maps could not be opened, so Maps opened LEGION navigation."

        fun decide(choice: MapsChoice, googleMapsPackage: String?): MapsRoute = when {
            choice == MapsChoice.LEGION_NAV -> LegionNav(null)
            googleMapsPackage == null -> LegionNav(NOT_INSTALLED_NOTICE)
            else -> Google(googleMapsPackage)
        }
    }
}

/**
 * Which package answers each external entry. Resolved at run time and never hard-coded to an OEM
 * (clone-and-run: a stranger's phone has a different dialer, SMS app and camera). A null means
 * nothing answers, and the screen says so in words.
 */
interface KeptAppResolver {
    fun dialerPackage(): String?
    fun smsPackage(): String?
    fun cameraPackage(): String?
    fun musicPackage(): String?

    /** `com.whatsapp` when installed, else null (the WhatsApp row is then not shown). */
    fun whatsappPackage(): String?

    /** Google Maps when installed, else null. Kept only while [MapsChoice.GOOGLE_MAPS] is the setting. */
    fun googleMapsPackage(): String?

    /**
     * Every package that answers [AlarmClock.ACTION_SHOW_ALARMS], resolved at run time (never an OEM
     * name: clone-and-run). Several when the phone has no default clock; empty means no clock app.
     */
    fun clockPackages(): Set<String>
}

/**
 * Packages [BlockModel] treats as kept (never blocked by seed or category) whatever the
 * settings: the resolved externals, WhatsApp
 * and the clock. Google Maps is NOT here: it depends on a setting and is added live by
 * [LiveKeptPackages]. LEGION itself is separate.
 */
fun KeptAppResolver.keptPackages(): Set<String> =
    (listOfNotNull(dialerPackage(), smsPackage(), cameraPackage(), musicPackage(), whatsappPackage()) +
        clockPackages()).toSet()

/**
 * The bounce's kept set. The setting-independent part is cached for the TTL (package-manager calls on
 * every window event are wasteful); the Google Maps part is read live on every call, so flipping the
 * Maps setting takes effect on the next window change rather than up to a minute later.
 */
class LiveKeptPackages(
    private val resolver: KeptAppResolver,
    private val mapsChoice: () -> MapsChoice,
    ttlMs: Long,
    clock: () -> Long,
) {
    private val base = TtlCache(ttlMs, clock) { resolver.keptPackages() }

    fun get(): Set<String> {
        val maps = if (mapsChoice() == MapsChoice.GOOGLE_MAPS) resolver.googleMapsPackage() else null
        return if (maps == null) base.get() else base.get() + maps
    }
}

/**
 * Android's answers. The dialer comes from [TelecomManager.getDefaultDialerPackage], the public
 * reader of the dialer role holder (`RoleManager.getRoleHolders` is a system API an app cannot
 * call). The camera is whoever answers the still-image camera intent; the `<queries>` entry in the
 * manifest is what makes that visible on API 30+.
 */
class AndroidKeptAppResolver(private val context: Context) : KeptAppResolver {
    override fun dialerPackage(): String? =
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    override fun smsPackage(): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    override fun cameraPackage(): String? {
        val info = context.packageManager.resolveActivity(cameraIntent(), 0)?.activityInfo ?: return null
        // "android" is the system's own chooser, not a camera app.
        return info.packageName.takeIf { it != "android" }
    }

    override fun musicPackage(): String? =
        SpotifyController.SPOTIFY_PACKAGE.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }

    override fun whatsappPackage(): String? = installed(WHATSAPP_PACKAGE)

    override fun googleMapsPackage(): String? = installed(MapsRoute.GOOGLE_MAPS_PACKAGE)

    // The `<queries>` entry for SHOW_ALARMS in the manifest is what makes this visible on API 30+.
    override fun clockPackages(): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
            .map { it.activityInfo.packageName }
            .filter { it != "android" }
            .toSet()
    }.getOrDefault(emptySet())

    private fun installed(packageName: String): String? =
        packageName.takeIf { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() != null }

    internal fun cameraIntent(): Intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}

/**
 * Opens a kept external app. Returns null on success, or a sentence saying what did NOT happen.
 * Phone uses ACTION_DIAL (the default dialer's dial pad) rather than the dialer package's launcher
 * entry, which some dialers do not export.
 */
class KeptAppLauncher(private val context: Context, private val resolver: KeptAppResolver) {

    fun launch(entry: KeptEntry): String? {
        val intent = when (entry) {
            KeptEntry.PHONE -> Intent(Intent.ACTION_DIAL)
            KeptEntry.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            KeptEntry.MESSAGES -> resolver.smsPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
            KeptEntry.MUSIC -> resolver.musicPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
            KeptEntry.WHATSAPP ->
                resolver.whatsappPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
            else -> null
        }
        return when {
            !entry.isExternal -> "${entry.label} opens inside LEGION."
            intent == null -> "${entry.label} is not available on this phone."
            else -> runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }.getOrElse { "${entry.label} could not be opened." }
        }
    }

    /** Opens [packageName]'s launcher activity. Null on success, else a sentence saying what did not happen. */
    fun launchPackage(packageName: String, label: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$label is not available on this phone."
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { "$label could not be opened." }
    }

    /**
     * The big clock on Matrix home: the system's alarm list, so an alarm can be set (Kevin,
     * 2026-10-23). Null on success. With nothing answering, or the launch failing, a sentence says so.
     */
    fun openAlarms(): String? {
        if (resolver.clockPackages().isEmpty()) return CLOCK_NONE
        return runCatching {
            context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { CLOCK_FAILED }
    }

    companion object {
        const val CLOCK_NONE = "No clock app on this phone can show alarms, so nothing opened."
        const val CLOCK_FAILED = "The clock app could not be opened."
    }
}
