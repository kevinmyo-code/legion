package com.kevin.legion.ui.dumb

import com.kevin.legion.service.Phase
import kotlin.math.log10

/** The motion beside a Matrix talk label. Each live phase gets its own, so two never move alike. */
internal enum class MatrixTalkMotion { NONE, SPINNER, CURSOR, DOTS, RUNNER, WAVE }

/**
 * What Matrix HOME's push-to-talk shows: the state line above the footer and the mic button's look.
 *
 * [label] carries the meaning on its own (CLAUDE.md section 4 rule 7): the motion and the filled
 * button only reinforce it. [spoken] is the TalkBack sentence; it never changes with the animation
 * frame, so a screen reader hears one announcement per state, not one per tick.
 */
internal data class MatrixTalkDisplay(
    val label: String,
    val detail: String?,
    val tone: MatrixTone,
    val motion: MatrixTalkMotion,
    /** True while LISTENING: the live mic level bar is the "it hears me" signal. */
    val showLevel: Boolean,
    /** True while a conversation is live: the button fills, and a tap stops it. */
    val live: Boolean,
    val spoken: String,
)

/**
 * Phase to Matrix talk display (Kevin, 2026-10-27: *"in matrix mode when i click the push to talk its
 * hard to tell if the agent is listening or thinking or not"*). Before this the Matrix mic button read
 * nothing at all, so every phase looked the same.
 *
 * Pure and Android-free, the same inputs and the same precedence as
 * [com.kevin.legion.ui.assistant.AssistantStripResolver]: a missing mic grant beats a silenced
 * capture, which beats a flashed notice, which beats the phase. IDLE after a failed tap and IDLE at
 * rest are the same [Phase]; only the notice tells them apart, which is why the notice is shown here.
 */
internal object MatrixTalkResolver {

    const val TAP_TO_STOP = "tap the mic to stop"

    val IDLE: MatrixTalkDisplay = forPhase(Phase.IDLE)

    /** [toolRunning] is [com.kevin.legion.service.CompanionPhase.toolRunning]: THINKING because a tool is in flight. */
    fun resolve(
        phase: Phase,
        notice: String?,
        micGranted: Boolean,
        silenced: Boolean,
        toolRunning: Boolean = false,
    ): MatrixTalkDisplay =
        when {
            !micGranted -> warning(
                label = "! MIC NOT ALLOWED",
                detail = "tap the mic for Settings",
                spoken = "Microphone not allowed. Tap the mic for Settings.",
            )
            silenced -> warning(
                label = "! CAN'T HEAR YOU",
                detail = "another app has the microphone",
                spoken = "Can't hear you. Another app has the microphone.",
            )
            notice != null -> warning(label = "! ${notice.uppercase()}", detail = null, spoken = notice)
            toolRunning && phase == Phase.THINKING -> WORKING
            else -> forPhase(phase)
        }

    fun forPhase(phase: Phase): MatrixTalkDisplay = when (phase) {
        Phase.IDLE -> MatrixTalkDisplay(
            "TAP TO TALK", null, MatrixTone.DIM, MatrixTalkMotion.NONE,
            showLevel = false, live = false, spoken = "Ready. Tap the mic to talk.",
        )
        Phase.CONNECTING -> MatrixTalkDisplay(
            "> CONNECTING", "opening the line", MatrixTone.BODY, MatrixTalkMotion.SPINNER,
            showLevel = false, live = false, spoken = "Connecting.",
        )
        Phase.LISTENING -> MatrixTalkDisplay(
            "> LISTENING", TAP_TO_STOP, MatrixTone.HEADING, MatrixTalkMotion.CURSOR,
            showLevel = true, live = true, spoken = "Listening. Tap the mic to stop.",
        )
        Phase.THINKING -> MatrixTalkDisplay(
            "> THINKING", TAP_TO_STOP, MatrixTone.HEADING, MatrixTalkMotion.DOTS,
            showLevel = false, live = true, spoken = "Thinking. Tap the mic to stop.",
        )
        Phase.SPEAKING -> MatrixTalkDisplay(
            "> SPEAKING", TAP_TO_STOP, MatrixTone.HEADING, MatrixTalkMotion.WAVE,
            showLevel = false, live = true, spoken = "Speaking. Tap the mic to stop.",
        )
    }

    /**
     * THINKING while a tool call is in flight: the controller's "Working..." status. No tool name: the
     * names are model-facing identifiers (`investigate`, `ledger_query`) and read badly as words.
     */
    val WORKING = MatrixTalkDisplay(
        "> WORKING", "tool running; tap to stop", MatrixTone.HEADING, MatrixTalkMotion.RUNNER,
        showLevel = false, live = true, spoken = "Working on it. Tap the mic to stop.",
    )

    private fun warning(label: String, detail: String?, spoken: String) = MatrixTalkDisplay(
        label, detail, MatrixTone.WARNING, MatrixTalkMotion.NONE, showLevel = false, live = false, spoken = spoken,
    )

    /**
     * The glyphs after the label for animation [tick] (one tick per [TICK_MS]). Every frame of one
     * motion is the same width so the line never jitters. A null [tick] is the still frame used when
     * system animations are off: it still differs per motion.
     */
    fun frame(motion: MatrixTalkMotion, tick: Int?): String {
        val frames = FRAMES.getValue(motion)
        if (tick == null) return frames.last()
        val slow = if (motion == MatrixTalkMotion.CURSOR) 2 else 1
        return frames[(tick / slow).mod(frames.size)]
    }

    private val FRAMES = mapOf(
        MatrixTalkMotion.NONE to listOf(""),
        MatrixTalkMotion.SPINNER to listOf(" /", " -", " \\", " |"),
        MatrixTalkMotion.CURSOR to listOf(" ", "_"),
        MatrixTalkMotion.DOTS to listOf("   ", ".  ", ".. ", "..."),
        MatrixTalkMotion.RUNNER to listOf(" [=  ]", " [ = ]", " [  =]", " [ = ]"),
        MatrixTalkMotion.WAVE to listOf(" )  ", " )) ", " )))"),
    )

    /** Animation tick length. The cursor blinks every two ticks (500ms). */
    const val TICK_MS = 250L

    /** Cells in the mic level bar. */
    const val LEVEL_CELLS = 8

    /** A reading older than this means the mic is not being read: shown as silence, not frozen. */
    const val LEVEL_STALE_MS = 400L

    // dBFS window the bar spans. GUESSED, not measured on the A25: a quiet room peaks near -50 dBFS
    // and close speech near -10, so the floor keeps hiss at zero or one cell and speech near full.
    private const val FLOOR_DB = -50.0
    private const val CEIL_DB = -10.0
    private const val FULL_SCALE = 32767.0
    private const val DB_PER_BEL = 20.0

    /** How many of [LEVEL_CELLS] to fill for a PCM16 [peak] read [ageMs] ago. */
    fun levelCells(peak: Int, ageMs: Long): Int {
        if (peak <= 0 || ageMs > LEVEL_STALE_MS || ageMs < 0) return 0
        val db = DB_PER_BEL * log10(peak.coerceAtMost(FULL_SCALE.toInt()) / FULL_SCALE)
        val fraction = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0.0, 1.0)
        return Math.round(fraction * LEVEL_CELLS).toInt()
    }

    /** The bar itself, in text: `[|||||...]`. */
    fun levelBar(cells: Int): String {
        val filled = cells.coerceIn(0, LEVEL_CELLS)
        return "[" + "|".repeat(filled) + ".".repeat(LEVEL_CELLS - filled) + "]"
    }
}
