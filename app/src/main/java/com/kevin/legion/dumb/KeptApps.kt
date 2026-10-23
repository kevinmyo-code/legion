package com.kevin.legion.dumb

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.kevin.legion.media.SpotifyController

/**
 * The ten entries dumb mode's HOME shows, in order (Kevin, ADR 0062; Brief added after Calendar by
 * ai-upgrade ticket 02, opening the same modal as HOME's Brief chip). The list is the allowlist:
 * everything else is hidden, including the browser, and a new install is hidden automatically. That
 * is why the Brief modal shows its News links as text in dumb mode rather than opening them.
 */
enum class KeptEntry(val label: String) {
    PHONE("Phone"),
    MESSAGES("Messages"),
    MAPS("Maps"),
    CAMERA("Camera"),
    MUSIC("Music"),
    TODOS("To-dos"),
    CALENDAR("Calendar"),
    BRIEF("Brief"),
    BOOKS("Books"),
    ASSISTANT("Assistant"),
    ;

    /** True for the four entries that hand off to another app; the rest are LEGION's own screens. */
    val isExternal: Boolean get() = this == PHONE || this == MESSAGES || this == CAMERA || this == MUSIC
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
}

/** Packages that stay launchable in dumb mode: the four resolved externals. LEGION itself is separate. */
fun KeptAppResolver.keptPackages(): Set<String> =
    listOfNotNull(dialerPackage(), smsPackage(), cameraPackage(), musicPackage()).toSet()

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

    internal fun cameraIntent(): Intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
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
}
