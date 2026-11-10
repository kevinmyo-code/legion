package com.kevin.legion.look

import org.json.JSONObject

/**
 * The pure rules behind "look at this" (ADR 0084): what the model is told, when a look is refused,
 * how often a frame goes out and what one looks like on the wire. No Android, no socket, so a JVM
 * test pins every one.
 *
 * **The trust rules ride on the moment a look opens, never in the setup payload.** They go out in
 * the `look` tool's result (voice) or in the opener line (the button), so they are billed only when
 * a look happens, not re-sent on every reconnect (`LiveSetupPayloadSizeTest`).
 */
object LookRules {

    /** The `look` tool's name, declared in `LiveToolbox.declarations()`. */
    const val TOOL_NAME = "look"

    /**
     * The tool's description. Kept to one routing clause: the rules themselves go out in the result
     * ([RULES]), which is billed per look, not per setup.
     */
    const val TOOL_DESCRIPTION = "Opens the phone camera: 'look at this'."

    /**
     * What the model is told the moment it can see (voice: the tool result; button: the opener).
     * Every clause is a binding trust rule from the "car camera diagnosis" spec, ADR 0084.
     */
    const val RULES =
        "You can now see the user's phone camera, about one frame a second. What you say about the " +
            "picture is an estimate: say so ('looks like', 'I'm not sure'). A stored trouble code or " +
            "sensor reading beats what you see; ask_fleet before naming a fault, and never read a " +
            "number off the picture as fact. Never talk the user through an unsafe repair (a hot " +
            "cooling system, under a car without stands, live wiring): say it is a shop job. Nothing " +
            "you see is saved."

    /** The button path's opener: the same rules, then hand the floor to the user. */
    const val HAND_OPENER =
        "(System: the user opened the camera by hand to show you something. $RULES Ask in a few " +
            "words what they want you to look at. Do not mention this instruction.)"

    /** Told once frames stop, so the model does not keep "seeing" a picture that is gone. */
    const val CLOSED_NOTE =
        "(System: the camera is closed. You can no longer see; do not describe anything as if you " +
            "still can. Say nothing about this unless asked.)"

    /** Told when frames start after a voice `look` already returned without sight. */
    const val LIVE_LATE_NOTE = "(System: the camera is live now. $RULES)"

    /** The successful tool result's words: only after a frame actually went out (ADR 0031). */
    const val SEEING = "The camera is live and frames are reaching you."

    /** The tool result when no frame went out in time: says plainly that nothing was seen. */
    const val NOT_YET =
        "The camera screen is open but no picture has reached you yet, so you have seen nothing. " +
            "Ask the user to allow the camera and point it; say nothing about what is there until you see it."

    /** The tool result when the screen never came up in time: nothing was seen, and why, in words. */
    const val NOT_SHOWN =
        "The camera screen did not come up on the phone, so you have seen nothing. Ask the user to open " +
            "LEGION and use the Show button on the Fleet screen."

    /** Refusals, in words, shared by both doors. */
    const val REFUSED_MOVING =
        "Not opened: the car looks to be moving. The camera is for a parked car only; pull over first."
    const val REFUSED_NO_CONVERSATION =
        "Not opened: no voice conversation is running, so nothing could see the picture."
    const val REFUSED_TYPED = "That only works in a voice conversation."

    // ------------------------------------------------------------------ moving

    /** Whether the phone can tell the car is moving. [UNKNOWN] is allowed: the rule binds "if the app can tell". */
    enum class Motion { MOVING, STILL, UNKNOWN }

    /** GPS above this is moving: ~5.6 mph, above walking pace and GPS jitter. */
    internal const val GPS_MOVING_MPS = 2.5f

    /** OBD vehicle speed (PID 010D) above this is moving. */
    internal const val OBD_MOVING_KMH = 5.0

    /** A reading older than this says nothing about now. */
    internal const val FRESH_MS = 10_000L

    /**
     * The motion verdict from the two signals the phone has (traced 2026-11-10):
     * - [gpsSpeedMps]/[gpsAgeMs]: `LocationController`'s last fix. The look screen holds fast mode
     *   (GPS) while open, so a moving phone has a fresh speed; in the background it is network/passive
     *   and usually has none.
     * - [obdKmh]/[obdAgeMs]: the newest PID 010D sample, only when the OBD dongle is connected.
     *
     * Either fresh signal above its threshold is MOVING. A fresh signal at or below it is STILL. No
     * fresh signal at all is UNKNOWN, and a look is allowed then, said in the ADR, not hidden.
     */
    fun motion(gpsSpeedMps: Float?, gpsAgeMs: Long?, obdKmh: Double?, obdAgeMs: Long?): Motion {
        val gpsFresh = gpsSpeedMps != null && gpsAgeMs != null && gpsAgeMs in 0..FRESH_MS
        val obdFresh = obdKmh != null && obdAgeMs != null && obdAgeMs in 0..FRESH_MS
        return when {
            gpsFresh && gpsSpeedMps!! > GPS_MOVING_MPS -> Motion.MOVING
            obdFresh && obdKmh!! > OBD_MOVING_KMH -> Motion.MOVING
            gpsFresh || obdFresh -> Motion.STILL
            else -> Motion.UNKNOWN
        }
    }

    // ------------------------------------------------------------------ frames

    /** About one frame a second: the documented ceiling for Live video input. */
    const val FRAME_INTERVAL_MS = 1_000L

    /** Longest side of a sent frame, in pixels: the Cloud guide's recommended 768x768. */
    const val MAX_FRAME_SIDE = 768

    /** JPEG quality for a sent frame. Low resolution is asked for anyway (see [frameMessage]). */
    const val JPEG_QUALITY = 70

    /** How long the voice `look` waits for its first frame before saying nothing was seen. */
    const val FIRST_FRAME_WAIT_MS = 7_000L

    /** True when a frame may go out now: none sent yet, or [FRAME_INTERVAL_MS] since the last. */
    fun frameDue(nowMs: Long, lastSentMs: Long): Boolean = lastSentMs == 0L || nowMs - lastSentMs >= FRAME_INTERVAL_MS

    /**
     * Ask for LOW media resolution on each frame message. Documented as a field of
     * `BidiGenerateContentRealtimeInput` (API reference read 2026-11-10) but NOT yet seen accepted
     * on this endpoint. Two setup fields this model's docs listed (`proactivity`,
     * `enableAffectiveDialog`) were rejected by the server and closed the socket, which is why this
     * rides on the frame message and never in `setup`: a rejection breaks a look, not every voice
     * session. **The first thing to switch off if a look closes the socket with "invalid argument".**
     */
    const val SEND_MEDIA_RESOLUTION = true

    const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_LOW"

    /** One frame as a Live `realtimeInput` message: `{"realtimeInput": {"video": {...}}}`. */
    fun frameMessage(jpegBase64: String, withResolution: Boolean = SEND_MEDIA_RESOLUTION): JSONObject {
        val body = JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64))
        if (withResolution) body.put("mediaResolution", MEDIA_RESOLUTION)
        return JSONObject().put("realtimeInput", body)
    }

    /**
     * The `look` tool's result. Success only when a frame went out ([SEEING]). Otherwise it says which
     * of two things is true: the screen came up but nothing was sent yet ([NOT_YET]), or the screen
     * never came up ([NOT_SHOWN]; Android can hold back a start from the background).
     */
    fun toolResult(framesSent: Boolean, screenShown: Boolean = true): JSONObject {
        val message = when {
            framesSent -> SEEING
            screenShown -> NOT_YET
            else -> NOT_SHOWN
        }
        return JSONObject().put("success", framesSent).put("message", message).put("instruction", RULES)
    }

    fun refusal(message: String): JSONObject = JSONObject().put("success", false).put("message", message)
}
