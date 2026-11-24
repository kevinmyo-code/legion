package com.kevin.midnightai.util

import android.content.ActivityNotFoundException
import com.kevin.midnightai.MidnightEvents

/**
 * The one door for every tap that hands work to Android.
 *
 * This app is the car's home screen, declares two activities and no `android:process`, so an
 * uncaught throw out of a click handler kills the launcher and `AriaForegroundService` with it.
 * The screens a tap opens - `ACTION_REQUEST_DISCOVERABLE`, `ACTION_BLUETOOTH_SETTINGS`,
 * `ACTION_NOTIFICATION_LISTENER_SETTINGS`, DocumentsUI, a camera, a share sheet - are all OPTIONAL
 * parts of Android, and this gutted AOSP unit (AOSP 8 to 10, `CLAUDE.md` section 1) has some of
 * them and not others. A third-party app is worse: the tray's package list is read once, so a
 * package uninstalled since then throws from a tap on the home screen.
 *
 * So no caller writes its own catch. [attempt] runs the launch, swallows whatever this ROM throws,
 * records a non-fatal naming the door and the REAL exception class, and hands the caller the words
 * for what did not happen. The unit has no ADB (`CLAUDE.md` section 14), so that non-fatal and the
 * drive log it rides in are the only way the real reason is ever read: which exception this ROM
 * throws is diagnosed from the next drive log, not guessed here.
 */
object ExternalDoor {
    /**
     * Runs [open]. Returns null when Android accepted it, else the sentence to put on screen.
     *
     * A caller that has nowhere to show a sentence may ignore the return: the non-fatal is still
     * recorded, and a silent tap is strictly better than a dead launcher. A caller that HAS a
     * screen shows it, because that is the whole point of catching.
     *
     * [open] must be the launch call and nothing else. Work around it (rendering a bitmap, writing
     * a file) throws `IOException`, which is not a [RuntimeException] and is not caught here.
     */
    fun attempt(screen: ExternalScreen, named: String? = null, open: () -> Unit): String? {
        val (words, failure) = try {
            open()
            return null
        } catch (e: ActivityNotFoundException) {
            screen.missingWords(named) to e
        } catch (e: SecurityException) {
            screen.refusedWords(named) to e
        } catch (e: RuntimeException) {
            // ActivityNotFoundException and SecurityException are both RuntimeExceptions and are
            // caught above, so this is the net under every other documented throw from
            // Context.startActivity and ActivityResultLauncher.launch (IllegalStateException from
            // an unregistered launcher among them), plus whatever a vendor ROM invents.
            screen.failedWords(named) to e
        }
        MidnightEvents.recordError(
            "external_${screen.name.lowercase()}_${failure.javaClass.simpleName}",
            failure,
        )
        return words
    }
}
