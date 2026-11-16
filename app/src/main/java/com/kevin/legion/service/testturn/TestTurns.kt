package com.kevin.legion.service.testturn

import android.content.Context
import android.util.Log
import com.kevin.legion.service.conversationlog.ConversationLog
import java.io.File

/**
 * Test turns from a computer (Kevin, 2026-11-15: "can u make some sort of cli or what for the phone
 * so u can test it?"). `tools/phone_voice_test.py` drives a conversation over adb: a typed line is
 * taken as Kevin's own words, or a WAV clip is fed in place of the microphone.
 *
 * Three locks, all required: the Settings switch ([TestTurnSettings], off by default); the caller
 * must be adb's shell (or root), checked by uid in [TestTurnProvider], so no app on the phone can
 * reach this; and a test conversation runs every tool through [TestTurnPolicy], so nothing is
 * changed unless the case allowed writes. A test conversation is closed by the CLI after each run,
 * by the phone itself after [IDLE_CLOSE_MS] with no command or [MAX_OPEN_MS] in all, and at once by
 * a real tap or wake word (the real conversation then opens normally, outside test mode).
 */
object TestTurns {
    const val TAG = "LIVE_TEST"

    /** No command from the computer for this long: the phone closes the test conversation itself. */
    const val IDLE_CLOSE_MS = 90_000L

    /** A test conversation never stays open longer than this. */
    const val MAX_OPEN_MS = 15 * 60_000L

    /** Where the CLI pushes clips: the app's own external files dir, readable with no permission. */
    const val AUDIO_DIR = "voicetest"

    /** What the controller answers for. Every call may come from a binder thread. */
    interface Host {
        /** Flat facts for `status` (see [TestTurnCommands.STATUS_KEYS]). */
        fun status(): Map<String, Any>

        /** Kevin's words as a turn of a test conversation, opened if none is. Null, or why not. */
        fun turn(id: String, text: String, allowWrites: Boolean): String?

        /**
         * A 16 kHz mono PCM16 clip fed in place of the mic of a test conversation, once the open and
         * any reply have finished, or at once with [barge] (to talk over a reply). Null, or why not.
         */
        fun audio(id: String, pcm: ByteArray, allowWrites: Boolean, barge: Boolean): String?

        /** Closes the test conversation (a no-op when none is open). */
        fun end(reason: String)
    }

    @Volatile var host: Host? = null

    /** A test conversation's LIVE_AUDIT lines always reach logcat (the CLI reads them there). */
    fun auditSink(tag: String, message: String) {
        Log.i(tag, message)
        ConversationLog.record(tag, message)
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    fun audioFile(context: Context, name: String): File? =
        context.getExternalFilesDir(AUDIO_DIR)?.takeIf { safeName(name) }?.let { File(it, name) }

    /** A plain `.wav` file name: no folder, no `..`. */
    fun safeName(name: String): Boolean = SAFE_NAME.matches(name)

    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}\\.wav")
}

/**
 * The provider's commands as a pure function, so a JVM test pins every refusal: disabled, no
 * service, unknown method, missing text, bad clip.
 */
object TestTurnCommands {
    val STATUS_KEYS = listOf("conversation", "test_id", "speaking", "busy", "turns_answered", "phase")

    @Suppress("ReturnCount", "LongParameterList") // one worded refusal per guard
    fun handle(
        method: String,
        arg: String?,
        id: String,
        allowWrites: Boolean,
        enabled: Boolean,
        host: TestTurns.Host?,
        barge: Boolean = false,
        readClip: (String) -> ByteArray?,
    ): Map<String, Any> {
        if (method == "status") {
            val base = mapOf<String, Any>("ok" to true, "enabled" to enabled, "service" to (host != null))
            return if (host != null && enabled) base + host.status() else base
        }
        if (!enabled) return error("disabled: Settings, Assistant, Allow test turns from a computer is off")
        if (host == null) return error("service_not_running")
        val why = when (method) {
            "turn" -> if (arg.isNullOrBlank()) "no_text" else host.turn(id, arg.trim(), allowWrites)
            "audio" -> {
                val pcm = arg?.let(readClip) ?: return error(BAD_CLIP)
                host.audio(id, pcm, allowWrites, barge)
            }
            "end" -> {
                host.end("cli")
                null
            }
            else -> "unknown_method"
        }
        return if (why == null) mapOf("ok" to true) else error(why)
    }

    private fun error(why: String) = mapOf<String, Any>("ok" to false, "error" to why)

    private const val BAD_CLIP = "bad_clip: push a 16 kHz mono 16-bit WAV to files/voicetest/"
}
